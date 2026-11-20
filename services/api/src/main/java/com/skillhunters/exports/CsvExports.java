package com.skillhunters.exports;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.governance.AuditTrail;
import com.skillhunters.scoring.ScoredSearchService;
import com.skillhunters.shared.ApiProblem.Rejected;
import com.skillhunters.talent.CandidateIdentity;
import com.skillhunters.talent.CandidateService;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CsvExports {
    public static final List<String> BASIC=List.of("candidateId","displayName","professionalTitle","availability");
    private static final Set<String> ALL=Set.of("candidateId","displayName","professionalTitle","availability","email","phone","score");
    public enum Purpose { RECRUITMENT_REVIEW, INTERNAL_COMPARISON }
    public record Request(UUID poolId,List<UUID> candidateIds,List<String> fields,Purpose purpose,UUID searchId) {}
    public record Options(List<String> fields,int maxRows,int maxExportsPer24Hours,int maxRowsPer24Hours,int validSeconds) {}
    public record Receipt(UUID id,String downloadUrl,OffsetDateTime expiresAt,List<String> fields,int rowCount) {}
    public record Download(String filename,byte[] content) {}
    private record Ticket(UUID id,UUID actorId,UUID poolId,UUID searchId,String fields,String purpose,int rowCount,OffsetDateTime expiresAt,OffsetDateTime consumedAt) {}
    private record Member(UUID candidateId,long candidateVersion) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final CandidateService candidates;private final CandidateIdentity identities;
    private final ExportPermissions permissions;private final ScoredSearchService scores;private final AuditTrail audit;private final ObjectMapper json;
    public CsvExports(JdbcClient db,TransactionTemplate tx,CandidateService candidates,CandidateIdentity identities,ExportPermissions permissions,ScoredSearchService scores,AuditTrail audit,ObjectMapper json){this.db=db;this.tx=tx;this.candidates=candidates;this.identities=identities;this.permissions=permissions;this.scores=scores;this.audit=audit;this.json=json;}
    private void actor(UUID actor){if(!db.sql("SELECT enabled FROM app_user WHERE id=:a FOR UPDATE").param("a",actor).query(Boolean.class).optional().orElse(false))fail(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED");}
    private Options authorize(UUID actor,UUID pool){
        actor(actor);
        if(db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:a AND pool_id=:p").param("a",actor).param("p",pool).query(Integer.class).single()==0)fail(HttpStatus.NOT_FOUND,"POOL_NOT_FOUND");
        if(!permissions.allowed(actor,pool))fail(HttpStatus.FORBIDDEN,"EXPORT_PERMISSION_REQUIRED");
        var fields=new ArrayList<>(BASIC);
        if(db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:a AND pool_id=:p AND role='RECRUITMENT_LEAD'").param("a",actor).param("p",pool).query(Integer.class).single()>0)fields.addAll(List.of("email","phone"));
        fields.add("score");return new Options(fields,50,20,500,300);
    }
    public Options options(UUID actor,UUID pool){return tx.execute(t->authorize(actor,pool));}
    private void mutex(){db.sql("SELECT id FROM retention_mutex WHERE id=1 FOR UPDATE").query(Integer.class).single();}
    private List<CandidateService.Candidate> selection(UUID actor,UUID pool,List<UUID> ids,List<String> fields){
        var allowed=authorize(actor,pool).fields();if(!allowed.containsAll(fields))fail(HttpStatus.FORBIDDEN,"CSV_FIELD_FORBIDDEN");
        // Same ordering as lineage and erasure: retention mutex, sorted identity groups, profiles.
        var groups=new HashMap<UUID,UUID>();for(UUID id:ids)groups.put(id,identities.id(id));identities.lockIds(groups.values());
        for(UUID id:ids.stream().sorted().toList()){
            if(!groups.get(id).equals(identities.id(id)))fail(HttpStatus.CONFLICT,"CSV_SELECTION_CHANGED");
            db.sql("SELECT id FROM candidate_profile WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).single();
        }
        var rows=new ArrayList<CandidateService.Candidate>();
        for(UUID id:ids){var c=candidates.get(actor,id);if(!c.poolId().equals(pool))fail(HttpStatus.NOT_FOUND,"CANDIDATE_NOT_FOUND");if(!c.activeVersion())fail(HttpStatus.CONFLICT,"CSV_SELECTION_CHANGED");rows.add(c);}
        return rows;
    }
    public Receipt create(UUID actor,Request request){
        if(request==null||request.poolId()==null||request.purpose()==null||request.candidateIds()==null||request.candidateIds().isEmpty()||request.candidateIds().size()>50
            ||request.candidateIds().stream().anyMatch(Objects::isNull)||new HashSet<>(request.candidateIds()).size()!=request.candidateIds().size()
            ||request.fields()==null||request.fields().isEmpty()||request.fields().size()>ALL.size()||request.fields().stream().anyMatch(f->f==null||!ALL.contains(f))
            ||new HashSet<>(request.fields()).size()!=request.fields().size()||request.fields().contains("score")&&request.searchId()==null||!request.fields().contains("score")&&request.searchId()!=null)
            fail(HttpStatus.BAD_REQUEST,"INVALID_CSV_EXPORT");
        return tx.execute(t->{
            authorize(actor,request.poolId());mutex();var rows=selection(actor,request.poolId(),request.candidateIds(),request.fields());
            OffsetDateTime now=now(),expires=now.plusMinutes(5);
            if(request.searchId()!=null){var m=scores.metadata(actor,request.searchId());if(!m.trace().poolId().equals(request.poolId()))fail(HttpStatus.NOT_FOUND,"SCORE_NOT_FOUND");expires=earlier(expires,m.expiresAt());for(var c:rows)scores.replay(actor,request.searchId(),c.id());}
            for(var c:rows)expires=earlier(expires,c.expiresAt());
            if(!expires.isAfter(now()))fail(HttpStatus.GONE,"CSV_EXPORT_EXPIRED");
            var counts=db.sql("SELECT COUNT(*),COALESCE(SUM(csv_rows),0) FROM governance_audit WHERE actor_id=:a AND action='CSV_EXPORT_CREATED' AND occurred_at>:since")
                .param("a",actor).param("since",now.minusHours(24)).query((r,n)->new long[]{r.getLong(1),r.getLong(2)}).single();
            if(counts[0]>=20||counts[1]+rows.size()>500)fail(HttpStatus.TOO_MANY_REQUESTS,"CSV_EXPORT_QUOTA");
            UUID id=UUID.randomUUID();String fields=String.join(",",request.fields());
            db.sql("INSERT INTO csv_export_ticket(id,actor_id,pool_id,search_id,fields,purpose,row_count,created_at,expires_at) VALUES (:id,:a,:p,:s,:f,:purpose,:n,:now,:expires)")
                .param("id",id).param("a",actor).param("p",request.poolId()).param("s",request.searchId()).param("f",fields).param("purpose",request.purpose().name()).param("n",rows.size()).param("now",now).param("expires",expires).update();
            for(int n=0;n<rows.size();n++){var c=rows.get(n);db.sql("INSERT INTO csv_export_member(export_id,candidate_id,candidate_version,ordinal) VALUES (:id,:c,:v,:n)").param("id",id).param("c",c.id()).param("v",c.version()).param("n",n).update();}
            audit.csv(actor,"CSV_EXPORT_CREATED",id,request.poolId(),request.purpose().name(),fields,rows.size());
            return new Receipt(id,"/api/v1/csv-exports/"+id+"/content",expires,request.fields(),rows.size());
        });
    }
    public Download download(UUID actor,UUID id){return tx.execute(t->{
        actor(actor);mutex();
        var ticket=db.sql("SELECT id,actor_id,pool_id,search_id,fields,purpose,row_count,expires_at,consumed_at FROM csv_export_ticket WHERE id=:id AND actor_id=:a FOR UPDATE")
            .param("id",id).param("a",actor).query(Ticket.class).optional().orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"CSV_EXPORT_NOT_FOUND"));
        if(ticket.consumedAt()!=null||!ticket.expiresAt().isAfter(now()))fail(HttpStatus.GONE,"CSV_EXPORT_EXPIRED");
        var members=db.sql("SELECT candidate_id,candidate_version FROM csv_export_member WHERE export_id=:id ORDER BY ordinal").param("id",id).query(Member.class).list();
        if(members.size()!=ticket.rowCount())fail(HttpStatus.GONE,"CSV_SELECTION_REMOVED");
        var fields=List.of(ticket.fields().split(","));var rows=selection(actor,ticket.poolId(),members.stream().map(Member::candidateId).toList(),fields);
        for(int n=0;n<rows.size();n++)if(rows.get(n).version()!=members.get(n).candidateVersion())fail(HttpStatus.CONFLICT,"CSV_SELECTION_CHANGED");
        var meta=ticket.searchId()==null?null:scores.metadata(actor,ticket.searchId());
        OffsetDateTime deadline=ticket.expiresAt();for(var c:rows)deadline=earlier(deadline,c.expiresAt());if(meta!=null)deadline=earlier(deadline,meta.expiresAt());
        var out=new StringBuilder("\ufeff");var header=new ArrayList<String>();for(String f:fields)header.addAll(f.equals("score")?SCORE_COLUMNS:List.of(f));line(out,header);
        for(var c:rows){var values=new ArrayList<String>();for(String f:fields)switch(f){
            case "candidateId" -> values.add(c.id().toString());case "displayName" -> values.add(c.profile().displayName());case "professionalTitle" -> values.add(c.profile().professionalTitle());
            case "availability" -> values.add(write(c.profile().availability()==null?null:Map.of("status",c.profile().availability().status(),"availableFrom",Objects.toString(c.profile().availability().availableFrom(),""),"declaredOn",Objects.toString(c.profile().availability().declaredOn(),""))));
            case "email" -> values.add(c.contact().email());case "phone" -> values.add(c.contact().phone());
            case "score" -> {var item=scores.replay(actor,ticket.searchId(),c.id());values.addAll(List.of(Double.toString(item.score().documented()),Double.toString(item.score().upper()),Boolean.toString(item.score().obligationsToVerify()),meta.createdAt().toString(),meta.trace().referenceDate().toString(),write(meta.trace()),meta.scoringRule(),write(meta.policy()==null?com.skillhunters.scoring.EvidenceScore.defaults():meta.policy()),Long.toString(item.hit().candidateVersion()),item.hit().publicationId().toString()));}
            default -> throw new IllegalStateException("CSV_FIELD_UNAVAILABLE");
        }line(out,values);}
        if(!deadline.isAfter(now()))fail(HttpStatus.GONE,"CSV_EXPORT_EXPIRED");
        byte[] bytes=out.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>2*1024*1024)fail(HttpStatus.PAYLOAD_TOO_LARGE,"CSV_EXPORT_TOO_LARGE");
        db.sql("UPDATE csv_export_ticket SET consumed_at=:now WHERE id=:id").param("now",now()).param("id",id).update();
        audit.csv(actor,"CSV_EXPORT_DOWNLOADED",id,ticket.poolId(),ticket.purpose(),ticket.fields(),rows.size());
        return new Download("selection-"+id+".csv",bytes);
    });}
    private static final List<String> SCORE_COLUMNS=List.of("score_documente_sur_100","borne_haute_sur_100","obligations_a_verifier","date_calcul","date_reference","criteres_et_contexte","regle_score","poids","version_fiche_au_calcul","publication_au_calcul");
    private String write(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("CSV_SERIALIZATION");}}
    /** Quote all cells; neutralize formulas even behind Unicode whitespace/format controls. */
    public static String cell(String value){
        String raw=value==null?"":value;var clean=new StringBuilder();raw.codePoints().forEach(c->clean.appendCodePoint(Character.isISOControl(c)?' ':c));raw=clean.toString();
        String probe=Normalizer.normalize(raw,Normalizer.Form.NFKC);int first=probe.codePoints().filter(c->!Character.isWhitespace(c)&&!Character.isSpaceChar(c)&&Character.getType(c)!=Character.FORMAT).findFirst().orElse(-1);
        if(first=='='||first=='+'||first=='-'||first=='@')raw="'"+raw;
        return "\""+raw.replace("\"","\"\"")+"\"";
    }
    private static void line(StringBuilder out,List<String> values){out.append(values.stream().map(CsvExports::cell).collect(java.util.stream.Collectors.joining(","))).append("\r\n");}
    private static OffsetDateTime earlier(OffsetDateTime a,OffsetDateTime b){return a.isBefore(b)?a:b;}
    private static OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    private static void fail(HttpStatus status,String code){throw new Rejected(status,code);}
    @Scheduled(initialDelayString="${app.csv.cleanup-ms:60000}",fixedDelayString="${app.csv.cleanup-ms:60000}")
    public void cleanup(){tx.executeWithoutResult(t->{mutex();db.sql("DELETE FROM csv_export_ticket WHERE expires_at<=:now OR consumed_at IS NOT NULL").param("now",now()).update();});}
}
