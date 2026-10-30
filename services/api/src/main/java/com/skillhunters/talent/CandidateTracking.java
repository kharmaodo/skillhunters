package com.skillhunters.talent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.time.*;
import java.text.Normalizer;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CandidateTracking {
    public enum Status { NEW, TO_REVIEW, SHORTLISTED, CONTACTED, INTERVIEW, CLOSED }
    public record Values(Status status,String note,List<String> tags,List<String> lists) {}
    public record Edit(Status status,String note,List<String> tags,List<String> lists,String reason,boolean professionalOnly) {}
    public record State(UUID candidateId,int version,Values values) {}
    public record Event(int version,UUID actorId,OffsetDateTime occurredAt,String reason,Values before,Values after) {}
    public record Row(UUID candidateId,String displayName,String professionalTitle,State tracking) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final ObjectMapper json;private final CandidateService candidates;
    public CandidateTracking(JdbcClient db,TransactionTemplate tx,ObjectMapper json,CandidateService candidates){this.db=db;this.tx=tx;this.json=json;this.candidates=candidates;}
    public State get(UUID actor,UUID id){return tx.execute(t->{candidates.get(actor,id);return state(actor,id);});}
    private State state(UUID actor,UUID id){return db.sql("SELECT state_json FROM candidate_tracking WHERE actor_id=:a AND candidate_id=:c").param("a",actor).param("c",id).query(String.class).optional().map(s->read(s,State.class)).orElseGet(()->new State(id,0,new Values(Status.NEW,"",List.of(),List.of())));}
    public State save(UUID actor,UUID id,String etag,Edit edit){return tx.execute(t->{
        // Candidate access holds the same actor lock as revocation and personal tracking writes.
        candidates.get(actor,id);var old=state(actor,id);
        if(etag==null)throw new Rejected(HttpStatus.PRECONDITION_REQUIRED,"VERSION_REQUIRED");
        if(!etag.equals("\""+old.version()+"\""))throw new Rejected(HttpStatus.PRECONDITION_FAILED,"TRACKING_CHANGED");
        if(edit==null||edit.status()==null||!edit.professionalOnly())invalid();
        String reason=text(edit.reason(),500,false),note=text(edit.note(),2000,true);
        var values=new Values(edit.status(),note,labels(edit.tags()),labels(edit.lists()));
        if(values.equals(old.values()))throw new Rejected(HttpStatus.CONFLICT,"TRACKING_UNCHANGED");
        if(old.version()>=500||old.version()==0&&db.sql("SELECT COUNT(*) FROM candidate_tracking WHERE actor_id=:a").param("a",actor).query(Integer.class).single()>=1000)throw new Rejected(HttpStatus.TOO_MANY_REQUESTS,"TRACKING_QUOTA");
        var current=new State(id,old.version()+1,values);
        if(old.version()==0) db.sql("INSERT INTO candidate_tracking(actor_id,candidate_id,version,status,state_json) VALUES (:a,:c,:v,:s,:j)").param("a",actor).param("c",id).param("v",current.version()).param("s",values.status().name()).param("j",write(current)).update();
        else db.sql("UPDATE candidate_tracking SET version=:v,status=:s,state_json=:j WHERE actor_id=:a AND candidate_id=:c").param("a",actor).param("c",id).param("v",current.version()).param("s",values.status().name()).param("j",write(current)).update();
        db.sql("DELETE FROM candidate_tracking_label WHERE actor_id=:a AND candidate_id=:c").param("a",actor).param("c",id).update();
        for(var kind:List.of("TAG","LIST"))for(var label:kind.equals("TAG")?values.tags():values.lists())
            db.sql("INSERT INTO candidate_tracking_label(actor_id,candidate_id,kind,label) VALUES (:a,:c,:k,:l)").param("a",actor).param("c",id).param("k",kind).param("l",label).update();
        var event=new Event(current.version(),actor,OffsetDateTime.now(ZoneOffset.UTC),reason,old.values(),values);
        db.sql("INSERT INTO candidate_tracking_event(actor_id,candidate_id,version,event_json) VALUES (:a,:c,:v,:j)").param("a",actor).param("c",id).param("v",event.version()).param("j",write(event)).update();
        return current;
    });}
    public CandidateService.Page<Event> history(UUID actor,UUID id,int cursor){bounds(cursor);return tx.execute(t->{
        candidates.get(actor,id);var rows=db.sql("SELECT event_json FROM candidate_tracking_event WHERE actor_id=:a AND candidate_id=:c ORDER BY version DESC LIMIT 26 OFFSET :o").param("a",actor).param("c",id).param("o",cursor).query(String.class).list();
        return new CandidateService.Page<>(rows.stream().limit(25).map(s->read(s,Event.class)).toList(),rows.size()>25?""+(cursor+25):null);
    });}
    public CandidateService.Page<Row> list(UUID actor,UUID pool,Status status,String list,String tag,int cursor){bounds(cursor);return tx.execute(t->{
        pool(actor,pool);String l=list==null?null:label(list),g=tag==null?null:label(tag);
        String sql="""
            SELECT c.id FROM candidate_profile c JOIN document_import d ON d.id=c.import_id
            LEFT JOIN candidate_tracking t ON t.candidate_id=c.id AND t.actor_id=:a
            WHERE d.pool_id=:p AND d.expires_at>:now
            """;
        if(status!=null)sql+=" AND COALESCE(t.status,'NEW')=:s";
        if(l!=null)sql+=" AND EXISTS (SELECT 1 FROM candidate_tracking_label x WHERE x.actor_id=:a AND x.candidate_id=c.id AND x.kind='LIST' AND x.label=:l)";
        if(g!=null)sql+=" AND EXISTS (SELECT 1 FROM candidate_tracking_label x WHERE x.actor_id=:a AND x.candidate_id=c.id AND x.kind='TAG' AND x.label=:g)";
        var q=db.sql(sql+" ORDER BY c.id LIMIT 26 OFFSET :o").param("a",actor).param("p",pool).param("now",OffsetDateTime.now(ZoneOffset.UTC)).param("o",cursor);
        if(status!=null)q=q.param("s",status.name());if(l!=null)q=q.param("l",l);if(g!=null)q=q.param("g",g);
        var ids=q.query(UUID.class).list();var rows=ids.stream().limit(25).map(id->{var c=candidates.get(actor,id);return new Row(id,c.profile().displayName(),c.profile().professionalTitle(),state(actor,id));}).toList();
        return new CandidateService.Page<>(rows,ids.size()>25?""+(cursor+25):null);
    });}
    private void pool(UUID actor,UUID pool){
        if(!db.sql("SELECT enabled FROM app_user WHERE id=:a FOR UPDATE").param("a",actor).query(Boolean.class).optional().orElse(false))throw new Rejected(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED");
        if(db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:a AND pool_id=:p").param("a",actor).param("p",pool).query(Integer.class).single()==0)throw new Rejected(HttpStatus.NOT_FOUND,"POOL_NOT_FOUND");
    }
    private static List<String> labels(List<String> input){if(input==null||input.size()>10)invalid();var set=new TreeSet<String>();for(String s:input)if(!set.add(label(s)))invalid();return List.copyOf(set);}
    private static String label(String s){String value=text(Normalizer.normalize(text(s,80,false),Normalizer.Form.NFKC).toLowerCase(Locale.ROOT),80,false);if(value.contains(",")||value.contains("\n"))invalid();return value;}
    private static String text(String s,int max,boolean empty){if(s==null||s.length()>max||!empty&&s.isBlank()||s.codePoints().anyMatch(c->c!='\n'&&Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)||s.contains("<")||s.contains(">"))invalid();return s.strip();}
    private static void bounds(int cursor){if(cursor<0||cursor>100000)invalid();}
    private static void invalid(){throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_TRACKING");}
    @Scheduled(initialDelayString="${app.scoring.cleanup-ms:3600000}",fixedDelayString="${app.scoring.cleanup-ms:3600000}")
    public void cleanup(){tx.executeWithoutResult(t->db.sql("DELETE FROM candidate_tracking WHERE candidate_id IN (SELECT c.id FROM candidate_profile c JOIN document_import d ON d.id=c.import_id WHERE d.expires_at<=:now)").param("now",OffsetDateTime.now(ZoneOffset.UTC)).update());}
    private String write(Object v){try{return json.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException("TRACKING_SERIALIZATION");}}
    private <T>T read(String s,Class<T> type){try{return json.readValue(s,type);}catch(Exception e){throw new IllegalStateException("TRACKING_STORAGE");}}
}
