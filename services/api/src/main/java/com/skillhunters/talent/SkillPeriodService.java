package com.skillhunters.talent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.documents.StructuredExtractor.Field;
import com.skillhunters.shared.ApiProblem.Rejected;
import com.skillhunters.taxonomy.TaxonomyService.Match;
import com.skillhunters.talent.ReviewService.*;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SkillPeriodService {
    public record Link(int skillIndex,int experienceIndex,Period period,String evidence,boolean confirmed) {}
    public record Request(UUID revisionId,List<Link> links,String reason) {}
    public record Snapshot(long version,UUID revisionId,List<Link> links,UUID actorId,String reason,String createdAt) {}
    public record SkillResult(String key,String label,Match taxonomy,List<Integer> sourceIndices,Map<Kind,PeriodCalculator.Result> byKind,PeriodCalculator.Result professional) {}
    public record Report(long version,UUID revisionId,LocalDate referenceDate,String rule,List<Link> links,
                         Map<Kind,PeriodCalculator.Result> globalByKind,PeriodCalculator.Result globalProfessional,List<SkillResult> skills,Snapshot lastEdit) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final ObjectMapper json;
    private final CandidateService candidates;private final ReviewService reviews;
    public SkillPeriodService(JdbcClient db,TransactionTemplate tx,ObjectMapper json,CandidateService candidates,ReviewService reviews) {
        this.db=db;this.tx=tx;this.json=json;this.candidates=candidates;this.reviews=reviews;
    }
    public Report get(UUID actor,UUID id,LocalDate reference) {
        if(reference==null) reference=LocalDate.now(ZoneOffset.UTC);
        if(reference.isAfter(LocalDate.now(ZoneOffset.UTC))||reference.isBefore(LocalDate.of(1900,1,1))) invalid();
        final var date=reference;
        return tx.execute(t->{var state=reviews.get(actor,id);var pub=reviews.publication(actor,id);
            var snapshot=latest(id,pub.revisionId());var links=snapshot==null?List.<Link>of():snapshot.links();
            var globals=new ArrayList<Period>();
            if(pub.revisionId()!=null) {
                var rev=reviews.revision(actor,id,pub.revisionId());
                for(var item:rev.items()) if(item.decision()==Decision.VALIDATED&&state.source().proposal().claims().get(item.sourceIndex()).field()==Field.EXPERIENCE)
                    globals.add(item.period()==null?new Period(null,null,DatePrecision.UNKNOWN,Kind.UNKNOWN):item.period());
            }
            var groups=new LinkedHashMap<String,List<Indexed>>();
            for(var item:pub.items()) if(item.field()==Field.SKILL) {
                var m=item.taxonomy();String key=m!=null&&m.skillId()!=null?m.skillId()+":"+m.dictionaryVersion():"source:"+item.sourceIndex();
                groups.computeIfAbsent(key,k->new ArrayList<>()).add(item);
            }
            var skills=new ArrayList<SkillResult>();
            groups.forEach((key,items)->{
                var indices=items.stream().map(Indexed::sourceIndex).toList();var first=items.getFirst();
                var periods=links.stream().filter(l->indices.contains(l.skillIndex())).map(Link::period).toList();
                skills.add(new SkillResult(key,first.taxonomy()!=null&&first.taxonomy().canonical()!=null?first.taxonomy().canonical():first.value(),first.taxonomy(),indices,byKind(periods,date),professional(periods,date)));
            });
            return new Report(state.version(),pub.revisionId(),date,PeriodCalculator.RULE,links,byKind(globals,date),professional(globals,date),skills,snapshot);
        });
    }
    public Report save(UUID actor,UUID id,String etag,Request request) {
        return tx.execute(t->{var state=reviews.get(actor,id);
            if(etag==null) throw new Rejected(HttpStatus.PRECONDITION_REQUIRED,"VERSION_REQUIRED");
            if(!etag.equals("\""+state.version()+"\"")) throw new Rejected(HttpStatus.PRECONDITION_FAILED,"CANDIDATE_CHANGED");
            if(request==null||request.revisionId()==null||!request.revisionId().equals(state.publishedRevisionId())) throw new Rejected(HttpStatus.CONFLICT,"PUBLICATION_CHANGED");
            text(request.reason());if(request.links()==null||request.links().size()>200) invalid();
            var rev=reviews.revision(actor,id,request.revisionId());var claims=state.source().proposal().claims();
            var unique=new HashSet<String>();
            for(var link:request.links()) {
                if(link==null||!link.confirmed()||link.skillIndex()<0||link.experienceIndex()<0||link.skillIndex()>=claims.size()||link.experienceIndex()>=claims.size()) invalid();
                if(claims.get(link.skillIndex()).field()!=Field.SKILL||claims.get(link.experienceIndex()).field()!=Field.EXPERIENCE) invalid();
                var skill=rev.items().get(link.skillIndex());var experience=rev.items().get(link.experienceIndex());
                if(skill.decision()!=Decision.VALIDATED||experience.decision()!=Decision.VALIDATED) invalid();
                text(link.evidence());
                // A literal excerpt of the existing source plus explicit human association, never an invented citation.
                if(claims.get(link.experienceIndex()).evidence().stream().noneMatch(e->e.quote().contains(link.evidence())))
                    throw new Rejected(HttpStatus.BAD_REQUEST,"PERIOD_EVIDENCE_REQUIRED");
                validatePeriod(link.period(),experience.period()==null?new Period(null,null,DatePrecision.UNKNOWN,Kind.UNKNOWN):experience.period());
                if(!unique.add(link.skillIndex()+":"+link.experienceIndex()+":"+link.period())) invalid();
            }
            candidates.recordReview(actor,id,state.version(),"SKILL_PERIODS:"+request.revisionId());
            db.sql("INSERT INTO candidate_period_links(candidate_id,revision_id,version,links_json,actor_id,reason) VALUES (:id,:revision,:version,:json,:actor,:reason)")
                .param("id",id).param("revision",request.revisionId()).param("version",state.version()+1).param("json",write(request.links()))
                .param("actor",actor).param("reason",request.reason()).update();
            candidates.get(actor,id);
            return get(actor,id,null);
        });
    }
    public List<Snapshot> history(UUID actor,UUID id,int cursor) {
        if(cursor<0||cursor>100000) invalid();
        return tx.execute(t->{candidates.get(actor,id);return db.sql("SELECT version,revision_id,links_json,actor_id,reason,created_at FROM candidate_period_links WHERE candidate_id=:id ORDER BY version DESC LIMIT 25 OFFSET :cursor")
            .param("id",id).param("cursor",cursor).query((r,n)->snapshot(r)).list();});
    }
    private Snapshot latest(UUID id,UUID revision) {
        if(revision==null) return null;
        return db.sql("SELECT version,revision_id,links_json,actor_id,reason,created_at FROM candidate_period_links WHERE candidate_id=:id AND revision_id=:revision ORDER BY version DESC LIMIT 1")
            .param("id",id).param("revision",revision).query((r,n)->snapshot(r)).optional().orElse(null);
    }
    private Snapshot snapshot(java.sql.ResultSet r) throws java.sql.SQLException {
        try{return new Snapshot(r.getLong(1),r.getObject(2,UUID.class),List.of(json.readValue(r.getString(3),Link[].class)),r.getObject(4,UUID.class),r.getString(5),r.getString(6));}
        catch(java.io.IOException e){throw new IllegalStateException("PERIOD_LINK_STORAGE");}
    }
    private static Map<Kind,PeriodCalculator.Result> byKind(List<Period> periods,LocalDate date) {
        var result=new EnumMap<Kind,PeriodCalculator.Result>(Kind.class);
        for(var kind:Kind.values()) result.put(kind,PeriodCalculator.calculate(periods.stream().filter(p->p.kind()==kind).toList(),date));
        return result;
    }
    private static PeriodCalculator.Result professional(List<Period> periods,LocalDate date) {
        return PeriodCalculator.calculate(periods.stream().filter(p->p.kind()!=Kind.ACADEMIC).map(p->p.kind()==Kind.UNKNOWN?new Period(null,null,DatePrecision.UNKNOWN,Kind.UNKNOWN):p).toList(),date);
    }
    private static void validatePeriod(Period p,Period outer) {
        if(p==null||outer==null||p.precision()==null||p.kind()==null||p.kind()!=outer.kind()||p.precision()!=outer.precision()) invalid();
        if(p.precision()==DatePrecision.UNKNOWN) {if(p.start()!=null||p.end()!=null) invalid();return;}
        try {
            String pattern=switch(p.precision()){case DAY->"[0-9]{4}-[0-9]{2}-[0-9]{2}";case MONTH->"[0-9]{4}-[0-9]{2}";case YEAR->"[0-9]{4}";default->"";};
            if(p.start()==null||p.end()==null||!p.start().matches(pattern)||!p.end().matches(pattern)||p.start().startsWith("0000")||p.end().startsWith("0000")) invalid();
            var b=PeriodCalculator.bounds(p);var o=PeriodCalculator.bounds(outer);
            if(!b.possible().start().isBefore(b.possible().endExclusive())||b.possible().start().isBefore(o.possible().start())||b.possible().endExclusive().isAfter(o.possible().endExclusive())) invalid();
        } catch(java.time.DateTimeException|NumberFormatException e) {invalid();}
    }
    private static void text(String s) {if(s==null||s.isBlank()||s.length()>500||s.chars().anyMatch(c->Character.isISOControl(c)&&c!='\n'&&c!='\t')) invalid();}
    private String write(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("PERIOD_LINK_SERIALIZATION");}}
    private static void invalid() {throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_SKILL_PERIOD");}
}
