package com.skillhunters.governance;

import com.skillhunters.shared.ApiProblem.Rejected;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Minimal metadata only: never CV text, contacts, notes, messages or tokens. */
@Service
public class AuditTrail {
    public record Event(long sequence,UUID id,UUID actorId,String action,UUID objectId,UUID poolId,String purpose,OffsetDateTime occurredAt) {}
    public record Page(List<Event> items,Long nextCursor) {}
    private final JdbcClient db;private final TransactionTemplate tx;
    public AuditTrail(JdbcClient db,TransactionTemplate tx){this.db=db;this.tx=tx;}
    public void record(UUID actor,String action,UUID object,UUID pool,String purpose) {
        db.sql("INSERT INTO governance_audit(id,actor_id,action,object_id,pool_id,purpose) VALUES (:id,:actor,:action,:object,:pool,:purpose)")
            .param("id",UUID.randomUUID()).param("actor",actor).param("action",action).param("object",object).param("pool",pool).param("purpose",purpose).update();
    }
    private record Context(UUID pool,String purpose) {}
    public void candidate(UUID actor,UUID candidate,String action) {
        var context=db.sql("SELECT d.pool_id,p.purpose FROM candidate_profile c JOIN document_import d ON d.id=c.import_id JOIN import_policy p ON p.id=d.policy_id WHERE c.id=:id")
            .param("id",candidate).query((rs,n)->new Context(rs.getObject(1,UUID.class),rs.getString(2))).single();
        record(actor,action,candidate,context.pool(),context.purpose());
    }
    public void document(UUID actor,UUID document,String action) {
        var context=db.sql("SELECT d.pool_id,p.purpose FROM document_import d JOIN import_policy p ON p.id=d.policy_id WHERE d.id=:id")
            .param("id",document).query((rs,n)->new Context(rs.getObject(1,UUID.class),rs.getString(2))).single();
        record(actor,action,document,context.pool(),context.purpose());
    }
    public Page list(UUID actor,UUID pool,Long before,String action) {
        if(before!=null&&before<1||action!=null&&!action.matches("[A-Z_]{1,80}"))throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_AUDIT_FILTER");
        return tx.execute(t->{
            if(!db.sql("SELECT enabled FROM app_user WHERE id=:id FOR UPDATE").param("id",actor).query(Boolean.class).optional().orElse(false))throw new Rejected(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED");
            if(db.sql("SELECT COUNT(*) FROM global_role WHERE user_id=:id AND role='COMPLIANCE'").param("id",actor).query(Integer.class).single()==0)throw new Rejected(HttpStatus.FORBIDDEN,"AUDIT_PERMISSION_REQUIRED");
            var sql=db.sql("SELECT sequence,id,actor_id,action,object_id,pool_id,purpose,occurred_at FROM governance_audit WHERE 1=1"
                +(pool==null?"":" AND pool_id=:pool")+(before==null?"":" AND sequence<:before")+(action==null?"":" AND action=:action")+" ORDER BY sequence DESC LIMIT 51");
            if(pool!=null)sql=sql.param("pool",pool);if(before!=null)sql=sql.param("before",before);if(action!=null)sql=sql.param("action",action);
            var values=sql.query((rs,n)->new Event(rs.getLong(1),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4),rs.getObject(5,UUID.class),rs.getObject(6,UUID.class),rs.getString(7),rs.getObject(8,OffsetDateTime.class))).list();
            var page=values.stream().limit(50).toList();
            record(actor,"AUDIT_READ",null,pool,null);
            return new Page(page,values.size()>50?page.getLast().sequence():null);
        });
    }
}
