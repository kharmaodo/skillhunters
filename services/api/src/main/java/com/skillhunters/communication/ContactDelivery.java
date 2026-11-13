package com.skillhunters.communication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.governance.AuditTrail;
import com.skillhunters.governance.CandidateGovernance;
import com.skillhunters.shared.ApiProblem.Rejected;
import com.skillhunters.talent.CandidateService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ContactDelivery {
    public record Approval(long draftVersion,String recipient,String subject,String body,boolean confirmed) {}
    public record Receipt(UUID id,long draftVersion,String contentHash,String sender,String recipient,String replyTo,String subject,String body,
                          String messageId,String status,OffsetDateTime approvedAt,OffsetDateTime expiresAt,OffsetDateTime attemptedAt,OffsetDateTime completedAt,String resultCode) {}
    private final ContactJournal journal;
    private final JdbcClient db;private final TransactionTemplate tx;private final ContactDrafts drafts;private final CandidateService candidates;
    private final CandidateGovernance governance;private final ContactTransport transport;private final AuditTrail audit;private final ObjectMapper json;
    public ContactDelivery(JdbcClient db,TransactionTemplate tx,ContactDrafts drafts,CandidateService candidates,CandidateGovernance governance,ContactTransport transport,AuditTrail audit,ObjectMapper json,ContactJournal journal){this.journal=journal;
        this.db=db;this.tx=tx;this.drafts=drafts;this.candidates=candidates;this.governance=governance;this.transport=transport;this.audit=audit;this.json=json;
    }
    private void access(UUID actor,UUID candidate){
        if(!candidates.get(actor,candidate).contactsVisible())throw new Rejected(HttpStatus.FORBIDDEN,"CONTACT_ACCESS_REQUIRED");
        db.sql("SELECT id FROM candidate_profile WHERE id=:id FOR UPDATE").param("id",candidate).query(UUID.class).single();
    }
    private ContactDrafts.Draft current(UUID actor,UUID candidate){
        governance.requireContactAllowed(actor,candidate);
        var values=drafts.history(actor,candidate,0).items();if(values.isEmpty())reject("CONTACT_SAVED_DRAFT_REQUIRED");
        var d=values.getFirst();var context=drafts.context(actor,candidate);
        if(!Objects.equals(d.publicationId(),context.publicationId())||!Objects.equals(d.recipient(),context.recipient()))reject("CONTACT_APPROVAL_CHANGED");
        String link=d.variables().get("unsubscribeOrObjectionLink");
        if(!d.missingFields().isEmpty())reject("CONTACT_DRAFT_INCOMPLETE");
        if(link==null||!governance.validIssuedLink(candidate,link)||!d.body().contains(link))reject("CONTACT_OPPOSITION_LINK_REQUIRED");
        try{SmtpContactTransport.address(d.recipient());SmtpContactTransport.address(d.variables().get("contactEmail"));SmtpContactTransport.address(transport.sender());}
        catch(Exception e){reject("CONTACT_ADDRESS_INVALID");}
        return d;
    }
    private String hash(ContactDrafts.Draft d){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(List.of(
        transport.sender(),d.recipient(),d.variables().get("contactEmail"),d.subject(),d.body(),d.publicationId().toString(),Long.toString(d.version()),d.templateId(),d.templateVersion())).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException("CONTACT_HASH_FAILED",e);}}
    public Receipt approve(UUID actor,UUID candidate,Approval input){return tx.execute(t->{
        var d=current(actor,candidate);
        if(input==null||!input.confirmed()||input.draftVersion()!=d.version()||!Objects.equals(input.recipient(),d.recipient())||!Objects.equals(input.subject(),d.subject())||!Objects.equals(input.body(),d.body()))reject("CONTACT_EXACT_REVIEW_REQUIRED");
        var existing=db.sql("SELECT id FROM contact_delivery WHERE candidate_id=:c AND actor_id=:a AND draft_version=:v").param("c",candidate).param("a",actor).param("v",d.version()).query(UUID.class).optional();
        if(existing.isPresent()){var r=row(actor,candidate,existing.get());if(!r.status().equals("APPROVED"))return r;if(r.expiresAt().isBefore(now())||!r.contentHash().equals(hash(d)))reject("CONTACT_NEW_DRAFT_REQUIRED");return r;}
        UUID id=UUID.randomUUID();var now=now();String messageId="<"+id+"@skillhunters.local>";
        db.sql("INSERT INTO contact_delivery(id,candidate_id,actor_id,pool_id,draft_version,content_hash,sender,recipient,reply_to,subject,body,message_id,status,approved_at,expires_at,history_expires_at) VALUES (:id,:c,:a,:p,:v,:hash,:sender,:recipient,:reply,:subject,:body,:mid,'APPROVED',:now,:expiry,:retention)")
            .param("id",id).param("c",candidate).param("a",actor).param("p",candidates.get(actor,candidate).poolId()).param("v",d.version()).param("hash",hash(d)).param("sender",transport.sender()).param("recipient",d.recipient()).param("reply",d.variables().get("contactEmail"))
            .param("subject",d.subject()).param("body",d.body()).param("mid",messageId).param("now",now).param("expiry",now.plusMinutes(30)).param("retention",journal.retention(candidates.get(actor,candidate).expiresAt())).update();
        journal.event(actor,id,"APPROVED",null);
        audit.candidate(actor,candidate,"CONTACT_APPROVED");return row(actor,candidate,id);
    });}
    public Receipt get(UUID actor,UUID candidate,UUID id){return tx.execute(t->{access(actor,candidate);var r=row(actor,candidate,id);
        if(r.status().equals("SENDING")&&r.attemptedAt().isBefore(now().minusMinutes(2))){finish(actor,candidate,id,"UNCERTAIN","INTERRUPTED_ATTEMPT");r=row(actor,candidate,id);}return r;
    });}
    public Receipt send(UUID actor,UUID candidate,UUID id){
        boolean claimed;
        try{claimed=tx.execute(t->{access(actor,candidate);var r=row(actor,candidate,id);if(!r.status().equals("APPROVED"))return false;
            if(!transport.enabled())throw new Rejected(HttpStatus.SERVICE_UNAVAILABLE,"SMTP_DISABLED");validate(actor,candidate,r);
            db.sql("UPDATE contact_delivery SET status='SENDING',attempted_at=:now WHERE id=:id").param("now",now()).param("id",id).update();journal.event(actor,id,"SENDING",null);audit.candidate(actor,candidate,"CONTACT_SEND_STARTED");return true;});
        }catch(Rejected denied){invalidate(actor,candidate,id);throw denied;}
        if(!claimed)return get(actor,candidate,id); // Repeated clicks/requests never send a second time.
        try{return tx.execute(t->{
            access(actor,candidate);var r=row(actor,candidate,id);if(!r.status().equals("SENDING"))return r;
            validate(actor,candidate,r); // Same candidate lock as opposition; held through this one bounded SMTP attempt.
            ContactTransport.Result result;
            try{result=transport.send(new ContactTransport.Envelope(r.sender(),r.recipient(),r.replyTo(),r.subject(),r.body(),r.messageId()));}
            catch(Exception ambiguous){result=new ContactTransport.Result("UNCERTAIN","SMTP_RESULT_UNCERTAIN");}
            if(!Set.of("ACCEPTED","UNCERTAIN","FAILED").contains(result.status()))result=new ContactTransport.Result("UNCERTAIN","SMTP_RESULT_UNCERTAIN");
            finish(actor,candidate,id,result.status(),result.code());return row(actor,candidate,id);
        });}catch(Rejected denied){invalidate(actor,candidate,id);throw denied;}
        // A crash/DB error after SMTP leaves the already-committed SENDING marker; no automatic replay.
    }
    private void validate(UUID actor,UUID candidate,Receipt r){var d=current(actor,candidate);
        if(!r.expiresAt().isAfter(now())||d.version()!=r.draftVersion()||!hash(d).equals(r.contentHash()))reject("CONTACT_APPROVAL_CHANGED");
    }
    private void finish(UUID actor,UUID candidate,UUID id,String status,String code){
        db.sql("UPDATE contact_delivery SET status=:s,result_code=:code,completed_at=:now WHERE id=:id").param("s",status).param("code",code).param("now",now()).param("id",id).update();
        journal.event(actor,id,status,code);
        audit.candidate(actor,candidate,"CONTACT_"+status);
    }
    private void invalidate(UUID actor,UUID candidate,UUID id){tx.executeWithoutResult(t->{
        if(db.sql("UPDATE contact_delivery SET status='INVALIDATED',result_code='REVIEW_REQUIRED',completed_at=CURRENT_TIMESTAMP WHERE id=:id AND actor_id=:a AND candidate_id=:c AND status IN ('APPROVED','SENDING')")
            .param("id",id).param("a",actor).param("c",candidate).update()>0)
            {journal.event(actor,id,"INVALIDATED","REVIEW_REQUIRED");audit.record(actor,"CONTACT_INVALIDATED",candidate,null,null);}
    });}
    private Receipt row(UUID actor,UUID candidate,UUID id){return db.sql("SELECT * FROM contact_delivery WHERE id=:id AND actor_id=:a AND candidate_id=:c AND content_purged=FALSE AND history_expires_at>CURRENT_TIMESTAMP FOR UPDATE")
        .param("id",id).param("a",actor).param("c",candidate).query((r,n)->new Receipt(r.getObject("id",UUID.class),r.getLong("draft_version"),r.getString("content_hash"),r.getString("sender"),r.getString("recipient"),r.getString("reply_to"),r.getString("subject"),r.getString("body"),r.getString("message_id"),r.getString("status"),r.getObject("approved_at",OffsetDateTime.class),r.getObject("expires_at",OffsetDateTime.class),r.getObject("attempted_at",OffsetDateTime.class),r.getObject("completed_at",OffsetDateTime.class),r.getString("result_code"))).optional().orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"CONTACT_DELIVERY_NOT_FOUND"));}
    private static OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    private static void reject(String code){throw new Rejected(HttpStatus.CONFLICT,code);}
}
