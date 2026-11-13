package com.skillhunters.communication;

import com.skillhunters.governance.AuditTrail;
import java.time.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ContactJournal {
    private final JdbcClient db;private final TransactionTemplate tx;private final AuditTrail audit;private final int days;
    public ContactJournal(JdbcClient db,TransactionTemplate tx,AuditTrail audit,@Value("${app.contact.history-days:90}") int days){
        if(days<1||days>3650)throw new IllegalArgumentException("INVALID_CONTACT_HISTORY_RETENTION");
        this.db=db;this.tx=tx;this.audit=audit;this.days=days;
    }
    public OffsetDateTime draftCutoff(){return now().minusDays(days);}
    public OffsetDateTime retention(OffsetDateTime sourceExpiry){var limit=now().plusDays(days);return sourceExpiry.isBefore(limit)?sourceExpiry:limit;}
    public void event(UUID actor,UUID delivery,String status,String code){
        db.sql("INSERT INTO contact_delivery_event(delivery_id,actor_id,status,code) VALUES (:id,:actor,:status,:code)").param("id",delivery).param("actor",actor).param("status",status).param("code",code).update();
    }
    public void invalidateApprovals(UUID actor,UUID candidate,String code){
        var ids=db.sql("SELECT id FROM contact_delivery WHERE candidate_id=:c AND actor_id=:a AND status='APPROVED'").param("c",candidate).param("a",actor).query(UUID.class).list();
        for(var id:ids)if(db.sql("UPDATE contact_delivery SET status='INVALIDATED',result_code=:code,completed_at=:now WHERE id=:id AND status='APPROVED'").param("code",code).param("now",now()).param("id",id).update()>0){event(actor,id,"INVALIDATED",code);audit.candidate(actor,candidate,"CONTACT_APPROVAL_INVALIDATED");}
    }
    // Caller holds the candidate lock, also held during the bounded SMTP attempt.
    public void reconcile(UUID candidate){
        var rows=db.sql("SELECT id,status FROM contact_delivery WHERE candidate_id=:c AND content_purged=FALSE AND ((status='SENDING' AND attempted_at<:cutoff) OR (status='APPROVED' AND expires_at<=:now)) FOR UPDATE")
            .param("c",candidate).param("cutoff",now().minusMinutes(2)).param("now",now()).query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getString(2)}).list();
        for(var row:rows){UUID id=(UUID)row[0];boolean interrupted=row[1].equals("SENDING");String status=interrupted?"UNCERTAIN":"INVALIDATED",code=interrupted?"INTERRUPTED_ATTEMPT":"APPROVAL_EXPIRED";
            db.sql("UPDATE contact_delivery SET status=:s,result_code=:code,completed_at=:now WHERE id=:id").param("s",status).param("code",code).param("now",now()).param("id",id).update();
            event(null,id,status,code);audit.candidate(null,candidate,"CONTACT_"+status);
        }
    }
    @Scheduled(initialDelayString="${app.contact.history-cleanup-ms:60000}",fixedDelayString="${app.contact.history-cleanup-ms:60000}")
    public void cleanup(){
        tx.executeWithoutResult(t->db.sql("UPDATE contact_draft_revision SET payload_json='{}',content_purged=TRUE WHERE content_purged=FALSE AND created_at<=:cutoff").param("cutoff",draftCutoff()).update());
        var ids=db.sql("SELECT x.id FROM contact_delivery x JOIN candidate_profile c ON c.id=x.candidate_id JOIN document_import d ON d.id=c.import_id WHERE x.content_purged=FALSE AND (x.history_expires_at<=:now OR d.expires_at<=:now) ORDER BY x.history_expires_at LIMIT 100")
            .param("now",now()).query(UUID.class).list();
        for(var id:ids)tx.executeWithoutResult(t->{
            var candidate=db.sql("SELECT candidate_id FROM contact_delivery WHERE id=:id").param("id",id).query(UUID.class).optional();if(candidate.isEmpty())return;
            if(db.sql("SELECT id FROM candidate_profile WHERE id=:c FOR UPDATE").param("c",candidate.get()).query(UUID.class).optional().isEmpty())return;
            var pool=db.sql("SELECT pool_id FROM contact_delivery WHERE id=:id AND content_purged=FALSE AND (history_expires_at<=:now OR EXISTS (SELECT 1 FROM candidate_profile c JOIN document_import d ON d.id=c.import_id WHERE c.id=contact_delivery.candidate_id AND d.expires_at<=:now)) FOR UPDATE")
                .param("id",id).param("now",now()).query(UUID.class).optional();if(pool.isEmpty())return;
            db.sql("DELETE FROM contact_delivery_event WHERE delivery_id=:id").param("id",id).update();
            // Keep the unique attempt marker: deleting it could make an old approval sendable again.
            db.sql("UPDATE contact_delivery SET sender='',recipient='',reply_to='',subject='',body='',content_purged=TRUE,status=CASE WHEN status='APPROVED' THEN 'INVALIDATED' WHEN status='SENDING' THEN 'UNCERTAIN' ELSE status END WHERE id=:id")
                .param("id",id).update();
            audit.record(null,"CONTACT_HISTORY_PURGED",candidate.get(),pool.get(),null);
        });
    }
    private static OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
}
