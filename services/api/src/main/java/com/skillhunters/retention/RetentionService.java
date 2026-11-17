package com.skillhunters.retention;

import com.skillhunters.documents.QuarantineStorage;
import com.skillhunters.governance.AuditTrail;
import com.skillhunters.talent.CandidateIdentity;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RetentionService implements ApplicationRunner {
    public record Hold(OffsetDateTime until,String reason,boolean confirmed) {}
    public record Status(UUID sourceId,OffsetDateTime expiresAt,long holdVersion,OffsetDateTime holdUntil,String holdReason,String erasureState,OffsetDateTime completedAt,String resultCode,List<String> categories) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final RetentionGuard guard;private final ErasureLedger ledger;private final QuarantineStorage storage;private final AuditTrail audit;private final CandidateIdentity identities;
    public RetentionService(JdbcClient db,TransactionTemplate tx,RetentionGuard guard,ErasureLedger ledger,QuarantineStorage storage,AuditTrail audit,CandidateIdentity identities){this.db=db;this.tx=tx;this.guard=guard;this.ledger=ledger;this.storage=storage;this.audit=audit;this.identities=identities;}
    public void mutex(){db.sql("SELECT id FROM retention_mutex WHERE id=1 FOR UPDATE").query(Integer.class).single();}
    private UUID access(UUID actor,UUID source){
        if(!db.sql("SELECT enabled FROM app_user WHERE id=:a FOR UPDATE").param("a",actor).query(Boolean.class).optional().orElse(false))deny("ACCOUNT_DISABLED");
        if(db.sql("SELECT COUNT(*) FROM global_role WHERE user_id=:a AND role='COMPLIANCE'").param("a",actor).query(Integer.class).single()==0)deny("COMPLIANCE_REQUIRED");
        var pool=db.sql("SELECT pool_id FROM document_import WHERE id=:id UNION SELECT pool_id FROM erasure_tombstone WHERE source_id=:id AND kind='IMPORT'").param("id",source).query(UUID.class).optional().orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"RETENTION_SOURCE_NOT_FOUND"));
        if(db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:a AND pool_id=:p").param("a",actor).param("p",pool).query(Integer.class).single()==0)throw new Rejected(HttpStatus.NOT_FOUND,"RETENTION_SOURCE_NOT_FOUND");
        return pool;
    }
    public record Proof(UUID sourceId,String kind,String state,OffsetDateTime requestedAt,OffsetDateTime completedAt,String resultCode) {}
    public record Page(List<Proof> items,UUID nextCursor) {}
    public Page proofs(UUID actor,UUID pool,UUID cursor){return tx.execute(t->{
        if(!db.sql("SELECT enabled FROM app_user WHERE id=:a FOR UPDATE").param("a",actor).query(Boolean.class).optional().orElse(false))deny("ACCOUNT_DISABLED");
        if(db.sql("SELECT COUNT(*) FROM global_role WHERE user_id=:a AND role='COMPLIANCE'").param("a",actor).query(Integer.class).single()==0)deny("COMPLIANCE_REQUIRED");
        if(db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:a AND pool_id=:p").param("a",actor).param("p",pool).query(Integer.class).single()==0)throw new Rejected(HttpStatus.NOT_FOUND,"POOL_NOT_FOUND");
        var q=db.sql("SELECT source_id,kind,state,requested_at,completed_at,result_code FROM erasure_tombstone WHERE pool_id=:p"+(cursor==null?"":" AND source_id>:cursor")+" ORDER BY source_id LIMIT 26").param("p",pool);
        if(cursor!=null)q=q.param("cursor",cursor);
        var all=q.query((r,n)->new Proof(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getObject(4,OffsetDateTime.class),r.getObject(5,OffsetDateTime.class),r.getString(6))).list();
        audit.record(actor,"ERASURE_PROOFS_READ",null,pool,null);var items=all.stream().limit(25).toList();return new Page(items,all.size()>25?items.getLast().sourceId():null);
    });}
    private Status view(UUID source){
        var expiry=db.sql("SELECT expires_at FROM document_import WHERE id=:id").param("id",source).query(OffsetDateTime.class).optional().orElse(null);
        var hold=db.sql("SELECT version,until_at,reason FROM retention_hold WHERE import_id=:id").param("id",source).query((r,n)->new Object[]{r.getLong(1),r.getObject(2,OffsetDateTime.class),r.getString(3)}).optional().orElse(new Object[]{0L,null,null});
        var erased=db.sql("SELECT state,completed_at,result_code FROM erasure_tombstone WHERE source_id=:id").param("id",source).query((r,n)->new Object[]{r.getString(1),r.getObject(2,OffsetDateTime.class),r.getString(3)}).optional().orElse(new Object[]{"LIVE",null,null});
        return new Status(source,expiry,(long)hold[0],(OffsetDateTime)hold[1],(String)hold[2],(String)erased[0],(OffsetDateTime)erased[1],(String)erased[2],erased[0].equals("COMPLETE")?categories():List.of());
    }
    public Status get(UUID actor,UUID source){return tx.execute(t->{var pool=access(actor,source);audit.record(actor,"RETENTION_READ",source,pool,null);return view(source);});}
    public Status hold(UUID actor,UUID source,String etag,Hold request){return tx.execute(t->{
        var pool=access(actor,source);mutex();guard.live(source);lockSource(source);var old=view(source);
        version(etag,old.holdVersion());if(request==null||!request.confirmed()||request.reason()==null||request.reason().isBlank()||request.reason().length()>1000||request.reason().codePoints().anyMatch(Character::isISOControl))invalid();
        if(request.until()!=null&&(!request.until().isAfter(now())||request.until().isAfter(now().plusDays(365))))invalid();
        if(old.holdVersion()==0)db.sql("INSERT INTO retention_hold(import_id,version,actor_id,reason,until_at) VALUES (:id,1,:a,:r,:until)").param("id",source).param("a",actor).param("r",request.reason().strip()).param("until",request.until()==null?now():request.until()).update();
        else db.sql("UPDATE retention_hold SET version=version+1,actor_id=:a,reason=:r,until_at=:until,approved_at=:now WHERE import_id=:id").param("id",source).param("a",actor).param("r",request.reason().strip()).param("until",request.until()==null?now():request.until()).param("now",now()).update();
        audit.record(actor,request.until()==null?"RETENTION_HOLD_RELEASED":"RETENTION_HOLD_APPROVED",source,pool,null);return view(source);
    });}
    public Status request(UUID actor,UUID source,boolean confirmed){
        if(!confirmed)invalid();
        var record=tx.execute(t->{var pool=access(actor,source);mutex();var r=enqueue(source,"IMPORT",false);audit.record(actor,"ERASURE_REQUESTED",source,pool,null);return r;});
        try{ledger.append(record);}catch(Exception unavailable){throw new Rejected(HttpStatus.SERVICE_UNAVAILABLE,"ERASURE_LEDGER_UNAVAILABLE");}return get(actor,source);
    }
    private void lockSource(UUID source){
        var candidate=db.sql("SELECT id FROM candidate_profile WHERE import_id=:id").param("id",source).query(UUID.class).optional();
        if(candidate.isPresent()){identities.lock(candidate.get());db.sql("SELECT id FROM candidate_profile WHERE id=:id FOR UPDATE").param("id",candidate.get()).query(UUID.class).optional();}
        db.sql("SELECT import_id FROM document_analysis WHERE import_id=:id FOR UPDATE").param("id",source).query(UUID.class).optional();
        db.sql("SELECT id FROM document_import WHERE id=:id FOR UPDATE").param("id",source).query(UUID.class).optional();
    }
    private ErasureRecord record(UUID id){return db.sql("SELECT * FROM erasure_tombstone WHERE source_id=:id").param("id",id).query((r,n)->new ErasureRecord(id,r.getString("kind"),r.getObject("pool_id",UUID.class),r.getString("replay_hash"),r.getObject("requested_at",OffsetDateTime.class),r.getString("signature"))).single();}
    private ErasureRecord enqueue(UUID id,String kind,boolean expiredOnly){
        if(guard.erased(id))return record(id);
        String table=kind.equals("IMPORT")?"document_import":"import_batch";
        if(kind.equals("IMPORT"))lockSource(id);
        var values=db.sql("SELECT actor_id,pool_id,idempotency_key,expires_at FROM "+table+" WHERE id=:id FOR UPDATE").param("id",id).query((r,n)->new Object[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getObject(4,OffsetDateTime.class)}).optional();
        if(values.isEmpty())throw new Rejected(HttpStatus.NOT_FOUND,"RETENTION_SOURCE_NOT_FOUND");
        var v=values.get();if(expiredOnly&&((OffsetDateTime)v[3]).isAfter(now()))throw new Rejected(HttpStatus.CONFLICT,"RETENTION_NOT_DUE");
        if(kind.equals("IMPORT")&&held(id))throw new Rejected(HttpStatus.CONFLICT,"RETENTION_HOLD_ACTIVE");
        var r=guard.sign(id,kind,(UUID)v[1],guard.replayHash(kind,(UUID)v[0],(UUID)v[1],(String)v[2]),now());
        insert(r);db.sql("UPDATE "+table+" SET expires_at=:now WHERE id=:id").param("now",now()).param("id",id).update();return r;
    }
    private boolean held(UUID id){return db.sql("SELECT COUNT(*) FROM retention_hold WHERE import_id=:id AND until_at>:now").param("id",id).param("now",now()).query(Integer.class).single()>0;}
    private void insert(ErasureRecord r){db.sql("INSERT INTO erasure_tombstone(source_id,kind,pool_id,replay_hash,requested_at,signature) VALUES (:id,:kind,:pool,:hash,:at,:signature)").param("id",r.sourceId()).param("kind",r.kind()).param("pool",r.poolId()).param("hash",r.replayHash()).param("at",r.requestedAt()).param("signature",r.signature()).update();}
    @Override public void run(ApplicationArguments args){replay();}
    public void replay(){
        guard.ready(false);
        ledger.scan(r->{guard.verify(r);tx.executeWithoutResult(t->{mutex();
            if(!guard.erased(r.sourceId()))insert(r);else if(!record(r.sourceId()).signature().equals(r.signature()))throw new IllegalStateException("ERASURE_LEDGER_CONFLICT");
            // Restore never reopens data already erased, even if an old backup contains an old hold.
            if(r.kind().equals("IMPORT")){lockSource(r.sourceId());db.sql("DELETE FROM retention_hold WHERE import_id=:id").param("id",r.sourceId()).update();}
            db.sql("UPDATE "+(r.kind().equals("IMPORT")?"document_import":"import_batch")+" SET expires_at=:now WHERE id=:id").param("now",now()).param("id",r.sourceId()).update();
            db.sql("UPDATE erasure_tombstone SET state='PENDING',completed_at=NULL,next_attempt_at=:now WHERE source_id=:id").param("now",now()).param("id",r.sourceId()).update();
        });});
        guard.ready(true);
    }
    @Scheduled(initialDelayString="${app.retention.poll-ms:60000}",fixedDelayString="${app.retention.poll-ms:60000}")
    public void runOnce(){
        if(!guard.ready())return;
        var due=db.sql("SELECT d.id FROM document_import d WHERE d.expires_at<=:now AND "+RetentionGuard.UNHELD_SOURCE+" AND NOT EXISTS(SELECT 1 FROM erasure_tombstone e WHERE e.source_id=d.id) ORDER BY d.expires_at LIMIT 25").param("now",now()).query(UUID.class).list();
        for(UUID id:due)try{tx.executeWithoutResult(t->{mutex();enqueue(id,"IMPORT",true);});}catch(Rejected changed){/* Hold/deletion raced the selection; reevaluate next pass. */}
        expireBatches();
        var pending=db.sql("SELECT source_id FROM erasure_tombstone WHERE state='PENDING' AND next_attempt_at<=:now ORDER BY requested_at LIMIT 25").param("now",now()).query(UUID.class).list();
        for(UUID id:pending)erase(id);
    }
    private void expireBatches(){tx.executeWithoutResult(t->{mutex();
        // Remove unheld manifest payloads even if a different item keeps the shared batch under hold.
        db.sql("UPDATE import_batch_item SET file_name='',sha256='',byte_size=0,state='FAILED',error_code='RETENTION_EXPIRED',lease_token=NULL,lease_until=NULL WHERE batch_id IN(SELECT id FROM import_batch WHERE expires_at<=:now) AND NOT EXISTS(SELECT 1 FROM document_import d JOIN retention_hold h ON h.import_id=d.id WHERE d.idempotency_key=CONCAT('batch-',CAST(import_batch_item.id AS VARCHAR)) AND h.until_at>:now)").param("now",now()).update();
        var ids=db.sql("SELECT b.id FROM import_batch b WHERE b.expires_at<=:now AND NOT EXISTS(SELECT 1 FROM erasure_tombstone e WHERE e.source_id=b.id) AND NOT EXISTS(SELECT 1 FROM import_batch_item i JOIN document_import d ON d.idempotency_key=CONCAT('batch-',CAST(i.id AS VARCHAR)) JOIN retention_hold h ON h.import_id=d.id WHERE i.batch_id=b.id AND h.until_at>:now) LIMIT 25").param("now",now()).query(UUID.class).list();
        for(UUID id:ids)enqueue(id,"BATCH",true);
    });}
    public void erase(UUID id){
        var claimed=tx.execute(t->{mutex();return db.sql("UPDATE erasure_tombstone SET attempts=attempts+1,next_attempt_at=:next WHERE source_id=:id AND state='PENDING' AND next_attempt_at<=:now").param("next",now().plusMinutes(5)).param("id",id).param("now",now()).update()==1;});
        if(!Boolean.TRUE.equals(claimed))return;
        var r=record(id);
        try{
            guard.verify(r);ledger.append(r); // Durable outside DB before deleting anything.
            if(r.kind().equals("IMPORT"))storage.delete("quarantine/"+r.poolId()+"/"+r.sourceId());
            tx.executeWithoutResult(t->{mutex();
                if(r.kind().equals("IMPORT")){
                    lockSource(id);
                    var batchKey=db.sql("SELECT idempotency_key FROM document_import WHERE id=:id").param("id",id).query(String.class).optional();
                    if(batchKey.isPresent()&&batchKey.get().startsWith("batch-")){
                        var item=UUID.fromString(batchKey.get().substring(6));
                        db.sql("DELETE FROM import_batch_event WHERE item_id=:item").param("item",item).update();
                        db.sql("UPDATE import_batch_item SET file_name='',sha256='',byte_size=0,state='FAILED',error_code='SOURCE_ERASED',lease_token=NULL,lease_until=NULL WHERE id=:item").param("item",item).update();
                    }
                    db.sql("DELETE FROM document_audit WHERE import_id=:id").param("id",id).update();
                    db.sql("DELETE FROM document_import WHERE id=:id").param("id",id).update();
                    db.sql("DELETE FROM candidate_identity WHERE NOT EXISTS(SELECT 1 FROM candidate_identity_member m WHERE m.identity_id=candidate_identity.id)").update();
                    db.sql("DELETE FROM scored_search WHERE NOT EXISTS(SELECT 1 FROM scored_search_item i WHERE i.search_id=scored_search.id)").update();
                }else{
                    db.sql("DELETE FROM import_batch_event WHERE batch_id=:id").param("id",id).update();
                    db.sql("DELETE FROM import_batch_item WHERE batch_id=:id").param("id",id).update();
                    db.sql("DELETE FROM import_batch WHERE id=:id").param("id",id).update();
                }
                db.sql("UPDATE erasure_tombstone SET state='COMPLETE',completed_at=:now,result_code='ERASED' WHERE source_id=:id").param("now",now()).param("id",id).update();
                audit.record(null,"ERASURE_COMPLETE",id,r.poolId(),null);
            });
        }catch(Exception unavailable){
            tx.executeWithoutResult(t->db.sql("UPDATE erasure_tombstone SET result_code='ERASURE_RETRY_REQUIRED',next_attempt_at=:next WHERE source_id=:id").param("id",id).param("next",now().plusMinutes(1)).update());
        }
    }
    private static List<String> categories(){return List.of("ORIGINAL_ALL_VERSIONS","TEXT_OCR_EXTRACTIONS","CANDIDATE_REVISIONS_CONTACTS","EXPORTS","SEARCH_SNAPSHOTS_COMPARISONS","TRACKING","CONTACT_MESSAGES","GOVERNANCE_PAYLOADS","BATCH_METADATA");}
    private static OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);}
    private static void version(String value,long version){if(value==null)throw new Rejected(HttpStatus.PRECONDITION_REQUIRED,"VERSION_REQUIRED");if(!value.equals("\""+version+"\""))throw new Rejected(HttpStatus.PRECONDITION_FAILED,"RETENTION_HOLD_CHANGED");}
    private static void invalid(){throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_RETENTION_REQUEST");}
    private static void deny(String code){throw new Rejected(HttpStatus.FORBIDDEN,code);}
}
