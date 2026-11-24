package com.skillhunters.documents;

import com.skillhunters.governance.AuditTrail;
import com.skillhunters.retention.RetentionGuard;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Operational metadata only. Never selects filenames, contacts, extracted text or worker messages. */
@Service
public class ImportOperations {
    public enum Reason { WORKER_RECOVERED, CONFIGURATION_FIXED, SOURCE_ACCESS_RESTORED }
    public enum Queue { FAILED, CANCELLED, REJECTED, RETRY, EXPIRED_LEASE }
    public record RetryRequest(Reason reason) {}
    public record Job(UUID id,long version,String state,int attempts,int manualRetries,int leaseRecoveries,String code,OffsetDateTime updatedAt,OffsetDateTime leaseUntil,boolean retryable) {}
    public record Page(List<Job> items,UUID nextCursor) {}
    public record Window(long successful,long failed,long rejected,long cancelled,long automaticRetries,long manualRetries,long leaseRecoveries,Double meanAttemptMillis) {}
    public record Metrics(OffsetDateTime observedAt,OffsetDateTime since,Map<String,Long> states,long expiredLeases,long oldestWaitingSeconds,Window window,Double technicalFailureRate,List<String> alerts) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final AnalysisService analysis;private final AuditTrail audit;private final RetentionGuard retention;
    public ImportOperations(JdbcClient db,TransactionTemplate tx,AnalysisService analysis,AuditTrail audit,RetentionGuard retention){this.db=db;this.tx=tx;this.analysis=analysis;this.audit=audit;this.retention=retention;}
    private void access(UUID actor,UUID pool) {
        if(!db.sql("SELECT enabled FROM app_user WHERE id=:a FOR UPDATE").param("a",actor).query(Boolean.class).optional().orElse(false))fail(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED");
        if(db.sql("SELECT COUNT(*) FROM global_role WHERE user_id=:a AND role='ADMIN'").param("a",actor).query(Integer.class).single()==0)fail(HttpStatus.FORBIDDEN,"ADMIN_REQUIRED");
        if(db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:a AND pool_id=:p").param("a",actor).param("p",pool).query(Integer.class).single()==0)fail(HttpStatus.NOT_FOUND,"POOL_NOT_FOUND");
    }
    private static OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    public Metrics metrics(UUID actor,UUID pool){return tx.execute(t->{
        access(actor,pool);var now=now();var since=now.minusHours(24);var states=new LinkedHashMap<String,Long>();
        for(var state:List.of("PENDING","RUNNING","RETRY","EXTRACTED","OCR_REQUIRED","FAILED","CANCELLED","REJECTED"))states.put(state,0L);
        db.sql("SELECT a.state,COUNT(*) AS n FROM document_analysis a JOIN document_import d ON d.id=a.import_id WHERE d.pool_id=:p AND d.expires_at>:now GROUP BY a.state")
            .param("p",pool).param("now",now).query((rs,n)->Map.entry(rs.getString(1),rs.getLong(2))).list().forEach(e->states.put(e.getKey(),e.getValue()));
        long expired=db.sql("SELECT COUNT(*) FROM document_analysis a JOIN document_import d ON d.id=a.import_id WHERE d.pool_id=:p AND d.expires_at>:now AND a.state='RUNNING' AND a.lease_until<=:now").param("p",pool).param("now",now).query(Long.class).single();
        var oldest=db.sql("SELECT MIN(d.created_at) FROM document_analysis a JOIN document_import d ON d.id=a.import_id WHERE d.pool_id=:p AND d.expires_at>:now AND a.state IN ('PENDING','RETRY')")
            .param("p",pool).param("now",now).query((rs,n)->rs.getObject(1,OffsetDateTime.class)).optional();
        long age=oldest.map(date->Math.max(0,Duration.between(date,now).toSeconds())).orElse(0L);
        var window=db.sql("""
            SELECT COALESCE(SUM(CASE WHEN e.state IN ('EXTRACTED','OCR_REQUIRED') THEN 1 ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN e.state='FAILED' THEN 1 ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN e.state='REJECTED' THEN 1 ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN e.state='CANCELLED' THEN 1 ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN e.state='RETRY' THEN 1 ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN e.code='HUMAN_RETRY' THEN 1 ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN e.code='LEASE_EXPIRED' THEN 1 ELSE 0 END),0),AVG(e.duration_ms)
            FROM document_analysis_event e JOIN document_import d ON d.id=e.import_id
            WHERE d.pool_id=:p AND d.expires_at>:now AND e.created_at>=:since AND e.created_at<=:now
            """).param("p",pool).param("now",now).param("since",since).query((rs,n)->new Window(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6),rs.getLong(7),rs.getObject(8)==null?null:rs.getDouble(8))).single();
        Double failure=window.successful()+window.failed()==0?null:(double)window.failed()/(window.successful()+window.failed());
        var alerts=new ArrayList<String>();if(expired>0)alerts.add("EXPIRED_LEASES");if(states.get("FAILED")>0)alerts.add("DEAD_LETTER");if(age>600)alerts.add("WAIT_OVER_TEN_MINUTES");if(!retention.ready())alerts.add("RETENTION_REPLAY_PENDING");
        audit.record(actor,"IMPORT_OPERATIONS_READ",null,pool,null);return new Metrics(now,since,states,expired,age,window,failure,alerts);
    });}
    private static final Set<String> SAFE_CODES=Set.of("WORKER_UNAVAILABLE","INVALID_WORKER_RESULT","ATTEMPTS_EXHAUSTED","SOURCE_UNAVAILABLE","ANTIVIRUS_BLOCKED","INTEGRITY_MISMATCH","OCR_UNAVAILABLE","PARSER_UNAVAILABLE","PARSER_LIMIT","ENCRYPTED_PDF","PDF_ACTIVE_CONTENT","UNSUPPORTED_FORMAT");
    private Job job(java.sql.ResultSet r)throws java.sql.SQLException {
        String code=r.getString("code"),state=r.getString("state");int retries=r.getInt("manual_retries");
        return new Job(r.getObject("import_id",UUID.class),r.getLong("version"),state,r.getInt("attempts"),retries,r.getInt("lease_recoveries"),code==null?null:SAFE_CODES.contains(code)?code:"OTHER",r.getObject("updated_at",OffsetDateTime.class),r.getObject("lease_until",OffsetDateTime.class),Set.of("FAILED","CANCELLED").contains(state)&&retries<3&&retention.ready());
    }
    private static final String COLUMNS="a.import_id,a.version,a.state,a.attempts,a.manual_retries,a.lease_recoveries,a.code,a.updated_at,a.lease_until";
    public Page queue(UUID actor,UUID pool,Queue state,UUID after){return tx.execute(t->{
        access(actor,pool);if(state==null)fail(HttpStatus.BAD_REQUEST,"INVALID_OPERATION_QUEUE");
        String condition=state==Queue.EXPIRED_LEASE?"a.state='RUNNING' AND a.lease_until<=:now":"a.state=:state";
        var sql=db.sql("SELECT "+COLUMNS+" FROM document_analysis a JOIN document_import d ON d.id=a.import_id WHERE d.pool_id=:p AND d.expires_at>:now AND "+condition+(after==null?"":" AND a.import_id>:after")+" ORDER BY a.import_id LIMIT 26").param("p",pool).param("now",now());
        if(state!=Queue.EXPIRED_LEASE)sql=sql.param("state",state.name());if(after!=null)sql=sql.param("after",after);
        var rows=sql.query((rs,n)->job(rs)).list();var page=rows.stream().limit(25).toList();audit.record(actor,"IMPORT_OPERATIONS_QUEUE_READ",null,pool,state.name());return new Page(page,rows.size()>25?page.getLast().id():null);
    });}
    public Job retry(UUID actor,UUID pool,UUID id,String etag,RetryRequest request){return tx.execute(t->{
        access(actor,pool);if(etag==null)fail(HttpStatus.PRECONDITION_REQUIRED,"VERSION_REQUIRED");if(request==null||request.reason()==null)fail(HttpStatus.BAD_REQUEST,"INVALID_OPERATION_RETRY");
        db.sql("SELECT id FROM retention_mutex WHERE id=1 FOR UPDATE").query(Integer.class).single();
        var row=db.sql("SELECT "+COLUMNS+" FROM document_analysis a JOIN document_import d ON d.id=a.import_id WHERE a.import_id=:id AND d.pool_id=:p AND d.expires_at>:now FOR UPDATE")
            .param("id",id).param("p",pool).param("now",now()).query((rs,n)->job(rs)).optional().orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"IMPORT_NOT_FOUND"));
        if(!etag.equals("\""+row.version()+"\""))fail(HttpStatus.PRECONDITION_FAILED,"ANALYSIS_CHANGED");
        if(!Set.of("FAILED","CANCELLED").contains(row.state()))fail(HttpStatus.CONFLICT,"ANALYSIS_NOT_RETRYABLE");
        analysis.retry(actor,id);audit.record(actor,"IMPORT_OPERATIONS_RETRY",id,pool,request.reason().name());
        return db.sql("SELECT "+COLUMNS+" FROM document_analysis a WHERE import_id=:id").param("id",id).query((rs,n)->job(rs)).single();
    });}
    private static void fail(HttpStatus status,String code){throw new Rejected(status,code);}
}
