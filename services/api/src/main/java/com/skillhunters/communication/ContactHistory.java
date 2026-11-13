package com.skillhunters.communication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.governance.AuditTrail;
import com.skillhunters.shared.ApiProblem.Rejected;
import com.skillhunters.talent.CandidateService;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ContactHistory {
    public record Summary(UUID id,UUID actorId,long draftVersion,String recipient,String subject,String status,OffsetDateTime approvedAt,OffsetDateTime attemptedAt,OffsetDateTime completedAt,OffsetDateTime retainedUntil,String resultCode) {}
    public record Prepared(long draftVersion,String subject,OffsetDateTime createdAt,String status) {}
    public record Page(Prepared prepared,List<Summary> items,String nextCursor) {}
    public record Event(long sequence,UUID actorId,String status,String code,OffsetDateTime occurredAt) {}
    public record Detail(Summary summary,String sender,String replyTo,String body,String messageId,String contentHash,List<Event> events) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final CandidateService candidates;private final ContactJournal journal;private final AuditTrail audit;private final ObjectMapper json;
    public ContactHistory(JdbcClient db,TransactionTemplate tx,CandidateService candidates,ContactJournal journal,AuditTrail audit,ObjectMapper json){this.db=db;this.tx=tx;this.candidates=candidates;this.journal=journal;this.audit=audit;this.json=json;}
    private void access(UUID actor,UUID candidate){
        if(!candidates.get(actor,candidate).contactsVisible())throw new Rejected(HttpStatus.FORBIDDEN,"CONTACT_ACCESS_REQUIRED");
        db.sql("SELECT id FROM candidate_profile WHERE id=:id FOR UPDATE").param("id",candidate).query(UUID.class).single();
        journal.reconcile(candidate);
    }
    public Page list(UUID actor,UUID candidate,String cursor,String status){return tx.execute(t->{
        access(actor,candidate);
        if(status!=null&&!Set.of("APPROVED","INVALIDATED","SENDING","ACCEPTED","UNCERTAIN","FAILED").contains(status))invalid();
        OffsetDateTime before=null;UUID beforeId=null;
        if(cursor!=null){try{if(cursor.length()>256)invalid();var parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=4||!parts[0].equals(candidate.toString())||!parts[3].equals(status==null?"":status))invalid();before=OffsetDateTime.parse(parts[1]);beforeId=UUID.fromString(parts[2]);
        }catch(IllegalArgumentException | DateTimeException e){invalid();}}
        var q=db.sql("SELECT * FROM contact_delivery WHERE candidate_id=:c AND content_purged=FALSE AND history_expires_at>:now"+(status==null?"":" AND status=:status")
            +(before==null?"":" AND (approved_at<:before OR (approved_at=:before AND id<:beforeId))")+" ORDER BY approved_at DESC,id DESC LIMIT 26")
            .param("c",candidate).param("now",now());if(status!=null)q=q.param("status",status);if(before!=null)q=q.param("before",before).param("beforeId",beforeId);
        var all=q.query(ContactHistory::summary).list();var items=all.stream().limit(25).toList();String next=null;
        if(all.size()>25){var last=items.getLast();next=Base64.getUrlEncoder().withoutPadding().encodeToString((candidate+"|"+last.approvedAt()+"|"+last.id()+"|"+(status==null?"":status)).getBytes(StandardCharsets.UTF_8));}
        Prepared prepared=null;
        if(cursor==null&&status==null){
            var latest=db.sql("SELECT payload_json FROM contact_draft_revision r WHERE candidate_id=:c AND actor_id=:a AND content_purged=FALSE AND created_at>:cutoff AND version=(SELECT MAX(version) FROM contact_draft_revision WHERE candidate_id=:c AND actor_id=:a) AND NOT EXISTS(SELECT 1 FROM contact_delivery d WHERE d.candidate_id=r.candidate_id AND d.actor_id=r.actor_id AND d.draft_version=r.version)")
                .param("c",candidate).param("a",actor).param("cutoff",journal.draftCutoff()).query(String.class).optional();
            if(latest.isPresent())try{var draft=json.readValue(latest.get(),ContactDrafts.Draft.class);prepared=new Prepared(draft.version(),draft.subject(),draft.createdAt(),"PREPARED");}catch(Exception e){throw new IllegalStateException("CONTACT_HISTORY_STORAGE",e);}
        }
        audit.candidate(actor,candidate,"CONTACT_HISTORY_READ");return new Page(prepared,items,next);
    });}
    public Detail detail(UUID actor,UUID candidate,UUID id){return tx.execute(t->{
        access(actor,candidate);
        var summary=db.sql("SELECT * FROM contact_delivery WHERE id=:id AND candidate_id=:c AND content_purged=FALSE AND history_expires_at>:now")
            .param("id",id).param("c",candidate).param("now",now()).query(ContactHistory::summary).optional().orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"CONTACT_HISTORY_NOT_FOUND"));
        var fields=db.sql("SELECT sender,reply_to,body,message_id,content_hash FROM contact_delivery WHERE id=:id").param("id",id).query((r,n)->List.of(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5))).single();
        var events=db.sql("SELECT sequence,actor_id,status,code,occurred_at FROM contact_delivery_event WHERE delivery_id=:id ORDER BY sequence LIMIT 100")
            .param("id",id).query((r,n)->new Event(r.getLong(1),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getObject(5,OffsetDateTime.class))).list();
        audit.candidate(actor,candidate,"CONTACT_HISTORY_READ");return new Detail(summary,fields.get(0),fields.get(1),fields.get(2),fields.get(3),fields.get(4),events);
    });}
    private static Summary summary(java.sql.ResultSet r,int n)throws java.sql.SQLException{return new Summary(r.getObject("id",UUID.class),r.getObject("actor_id",UUID.class),r.getLong("draft_version"),r.getString("recipient"),r.getString("subject"),r.getString("status"),r.getObject("approved_at",OffsetDateTime.class),r.getObject("attempted_at",OffsetDateTime.class),r.getObject("completed_at",OffsetDateTime.class),r.getObject("history_expires_at",OffsetDateTime.class),r.getString("result_code"));}
    private static OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    private static void invalid(){throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_CONTACT_HISTORY_FILTER");}
}
