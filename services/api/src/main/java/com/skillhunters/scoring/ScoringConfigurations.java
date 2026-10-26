package com.skillhunters.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.shared.ApiProblem.Rejected;
import com.skillhunters.talent.CandidateService;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ScoringConfigurations {
    public record Weights(
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictWeight.class) Integer required,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictWeight.class) Integer desired,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictWeight.class) Integer experience,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictWeight.class) Integer recency) {}
    public static class StrictWeight extends com.fasterxml.jackson.databind.JsonDeserializer<Integer> {
        @Override public Integer deserialize(com.fasterxml.jackson.core.JsonParser p,com.fasterxml.jackson.databind.DeserializationContext c) throws java.io.IOException {
            if(!p.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT)) return (Integer)c.handleUnexpectedToken(Integer.class,p);
            return p.getIntValue();
        }
    }
    public record Edit(String name,Weights weights,String reason) {}
    public record Revision(UUID poolId,long version,String name,Weights weights,Weights before,
            Map<String,Double> normalized,UUID validatorId,OffsetDateTime validatedAt,String reason) {}
    public record State(Revision latest,boolean canConfigure) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final ObjectMapper json;
    public ScoringConfigurations(JdbcClient db,TransactionTemplate tx,ObjectMapper json) {this.db=db;this.tx=tx;this.json=json;}
    public State state(UUID actor,UUID pool) {return tx.execute(t->{access(actor,pool);return new State(latest(pool),canConfigure(actor,pool));});}
    public Revision revision(UUID actor,UUID pool,Long version) {
        return tx.execute(t->{access(actor,pool);return get(pool,version==null?0:version);});
    }
    public CandidateService.Page<Revision> history(UUID actor,UUID pool,int cursor) {
        if(cursor<0||cursor>100000) invalid();
        return tx.execute(t->{access(actor,pool);
            var rows=db.sql("SELECT revision_json FROM scoring_configuration WHERE pool_id=:pool ORDER BY version DESC LIMIT 26 OFFSET :offset")
                .param("pool",pool).param("offset",cursor).query(String.class).list();
            return new CandidateService.Page<>(rows.stream().limit(25).map(this::read).toList(),rows.size()>25?""+(cursor+25):null);
        });
    }
    public Revision save(UUID actor,UUID pool,String etag,Edit edit) {
        return tx.execute(t->{access(actor,pool);
            if(!canConfigure(actor,pool)) throw new Rejected(HttpStatus.FORBIDDEN,"SCORING_CONFIGURE_REQUIRED");
            // Same actor lock as role revocation, then one pool lock for version allocation.
            db.sql("SELECT id FROM talent_pool WHERE id=:pool FOR UPDATE").param("pool",pool).query(UUID.class).single();
            var before=latest(pool);
            if(etag==null) throw new Rejected(HttpStatus.PRECONDITION_REQUIRED,"VERSION_REQUIRED");
            if(!etag.equals("\""+before.version()+"\"")) throw new Rejected(HttpStatus.PRECONDITION_FAILED,"SCORING_CONFIGURATION_CHANGED");
            if(edit==null) invalid();text(edit.name(),100);text(edit.reason(),500);validate(edit.weights());
            var revision=new Revision(pool,before.version()+1,edit.name().strip(),edit.weights(),before.weights(),normalize(edit.weights()),actor,OffsetDateTime.now(ZoneOffset.UTC),edit.reason().strip());
            db.sql("INSERT INTO scoring_configuration(pool_id,version,revision_json) VALUES (:pool,:version,:json)")
                .param("pool",pool).param("version",revision.version()).param("json",write(revision)).update();
            return revision;
        });
    }
    public EvidenceScore.Policy policy(UUID actor,UUID pool,Long version) {
        var r=revision(actor,pool,version);var w=r.weights();
        return new EvidenceScore.Policy(r.version()==0?EvidenceScore.WEIGHTS:"pool-"+pool+"/scoring-"+r.version(),w.required(),w.desired(),w.experience(),w.recency());
    }
    private Revision latest(UUID pool) {return db.sql("SELECT revision_json FROM scoring_configuration WHERE pool_id=:pool ORDER BY version DESC LIMIT 1").param("pool",pool).query(String.class).optional().map(this::read).orElseGet(()->initial(pool));}
    private Revision get(UUID pool,long version) {
        if(version<0) invalid();if(version==0)return initial(pool);
        return db.sql("SELECT revision_json FROM scoring_configuration WHERE pool_id=:pool AND version=:version").param("pool",pool).param("version",version).query(String.class).optional().map(this::read)
            .orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"SCORING_CONFIGURATION_NOT_FOUND"));
    }
    private boolean access(UUID actor,UUID pool) {
        if(!db.sql("SELECT enabled FROM app_user WHERE id=:actor FOR UPDATE").param("actor",actor).query(Boolean.class).optional().orElse(false)) throw new Rejected(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED");
        if(db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:actor AND pool_id=:pool").param("actor",actor).param("pool",pool).query(Integer.class).single()==0) throw new Rejected(HttpStatus.NOT_FOUND,"POOL_NOT_FOUND");
        return true;
    }
    private boolean canConfigure(UUID actor,UUID pool) {return db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:actor AND pool_id=:pool AND role='RECRUITMENT_LEAD'").param("actor",actor).param("pool",pool).query(Integer.class).single()>0;}
    private static Revision initial(UUID pool) {var w=new Weights(35,15,20,10);return new Revision(pool,0,"Poids initiaux SH-18",w,null,normalize(w),null,null,"Configuration initiale versionnée");}
    private static void validate(Weights w) {
        if(w==null||w.required()==null||w.desired()==null||w.experience()==null||w.recency()==null) invalid();
        var values=List.of(w.required(),w.desired(),w.experience(),w.recency());
        if(values.stream().anyMatch(v->v<0||v>10000)||values.stream().mapToInt(Integer::intValue).sum()==0) invalid();
    }
    private static Map<String,Double> normalize(Weights w) {
        int total=w.required()+w.desired()+w.experience()+w.recency();
        var result=new LinkedHashMap<String,Double>();
        result.put("REQUIRED",w.required()/(double)total);result.put("DESIRED",w.desired()/(double)total);result.put("EXPERIENCE",w.experience()/(double)total);result.put("RECENCY",w.recency()/(double)total);return Collections.unmodifiableMap(result);
    }
    private static void text(String s,int max) {if(s==null||s.isBlank()||s.length()>max||s.codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT))invalid();}
    private static void invalid() {throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_SCORING_CONFIGURATION");}
    private Revision read(String s) {try{return json.readValue(s,Revision.class);}catch(Exception e){throw new IllegalStateException("SCORING_CONFIGURATION_STORAGE");}}
    private String write(Revision r) {try{return json.writeValueAsString(r);}catch(Exception e){throw new IllegalStateException("SCORING_CONFIGURATION_SERIALIZATION");}}
}
