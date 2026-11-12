package com.skillhunters.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.shared.ApiProblem.Rejected;
import com.skillhunters.talent.CandidateService;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CandidateGovernance {
    public record State(UUID candidateId,long version,UUID policyId,String source,String purpose,String basisCode,String justification,
                        String noticeVersion,String noticeEvidence,OffsetDateTime notifiedAt,boolean opposed) {}
    public record Edit(UUID policyId,String source,String justification,String noticeVersion,String noticeEvidence,OffsetDateTime notifiedAt) {}
    public record Link(String url,OffsetDateTime expiresAt) {}
    public record Eligibility(boolean allowed,String reason) {}
    public record History(List<State> items,Long nextCursor) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final CandidateService candidates;private final AuditTrail audit;
    private final ObjectMapper json;private final String origin;private final SecureRandom random=new SecureRandom();
    public CandidateGovernance(JdbcClient db,TransactionTemplate tx,CandidateService candidates,AuditTrail audit,ObjectMapper json,@Value("${app.origin}") String origin) {
        this.db=db;this.tx=tx;this.candidates=candidates;this.audit=audit;this.json=json;this.origin=origin;
    }
    private void lock(UUID candidate) {
        db.sql("SELECT id FROM candidate_profile WHERE id=:id FOR UPDATE").param("id",candidate).query(UUID.class).optional()
            .orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"CANDIDATE_NOT_FOUND"));
    }
    private void compliance(UUID actor,UUID candidate) {
        candidates.get(actor,candidate); // Requires membership, enabled account and unexpired source, including for ADMIN/COMPLIANCE.
        if(db.sql("SELECT COUNT(*) FROM global_role WHERE user_id=:actor AND role='COMPLIANCE'").param("actor",actor).query(Integer.class).single()==0)
            throw new Rejected(HttpStatus.FORBIDDEN,"COMPLIANCE_REQUIRED");
        lock(candidate);
    }
    private void contact(UUID actor,UUID candidate) {
        if(!candidates.get(actor,candidate).contactsVisible())throw new Rejected(HttpStatus.FORBIDDEN,"CONTACT_ACCESS_REQUIRED");
        lock(candidate);
    }
    private State state(UUID candidate) {
        return db.sql("SELECT * FROM candidate_governance WHERE candidate_id=:id").param("id",candidate).query((r,n)->new State(candidate,r.getLong("version"),r.getObject("policy_id",UUID.class),r.getString("source"),r.getString("purpose"),r.getString("basis_code"),r.getString("justification"),r.getString("notice_version"),r.getString("notice_evidence"),r.getObject("notified_at",OffsetDateTime.class),r.getBoolean("opposed"))).optional()
            .orElseGet(()->db.sql("SELECT d.* FROM candidate_profile c JOIN document_import d ON d.id=c.import_id WHERE c.id=:id").param("id",candidate)
                .query((r,n)->new State(candidate,0,r.getObject("policy_id",UUID.class),r.getString("source"),r.getString("purpose"),r.getString("basis_code"),null,r.getString("notice_version"),null,null,false)).single());
    }
    public State get(UUID actor,UUID candidate){return tx.execute(t->{compliance(actor,candidate);audit.candidate(actor,candidate,"GOVERNANCE_READ");return state(candidate);});}
    public State save(UUID actor,UUID candidate,String etag,Edit edit){return tx.execute(t->{
        compliance(actor,candidate);var old=state(candidate);version(etag,old.version());
        if(edit==null||edit.policyId()==null)invalid();
        var policy=db.sql("SELECT purpose,basis_code FROM import_policy WHERE id=:id AND enabled=TRUE").param("id",edit.policyId()).query((r,n)->List.of(r.getString(1),r.getString(2))).optional()
            .orElseThrow(()->new Rejected(HttpStatus.BAD_REQUEST,"GOVERNANCE_POLICY_REQUIRED"));
        String source=text(edit.source(),200),justification=text(edit.justification(),1000),notice=text(edit.noticeVersion(),80),evidence=text(edit.noticeEvidence(),500);
        if(edit.notifiedAt()==null||edit.notifiedAt().isAfter(OffsetDateTime.now(ZoneOffset.UTC)))invalid();
        var next=new State(candidate,old.version()+1,edit.policyId(),source,policy.get(0),policy.get(1),justification,notice,evidence,edit.notifiedAt(),old.opposed());
        persist(actor,next,"GOVERNANCE_CHANGED");return next;
    });}
    public State oppose(UUID actor,UUID candidate,String etag){return tx.execute(t->{compliance(actor,candidate);var old=state(candidate);version(etag,old.version());return opposeLocked(actor,old);});}
    private State opposeLocked(UUID actor,State old){
        if(old.opposed())return old;
        var next=new State(old.candidateId(),old.version()+1,old.policyId(),old.source(),old.purpose(),old.basisCode(),old.justification(),old.noticeVersion(),old.noticeEvidence(),old.notifiedAt(),true);
        persist(actor,next,"CONTACT_OPPOSED");return next;
    }
    private void persist(UUID actor,State s,String action){
        if(db.sql("SELECT COUNT(*) FROM candidate_governance WHERE candidate_id=:id").param("id",s.candidateId()).query(Integer.class).single()==0)
            db.sql("INSERT INTO candidate_governance(candidate_id) VALUES (:id)").param("id",s.candidateId()).update();
        db.sql("UPDATE candidate_governance SET version=:v,policy_id=:p,source=:s,purpose=:purpose,basis_code=:b,justification=:j,notice_version=:n,notice_evidence=:e,notified_at=:at,opposed=:o WHERE candidate_id=:id")
            .param("id",s.candidateId()).param("v",s.version()).param("p",s.policyId()).param("s",s.source()).param("purpose",s.purpose()).param("b",s.basisCode()).param("j",s.justification()).param("n",s.noticeVersion()).param("e",s.noticeEvidence()).param("at",s.notifiedAt()).param("o",s.opposed()).update();
        try{db.sql("INSERT INTO candidate_governance_revision(candidate_id,version,actor_id,payload_json) VALUES (:id,:v,:a,:j)").param("id",s.candidateId()).param("v",s.version()).param("a",actor).param("j",json.writeValueAsString(s)).update();}
        catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new IllegalStateException("GOVERNANCE_SERIALIZATION",e);}
        audit.candidate(actor,s.candidateId(),action);
    }
    public History history(UUID actor,UUID candidate,Long before){return tx.execute(t->{compliance(actor,candidate);if(before!=null&&before<1)invalid();
        var q=db.sql("SELECT payload_json FROM candidate_governance_revision WHERE candidate_id=:id"+(before==null?"":" AND version<:before")+" ORDER BY version DESC LIMIT 26").param("id",candidate);if(before!=null)q=q.param("before",before);
        var values=q.query(String.class).list().stream().map(s->{try{return json.readValue(s,State.class);}catch(Exception e){throw new IllegalStateException("GOVERNANCE_STORAGE",e);}}).toList();
        audit.candidate(actor,candidate,"GOVERNANCE_READ");var items=values.stream().limit(25).toList();return new History(items,values.size()>25?items.getLast().version():null);
    });}
    // Called in the enclosing contact transaction. The candidate lock serializes opposition with future send approval/dispatch.
    public void ensureNotOpposed(UUID candidate){lock(candidate);if(state(candidate).opposed())throw new Rejected(HttpStatus.CONFLICT,"CONTACT_OPPOSED");}
    private Eligibility eligibilityLocked(UUID candidate){
        var s=state(candidate);if(s.opposed())return new Eligibility(false,"CONTACT_OPPOSED");
        if(s.justification()==null||s.noticeEvidence()==null||s.notifiedAt()==null)return new Eligibility(false,"GOVERNANCE_INCOMPLETE");
        if(db.sql("SELECT COUNT(*) FROM import_policy WHERE id=:id AND enabled=TRUE").param("id",s.policyId()).query(Integer.class).single()==0)return new Eligibility(false,"GOVERNANCE_POLICY_DISABLED");
        return new Eligibility(true,"GOVERNANCE_READY");
    }
    public Eligibility eligibility(UUID actor,UUID candidate){return tx.execute(t->{contact(actor,candidate);return eligibilityLocked(candidate);});}
    @org.springframework.transaction.annotation.Transactional(propagation=org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void requireContactAllowed(UUID actor,UUID candidate){contact(actor,candidate);var e=eligibilityLocked(candidate);if(!e.allowed())throw new Rejected(HttpStatus.CONFLICT,e.reason());}
    public Link issue(UUID actor,UUID candidate){return tx.execute(t->{requireContactAllowed(actor,candidate);
        db.sql("DELETE FROM contact_opposition_token WHERE candidate_id=:id AND expires_at<=:now").param("id",candidate).param("now",OffsetDateTime.now(ZoneOffset.UTC)).update();
        if(db.sql("SELECT COUNT(*) FROM contact_opposition_token WHERE candidate_id=:id").param("id",candidate).query(Integer.class).single()>=100)throw new Rejected(HttpStatus.TOO_MANY_REQUESTS,"OPPOSITION_LINK_QUOTA");
        byte[] bytes=new byte[32];random.nextBytes(bytes);String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        var expiry=OffsetDateTime.now(ZoneOffset.UTC).plusDays(7);
        db.sql("INSERT INTO contact_opposition_token(token_hash,candidate_id,expires_at) VALUES (:hash,:id,:expiry)").param("hash",hash(token)).param("id",candidate).param("expiry",expiry).update();
        audit.candidate(actor,candidate,"OPPOSITION_LINK_CREATED");return new Link(origin+"/#opposition="+token,expiry);
    });}
    public boolean validIssuedLink(UUID candidate,String link){
        String prefix=origin+"/#opposition=";
        if(!link.startsWith(prefix))return false;
        String token=link.substring(prefix.length());if(!token.matches("[A-Za-z0-9_-]{43}"))return false;
        return db.sql("SELECT COUNT(*) FROM contact_opposition_token WHERE candidate_id=:id AND token_hash=:hash AND expires_at>:now")
            .param("id",candidate).param("hash",hash(token)).param("now",OffsetDateTime.now(ZoneOffset.UTC)).query(Integer.class).single()>0;
    }
    public void redeem(String token){
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))gone();
        tx.executeWithoutResult(t->{String hash=hash(token);
            var id=db.sql("SELECT candidate_id FROM contact_opposition_token WHERE token_hash=:hash AND expires_at>:now").param("hash",hash).param("now",OffsetDateTime.now(ZoneOffset.UTC)).query(UUID.class).optional().orElseThrow(()->new Rejected(HttpStatus.GONE,"OPPOSITION_LINK_UNAVAILABLE"));
            if(db.sql("SELECT id FROM candidate_profile WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional().isEmpty())gone();
            if(db.sql("SELECT COUNT(*) FROM contact_opposition_token WHERE token_hash=:hash AND expires_at>:now").param("hash",hash).param("now",OffsetDateTime.now(ZoneOffset.UTC)).query(Integer.class).single()==0)gone();
            opposeLocked(null,state(id));
            db.sql("UPDATE contact_opposition_token SET used=TRUE WHERE token_hash=:hash").param("hash",hash).update();
        });
    }
    private static String hash(String token){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private static void version(String etag,long version){if(etag==null)throw new Rejected(HttpStatus.PRECONDITION_REQUIRED,"VERSION_REQUIRED");if(!etag.equals("\""+version+"\""))throw new Rejected(HttpStatus.PRECONDITION_FAILED,"GOVERNANCE_CHANGED");}
    private static String text(String value,int max){if(value==null||value.isBlank()||value.length()>max||value.codePoints().anyMatch(Character::isISOControl))invalid();return value.strip();}
    private static void invalid(){throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_GOVERNANCE");}
    private static void gone(){throw new Rejected(HttpStatus.GONE,"OPPOSITION_LINK_UNAVAILABLE");}
}
