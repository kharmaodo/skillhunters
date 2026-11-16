package com.skillhunters.talent;

import com.skillhunters.governance.AuditTrail;
import com.skillhunters.communication.ContactJournal;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CandidateLineage {
    public record Member(UUID candidateId,UUID sourceId,UUID sourceRevisionId,long candidateVersion,String displayName,boolean active,OffsetDateTime expiresAt) {}
    public record State(UUID identityId,long version,boolean emailVerified,List<Member> members) {}
    public record Suggestion(UUID candidateId,String displayName,UUID sourceId,long candidateVersion,UUID identityId,long identityVersion) {}
    public record Suggestions(List<Suggestion> items,String nextCursor) {}
    public record Merge(UUID otherCandidateId,long candidateVersion,long otherCandidateVersion,long identityVersion,long otherIdentityVersion,UUID activeCandidateId,boolean confirmed,String reason) {}
    public record Activation(long identityVersion,UUID activeCandidateId,boolean confirmed,String reason) {}
    public record Event(long sequence,UUID decisionId,UUID actorId,String action,UUID activeCandidateId,UUID sourceRevisionId,UUID publicationId,long candidateVersion,String reason,OffsetDateTime occurredAt) {}
    public record History(List<Event> items,Long nextCursor) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final CandidateService candidates;private final CandidateIdentity identities;private final AuditTrail audit;private final ContactJournal journal;
    public CandidateLineage(JdbcClient db,TransactionTemplate tx,CandidateService candidates,CandidateIdentity identities,AuditTrail audit,ContactJournal journal){this.db=db;this.tx=tx;this.candidates=candidates;this.identities=identities;this.audit=audit;this.journal=journal;}
    private CandidateService.Candidate access(UUID actor,UUID id){var c=candidates.get(actor,id);if(!c.contactsVisible())throw new Rejected(HttpStatus.FORBIDDEN,"CONTACT_ACCESS_REQUIRED");return c;}
    private long version(UUID identity){return db.sql("SELECT version FROM candidate_identity WHERE id=:id").param("id",identity).query(Long.class).single();}
    private String key(String email){if(email==null||email.isBlank())throw new Rejected(HttpStatus.CONFLICT,"CONTACT_EMAIL_REQUIRED");try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(email.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private boolean verified(CandidateService.Candidate c){return c.contact().email()!=null&&db.sql("SELECT COUNT(*) FROM candidate_contact_verification WHERE candidate_id=:id AND email_key=:key").param("id",c.id()).param("key",key(c.contact().email())).query(Integer.class).single()==1;}
    public State verify(UUID actor,UUID candidate,String etag,boolean confirmed){return tx.execute(t->{
        var c=access(actor,candidate);identities.requireActive(candidate);
        db.sql("SELECT id FROM candidate_profile WHERE id=:id FOR UPDATE").param("id",candidate).query(UUID.class).single();c=access(actor,candidate);
        if(etag==null)throw new Rejected(HttpStatus.PRECONDITION_REQUIRED,"VERSION_REQUIRED");if(!etag.equals("\""+c.version()+"\""))changed();
        if(!confirmed)invalid();String key=key(c.contact().email());
        db.sql("DELETE FROM candidate_contact_verification WHERE candidate_id=:id").param("id",candidate).update();
        db.sql("INSERT INTO candidate_contact_verification(candidate_id,email_key,actor_id) VALUES (:id,:key,:actor)").param("id",candidate).param("key",key).param("actor",actor).update();
        audit.candidate(actor,candidate,"CONTACT_IDENTITY_VERIFIED");return state(actor,candidate);
    });}
    public State state(UUID actor,UUID candidate){return tx.execute(t->{var c=access(actor,candidate);identities.lock(candidate);var identity=identities.id(candidate);
        var members=db.sql("SELECT c.id FROM candidate_identity_member m JOIN candidate_profile c ON c.id=m.candidate_id JOIN document_import d ON d.id=c.import_id WHERE m.identity_id=:id AND d.expires_at>:now ORDER BY c.id LIMIT 100")
            .param("id",identity).param("now",now()).query(UUID.class).list().stream().map(id->{var item=access(actor,id);return new Member(id,item.importId(),item.revisionId(),item.version(),item.profile().displayName(),item.activeVersion(),item.expiresAt());}).toList();
        audit.candidate(actor,candidate,"CANDIDATE_LINEAGE_READ");return new State(identity,version(identity),verified(c),members);
    });}
    public Suggestions suggestions(UUID actor,UUID candidate,String cursor){return tx.execute(t->{
        var c=access(actor,candidate);identities.lock(candidate);if(!verified(c))throw new Rejected(HttpStatus.CONFLICT,"VERIFIED_EMAIL_REQUIRED");
        UUID after=null;if(cursor!=null)try{if(cursor.length()>150)invalid();var p=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split(":",-1);if(p.length!=3||!p[0].equals(candidate.toString())||!p[1].equals(key(c.contact().email())))invalid();after=UUID.fromString(p[2]);}catch(IllegalArgumentException e){invalid();}
        var q=db.sql("SELECT c.id FROM candidate_contact_verification v JOIN candidate_contact cc ON cc.candidate_id=v.candidate_id JOIN candidate_profile c ON c.id=v.candidate_id JOIN document_import d ON d.id=c.import_id JOIN candidate_identity_member m ON m.candidate_id=c.id WHERE v.email_key=:key AND LOWER(TRIM(cc.email))=:email AND d.pool_id=:pool AND d.expires_at>:now AND c.active_version=TRUE AND m.identity_id<>:identity"+(after==null?"":" AND c.id>:after")+" ORDER BY c.id LIMIT 21")
            .param("key",key(c.contact().email())).param("email",c.contact().email().strip().toLowerCase(Locale.ROOT)).param("pool",c.poolId()).param("now",now()).param("identity",identities.id(candidate));
        if(after!=null)q=q.param("after",after);
        var ids=q.query(UUID.class).list();var items=ids.stream().limit(20).map(id->{var other=access(actor,id);UUID group=identities.id(id);return new Suggestion(id,other.profile().displayName(),other.importId(),other.version(),group,version(group));}).toList();
        String next=ids.size()>20?Base64.getUrlEncoder().withoutPadding().encodeToString((candidate+":"+key(c.contact().email())+":"+items.getLast().candidateId()).getBytes(StandardCharsets.UTF_8)):null;
        audit.candidate(actor,candidate,"CANDIDATE_MATCH_READ");return new Suggestions(items,next);
    });}
    public State merge(UUID actor,UUID candidate,Merge request){return tx.execute(t->{
        if(request==null||request.otherCandidateId()==null||request.activeCandidateId()==null||candidate.equals(request.otherCandidateId()))invalid();decision(request.confirmed(),request.reason());
        var first=access(actor,candidate);var other=access(actor,request.otherCandidateId());if(!first.poolId().equals(other.poolId()))missing();
        UUID left=identities.id(candidate),right=identities.id(other.id());identities.lockIds(List.of(left,right));
        if(!left.equals(identities.id(candidate))||!right.equals(identities.id(other.id()))||left.equals(right))changed();
        if(version(left)!=request.identityVersion()||version(right)!=request.otherIdentityVersion())changed();
        var ids=new ArrayList<>(members(left));ids.addAll(members(right));lockMembers(ids);
        first=access(actor,candidate);other=access(actor,other.id());
        if(first.version()!=request.candidateVersion()||other.version()!=request.otherCandidateVersion())changed();
        if(!verified(first)||!verified(other)||!key(first.contact().email()).equals(key(other.contact().email())))throw new Rejected(HttpStatus.CONFLICT,"VERIFIED_CONTACT_CHANGED");
        var active=access(actor,request.activeCandidateId());if(!ids.contains(active.id()))invalid();
        if(ids.size()>100||version(left)>=100||version(right)>=100)throw new Rejected(HttpStatus.TOO_MANY_REQUESTS,"LINEAGE_QUOTA");
        pending(ids);
        boolean opposed=identities.opposed(candidate)||identities.opposed(other.id());
        db.sql("UPDATE candidate_identity_member SET identity_id=:target WHERE identity_id=:source").param("target",left).param("source",right).update();
        db.sql("UPDATE candidate_identity SET opposed=:opposed,version=version+1 WHERE id=:id").param("opposed",opposed).param("id",left).update();
        // Empty identity retained only as a non-personal transaction marker; members keep source-level retention.
        db.sql("UPDATE candidate_identity SET version=version+1 WHERE id=:id").param("id",right).update();
        apply(actor,ids,active.id(),"MERGED",request.reason());
        return state(actor,candidate);
    });}
    public State activate(UUID actor,UUID candidate,Activation request){return tx.execute(t->{
        if(request==null||request.activeCandidateId()==null)invalid();decision(request.confirmed(),request.reason());access(actor,candidate);identities.lock(candidate);UUID identity=identities.id(candidate);
        if(version(identity)!=request.identityVersion())changed();if(version(identity)>=100)throw new Rejected(HttpStatus.TOO_MANY_REQUESTS,"LINEAGE_QUOTA");
        var ids=members(identity);lockMembers(ids);if(!ids.contains(request.activeCandidateId()))missing();access(actor,request.activeCandidateId());pending(ids);
        db.sql("UPDATE candidate_identity SET version=version+1 WHERE id=:id").param("id",identity).update();apply(actor,ids,request.activeCandidateId(),"ACTIVATED",request.reason());return state(actor,candidate);
    });}
    private List<UUID> members(UUID identity){return db.sql("SELECT candidate_id FROM candidate_identity_member WHERE identity_id=:id ORDER BY candidate_id").param("id",identity).query(UUID.class).list();}
    private void lockMembers(List<UUID> ids){for(UUID id:ids.stream().sorted(Comparator.comparing(UUID::toString)).toList())db.sql("SELECT id FROM candidate_profile WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).single();}
    private void pending(List<UUID> ids){for(UUID id:ids)if(db.sql("SELECT COUNT(*) FROM contact_delivery WHERE candidate_id=:id AND status='SENDING'").param("id",id).query(Integer.class).single()>0)throw new Rejected(HttpStatus.CONFLICT,"CONTACT_ATTEMPT_PENDING");}
    private void apply(UUID actor,List<UUID> ids,UUID active,String action,String reason){
        UUID decision=UUID.randomUUID();
        for(UUID id:ids){
            db.sql("UPDATE candidate_profile SET active_version=:active WHERE id=:id").param("active",id.equals(active)).param("id",id).update();
            var deliveries=db.sql("SELECT id FROM contact_delivery WHERE candidate_id=:id AND status='APPROVED'").param("id",id).query(UUID.class).list();
            for(UUID delivery:deliveries){db.sql("UPDATE contact_delivery SET status='INVALIDATED',result_code='LINEAGE_CHANGED',completed_at=:now WHERE id=:id").param("now",now()).param("id",delivery).update();journal.event(actor,delivery,"INVALIDATED","LINEAGE_CHANGED");}
            db.sql("INSERT INTO candidate_lineage_event(candidate_id,decision_id,actor_id,action,active_candidate_id,source_revision_id,publication_id,candidate_version,reason) SELECT c.id,:decision,:actor,:action,:active,c.revision_id,(SELECT r.id FROM candidate_revision r WHERE r.candidate_id=c.id AND r.status='PUBLISHED' ORDER BY r.version DESC LIMIT 1),c.version,:reason FROM candidate_profile c WHERE c.id=:id")
                .param("decision",decision).param("actor",actor).param("action",action).param("active",active).param("reason",reason.strip()).param("id",id).update();
            audit.candidate(actor,id,"CANDIDATE_"+action);
        }
    }
    public History history(UUID actor,UUID candidate,Long before){return tx.execute(t->{access(actor,candidate);if(before!=null&&before<1)invalid();
        var q=db.sql("SELECT * FROM candidate_lineage_event WHERE candidate_id=:id"+(before==null?"":" AND sequence<:before")+" ORDER BY sequence DESC LIMIT 26").param("id",candidate);if(before!=null)q=q.param("before",before);
        var all=q.query((r,n)->new Event(r.getLong("sequence"),r.getObject("decision_id",UUID.class),r.getObject("actor_id",UUID.class),r.getString("action"),r.getObject("active_candidate_id",UUID.class),r.getObject("source_revision_id",UUID.class),r.getObject("publication_id",UUID.class),r.getLong("candidate_version"),r.getString("reason"),r.getObject("occurred_at",OffsetDateTime.class))).list();
        audit.candidate(actor,candidate,"CANDIDATE_LINEAGE_READ");var items=all.stream().limit(25).toList();return new History(items,all.size()>25?items.getLast().sequence():null);
    });}
    @org.springframework.scheduling.annotation.Scheduled(initialDelayString="${app.lineage.cleanup-ms:60000}",fixedDelayString="${app.lineage.cleanup-ms:60000}")
    public void cleanup(){tx.executeWithoutResult(t->{for(String table:List.of("candidate_contact_verification","candidate_lineage_event"))db.sql("DELETE FROM "+table+" WHERE candidate_id IN (SELECT c.id FROM candidate_profile c JOIN document_import d ON d.id=c.import_id WHERE d.expires_at<=:now)").param("now",now()).update();});}
    private static OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    private static void decision(boolean confirmed,String reason){if(!confirmed||reason==null||reason.isBlank()||reason.length()>500||reason.codePoints().anyMatch(Character::isISOControl))invalid();}
    private static void changed(){throw new Rejected(HttpStatus.PRECONDITION_FAILED,"LINEAGE_CHANGED");}
    private static void missing(){throw new Rejected(HttpStatus.NOT_FOUND,"CANDIDATE_NOT_FOUND");}
    private static void invalid(){throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_LINEAGE_DECISION");}
}
