package com.skillhunters.talent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.documents.ExtractionService;
import com.skillhunters.documents.StructuredExtractor;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReviewService {
    public enum Decision { PENDING, VALIDATED, REJECTED, UNKNOWN }
    public enum Precision { UNKNOWN, TEXT, DECLARED_LEVEL }
    public enum DatePrecision { UNKNOWN, DAY, MONTH, YEAR }
    public enum Kind { UNKNOWN, EMPLOYMENT, INTERNSHIP, FREELANCE, ACADEMIC }
    public record Period(String start, String end, DatePrecision precision, Kind kind) {}
    public record Item(int sourceIndex, String value, Decision decision, Precision precision, Period period, String reason) {}
    public record Change(int sourceIndex, Item before, Item after) {}
    public record Revision(UUID id, UUID candidateId, UUID sourceRevisionId, UUID parentId, long version, String status,
                           List<Item> items, List<Change> changes, UUID actorId, OffsetDateTime createdAt, String justification) {}
    public record State(long version, ExtractionService.Revision source, Revision latest, UUID publishedRevisionId) {}
    public record Request(List<Item> items, String justification) {}
    public record Summary(UUID id, long version, String status, UUID actorId, OffsetDateTime createdAt) {}
    public record Indexed(int sourceIndex, StructuredExtractor.Field field, String value, Period period) {}
    public record Publication(UUID revisionId, List<Indexed> items) {}
    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final CandidateService candidates;
    public ReviewService(JdbcClient db,TransactionTemplate tx,ObjectMapper json,CandidateService candidates) {
        this.db=db;this.tx=tx;this.json=json;this.candidates=candidates;
    }
    private CandidateService.Candidate access(UUID actor,UUID id,boolean lock) {
        var c=candidates.get(actor,id); // Actor lock stays held by the enclosing transaction.
        if(lock) {
            db.sql("SELECT id FROM candidate_profile WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).single();
            c=candidates.get(actor,id);
        }
        return c;
    }
    private ExtractionService.Revision source(CandidateService.Candidate c) {
        return read(db.sql("SELECT revision_json FROM extraction_revision WHERE id=:revision AND import_id=:source")
            .param("revision",c.revisionId()).param("source",c.importId()).query(String.class).single(),ExtractionService.Revision.class);
    }
    private Revision latest(UUID id,boolean published) {
        return db.sql("SELECT revision_json FROM candidate_revision WHERE candidate_id=:id"+(published?" AND status='PUBLISHED'":"")+" ORDER BY version DESC LIMIT 1")
            .param("id",id).query(String.class).optional().map(s->read(s,Revision.class)).orElse(null);
    }
    public State get(UUID actor,UUID id) {
        return tx.execute(s->{var c=access(actor,id,true);var published=latest(id,true);
            return new State(c.version(),source(c),latest(id,false),published==null?null:published.id());});
    }
    public Revision revision(UUID actor,UUID id,UUID revision) {
        return tx.execute(s->{access(actor,id,false);
            return db.sql("SELECT revision_json FROM candidate_revision WHERE candidate_id=:id AND id=:revision")
                .param("id",id).param("revision",revision).query(String.class).optional().map(v->read(v,Revision.class))
                .orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"REVIEW_NOT_FOUND"));});
    }
    public CandidateService.Page<Summary> history(UUID actor,UUID id,int cursor,int limit) {
        if(cursor<0||cursor>100000||limit<1||limit>100) invalid();
        return tx.execute(s->{access(actor,id,false);
            var rows=db.sql("SELECT revision_json FROM candidate_revision WHERE candidate_id=:id ORDER BY version DESC LIMIT :limit OFFSET :offset")
                .param("id",id).param("limit",limit+1).param("offset",cursor).query(String.class).list();
            return new CandidateService.Page<>(rows.stream().limit(limit).map(v->{var r=read(v,Revision.class);return new Summary(r.id(),r.version(),r.status(),r.actorId(),r.createdAt());}).toList(),rows.size()>limit?""+(cursor+limit):null);
        });
    }
    public Publication publication(UUID actor,UUID id) {
        return tx.execute(s->{access(actor,id,true);var published=latest(id,true);
            var rows=db.sql("SELECT source_index,field,normalized_value,period_start,period_end,period_precision,period_kind FROM candidate_assertion_index WHERE candidate_id=:id ORDER BY source_index")
                .param("id",id).query((rs,n)->new Indexed(rs.getInt(1),StructuredExtractor.Field.valueOf(rs.getString(2)),rs.getString(3),
                    rs.getString(4)==null?null:new Period(rs.getString(4),rs.getString(5),DatePrecision.valueOf(rs.getString(6)),Kind.valueOf(rs.getString(7))))).list();
            return new Publication(published==null?null:published.id(),rows);});
    }
    public Revision save(UUID actor,UUID id,String etag,Request request,boolean publish) {
        return tx.execute(s->{var c=access(actor,id,true);
            if(etag==null) throw new Rejected(HttpStatus.PRECONDITION_REQUIRED,"VERSION_REQUIRED");
            if(!etag.equals("\""+c.version()+"\"")) throw new Rejected(HttpStatus.PRECONDITION_FAILED,"CANDIDATE_CHANGED");
            if(request==null||request.items()==null) invalid();
            text(request.justification(),500,false);
            var source=source(c);var claims=source.proposal().claims();
            if(request.items().size()!=claims.size()) invalid();
            var previous=latest(id,false);var items=new ArrayList<Item>();var changes=new ArrayList<Change>();
            for(int i=0;i<claims.size();i++) {
                var item=request.items().get(i);var claim=claims.get(i);
                validate(item,i,claim,publish);items.add(item);
                Item before=previous==null?initial(i,claim):previous.items().get(i);
                if(!item.equals(before)) {
                    text(item.reason(),500,false);
                    changes.add(new Change(i,before,item));
                }
            }
            if(publish) consistency(items,claims);
            var revision=new Revision(UUID.randomUUID(),id,source.id(),previous==null?null:previous.id(),c.version()+1,
                publish?"PUBLISHED":"DRAFT",List.copyOf(items),List.copyOf(changes),actor,OffsetDateTime.now(ZoneOffset.UTC),request.justification());
            candidates.recordReview(actor,id,c.version(),"REVIEW_"+revision.status()+":"+revision.id());
            db.sql("INSERT INTO candidate_revision(id,candidate_id,version,status,revision_json) VALUES (:revision,:id,:version,:status,:json)")
                .param("revision",revision.id()).param("id",id).param("version",revision.version()).param("status",revision.status()).param("json",write(revision)).update();
            if(publish) {
                db.sql("DELETE FROM candidate_assertion_index WHERE candidate_id=:id").param("id",id).update();
                for(int i=0;i<items.size();i++) {
                    var item=items.get(i);var field=claims.get(i).field();
                    if(item.decision()!=Decision.VALIDATED) continue;
                    // Restricted structured index: never name, contact, raw evidence, language or narrative CV text.
                    boolean label=field==StructuredExtractor.Field.SKILL||field==StructuredExtractor.Field.DECLARED_LEVEL;
                    boolean dated=field==StructuredExtractor.Field.EXPERIENCE&&item.period()!=null&&item.period().precision()!=DatePrecision.UNKNOWN;
                    if(!label&&!dated) continue;
                    db.sql("INSERT INTO candidate_assertion_index(candidate_id,source_index,revision_id,field,normalized_value,period_start,period_end,period_precision,period_kind) VALUES (:id,:index,:revision,:field,:value,:start,:end,:precision,:kind)")
                        .param("id",id).param("index",i).param("revision",revision.id()).param("field",field.name())
                        .param("value",label?item.value():null).param("start",dated?item.period().start():null).param("end",dated?item.period().end():null)
                        .param("precision",dated?item.period().precision().name():null).param("kind",dated?item.period().kind().name():null).update();
                }
            }
            access(actor,id,false); // Expiry during review cannot publish any derivative.
            return revision;
        });
    }
    private static Item initial(int index,StructuredExtractor.Claim claim) {
        return new Item(index,claim.normalized()!=null?claim.normalized():claim.raw(),
            claim.status()==StructuredExtractor.State.UNKNOWN?Decision.UNKNOWN:Decision.PENDING,
            claim.status()==StructuredExtractor.State.UNKNOWN?Precision.UNKNOWN:claim.field()==StructuredExtractor.Field.DECLARED_LEVEL?Precision.DECLARED_LEVEL:Precision.TEXT,
            null,null);
    }
    private static void validate(Item item,int index,StructuredExtractor.Claim claim,boolean publish) {
        if(item==null||item.sourceIndex()!=index||item.decision()==null||item.precision()==null) invalid();
        text(item.reason(),500,true);text(item.value(),2048,true);
        if(publish&&item.decision()==Decision.PENDING) throw new Rejected(HttpStatus.CONFLICT,"REVIEW_INCOMPLETE");
        if(item.decision()==Decision.UNKNOWN||item.decision()==Decision.REJECTED) {
            if(item.value()!=null||item.precision()!=Precision.UNKNOWN||item.period()!=null) invalid();
            return;
        }
        if(item.decision()==Decision.VALIDATED) {
            text(item.value(),2048,false);
            if(claim.field()==StructuredExtractor.Field.DECLARED_LEVEL) {
                if(item.precision()!=Precision.DECLARED_LEVEL||!Set.of("1","1.5","2","2.5","3").contains(item.value())) invalid();
            } else if(item.precision()!=Precision.TEXT) invalid();
            if(claim.field()==StructuredExtractor.Field.SKILL) {
                if(item.value().length()>200||item.value().contains("@")||item.value().matches(".*[0-9]{5,}.*")) invalid();
            }
        }
        if(item.period()!=null) {
            if(claim.field()!=StructuredExtractor.Field.EXPERIENCE&&claim.field()!=StructuredExtractor.Field.EDUCATION) invalid();
            var p=item.period();if(p.precision()==null||p.kind()==null) invalid();
            if(p.precision()==DatePrecision.UNKNOWN) {if(p.start()!=null||p.end()!=null) invalid();}
            else {
                try {
                    if(p.start()==null||p.end()==null||p.start().length()>10||p.end().length()>10) invalid();
                    long start=ordinal(p.start(),p.precision()),end=ordinal(p.end(),p.precision());if(end<start) invalid();
                } catch(java.time.DateTimeException e) {invalid();}
            }
            if(claim.field()==StructuredExtractor.Field.EDUCATION&&p.kind()!=Kind.UNKNOWN&&p.kind()!=Kind.ACADEMIC) invalid();
        }
    }
    private static long ordinal(String date,DatePrecision precision) {
        String pattern=switch(precision) {case DAY -> "[0-9]{4}-[0-9]{2}-[0-9]{2}";case MONTH -> "[0-9]{4}-[0-9]{2}";case YEAR -> "[0-9]{4}";case UNKNOWN -> "";};
        if(!date.matches(pattern)||date.startsWith("0000")) invalid();
        return switch(precision) {
            case DAY -> LocalDate.parse(date).toEpochDay();
            case MONTH -> {var m=YearMonth.parse(date);yield m.getYear()*12L+m.getMonthValue();}
            case YEAR -> Year.parse(date).getValue();
            case UNKNOWN -> 0;
        };
    }
    private static void consistency(List<Item> items,List<StructuredExtractor.Claim> claims) {
        // A level cannot survive rejection of its skill; singleton contradictions require an explicit choice.
        for(var field:List.of(StructuredExtractor.Field.FULL_NAME,StructuredExtractor.Field.TITLE,StructuredExtractor.Field.AVAILABILITY)) {
            long count=0;for(int i=0;i<items.size();i++) if(claims.get(i).field()==field&&items.get(i).decision()==Decision.VALIDATED) count++;
            if(count>1) throw new Rejected(HttpStatus.CONFLICT,"REVIEW_CONTRADICTION");
        }
        for(int i=0;i<items.size();i++) if(claims.get(i).field()==StructuredExtractor.Field.DECLARED_LEVEL&&items.get(i).decision()==Decision.VALIDATED) {
            boolean found=false;
            for(int j=0;j<items.size();j++) if(claims.get(j).field()==StructuredExtractor.Field.SKILL&&claims.get(j).item()==claims.get(i).item()&&items.get(j).decision()==Decision.VALIDATED) found=true;
            if(!found) throw new Rejected(HttpStatus.CONFLICT,"REVIEW_CONTRADICTION");
        }
    }
    private static void text(String value,int max,boolean nullable) {
        if(value==null) {if(!nullable) invalid();return;}
        if(value.isBlank()||value.length()>max||value.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t')) invalid();
    }
    private static void invalid() {throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_REVIEW");}
    private <T>T read(String value,Class<T> type) {try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("INVALID_REVIEW_STORAGE");}}
    private String write(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("REVIEW_SERIALIZATION");}}
}
