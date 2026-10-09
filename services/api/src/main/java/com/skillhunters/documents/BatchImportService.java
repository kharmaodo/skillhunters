package com.skillhunters.documents;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import com.skillhunters.shared.ApiProblem.Rejected;

@Service
public class BatchImportService {
    public record FileSpec(@NotBlank @Size(max=200) @Pattern(regexp="[^\\\\/\\p{Cntrl}\\p{Cf}]+") String fileName,
            @Min(0) @Max(314572800) long byteSize, @NotNull @Pattern(regexp="[a-f0-9]{64}") String sha256) {}
    public record Request(@NotNull UUID poolId, @NotNull @Valid ImportService.Basis basis,
            @NotNull @Size(min=1,max=100) List<@NotNull @Valid FileSpec> files) {}
    public record Item(UUID id, String fileName, long byteSize, String sha256, String state, String errorCode,
            int attempts, OffsetDateTime retryAfter, UUID importId) {}
    public record Batch(UUID id, UUID poolId, OffsetDateTime createdAt, OffsetDateTime expiresAt,
            boolean canUpload, int completedFiles, int totalFiles, List<Item> items) {}
    public record Summary(UUID id, OffsetDateTime createdAt, int completedFiles, int totalFiles) {}
    public record Page(List<Summary> items, String nextCursor) {}
    private record Header(UUID id, UUID actorId, UUID poolId, OffsetDateTime createdAt, OffsetDateTime expiresAt,
            String source, String purpose, String basisCode, String noticeVersion, UUID policyId, String policyLabel, int retentionDays) {}
    private record Claim(UUID token, boolean done) {}
    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final ImportService imports;
    private final ImportQuota quota;
    private final ReceptionValidator validator;

    public BatchImportService(JdbcClient db, TransactionTemplate tx, ImportService imports, ImportQuota quota, ReceptionValidator validator) {
        this.db=db; this.tx=tx; this.imports=imports; this.quota=quota; this.validator=validator;
    }
    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private static Rejected error(HttpStatus status, String code) { return new Rejected(status,code); }

    public Batch create(UUID actor, String key, Request request) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}")) throw error(HttpStatus.BAD_REQUEST,"INVALID_IDEMPOTENCY_KEY");
        long bytes = request.files().stream().mapToLong(FileSpec::byteSize).sum();
        if (bytes > 300L*1024*1024) throw error(HttpStatus.PAYLOAD_TOO_LARGE,"BATCH_TOO_LARGE");
        var fingerprint = new StringBuilder();
        var basis=request.basis();
        for (String value : List.of(basis.source(),basis.purpose(),basis.basisCode(),java.util.Objects.toString(basis.noticeVersion(),""),basis.retentionPolicyId().toString())) append(fingerprint,value);
        for (var file : request.files()) { append(fingerprint,file.fileName()); append(fingerprint,Long.toString(file.byteSize())); append(fingerprint,file.sha256()); }
        String hash = HexFormat.of().formatHex(ReceptionValidator.sha().digest(fingerprint.toString().getBytes(StandardCharsets.UTF_8)));
        UUID id = tx.execute(status -> {
            imports.lockActor(actor,request.poolId());
            var existing = db.sql("SELECT id,request_hash FROM import_batch WHERE actor_id=:actor AND pool_id=:pool AND idempotency_key=:key")
                .param("actor",actor).param("pool",request.poolId()).param("key",key).query((rs,n) -> {
                    if (!hash.equals(rs.getString("request_hash"))) throw error(HttpStatus.CONFLICT,"IDEMPOTENCY_CONFLICT");
                    return rs.getObject("id",UUID.class);
                }).optional();
            if (existing.isPresent()) return existing.get();
            var policy=imports.policies(actor,request.poolId()).stream().filter(p -> p.id().equals(basis.retentionPolicyId())).findFirst()
                .orElseThrow(() -> error(HttpStatus.BAD_REQUEST,"IMPORT_POLICY_UNAVAILABLE"));
            if (!policy.purpose().equals(basis.purpose()) || !policy.basisCode().equals(basis.basisCode())) throw error(HttpStatus.BAD_REQUEST,"IMPORT_POLICY_MISMATCH");
            quota.check(actor,request.files().size(),bytes);
            UUID batch=UUID.randomUUID(); var date=now();
            db.sql("""
                INSERT INTO import_batch(id,actor_id,pool_id,idempotency_key,request_hash,source,purpose,basis_code,
                    notice_version,policy_id,policy_label,retention_days,created_at,expires_at)
                VALUES (:id,:actor,:pool,:key,:hash,:source,:purpose,:basis,:notice,:policy,:label,:days,:created,:expires)
                """).param("id",batch).param("actor",actor).param("pool",request.poolId()).param("key",key).param("hash",hash)
                .param("source",basis.source()).param("purpose",policy.purpose()).param("basis",policy.basisCode())
                .param("notice",basis.noticeVersion()).param("policy",policy.id()).param("label",policy.label()).param("days",policy.retentionDays())
                .param("created",date).param("expires",date.plusDays(policy.retentionDays())).update();
            for (int index=0;index<request.files().size();index++) {
                var file=request.files().get(index); String rejection=manifestRejection(file); UUID item=UUID.randomUUID();
                db.sql("""
                    INSERT INTO import_batch_item(id,batch_id,ordinal,file_name,byte_size,sha256,state,error_code)
                    VALUES (:id,:batch,:ordinal,:name,:size,:sha,:state,:error)
                    """).param("id",item).param("batch",batch).param("ordinal",index).param("name",file.fileName())
                    .param("size",file.byteSize()).param("sha",file.sha256()).param("state",rejection==null ? "WAITING_UPLOAD" : "REJECTED_FORMAT")
                    .param("error",rejection).update();
                if (rejection!=null) audit(actor,batch,item,"FILE_REJECTED",rejection);
            }
            audit(actor,batch,null,"BATCH_RESERVED",null);
            return batch;
        });
        return get(actor,id);
    }
    private static void append(StringBuilder target,String value) { target.append(value.length()).append(':').append(value); }
    private static String manifestRejection(FileSpec file) {
        if (file.byteSize()==0) return "EMPTY_FILE";
        if (file.byteSize()>ReceptionValidator.MAX_BYTES) return "FILE_TOO_LARGE";
        String name=file.fileName().toLowerCase(Locale.ROOT);
        return name.matches(".+\\.(pdf|docx|doc|md)$") ? null : "UNSUPPORTED_FORMAT";
    }
    private Header header(UUID actor,UUID id) {
        return db.sql("""
            SELECT b.* FROM import_batch b WHERE b.id=:id AND EXISTS
            (SELECT 1 FROM membership m WHERE m.pool_id=b.pool_id AND m.user_id=:actor)
            """).param("id",id).param("actor",actor).query(Header.class).optional()
            .orElseThrow(() -> error(HttpStatus.NOT_FOUND,"IMPORT_BATCH_NOT_FOUND"));
    }
    private List<Item> items(Header h) {
        return db.sql("""
            SELECT i.*, d.id AS receipt_id, d.state AS receipt_state
            FROM import_batch_item i LEFT JOIN document_import d ON d.actor_id=:actor AND d.pool_id=:pool
                AND d.idempotency_key=CONCAT('batch-',CAST(i.id AS VARCHAR))
            WHERE i.batch_id=:batch ORDER BY i.ordinal
            """).param("actor",h.actorId()).param("pool",h.poolId()).param("batch",h.id()).query((rs,n) -> {
                // A durable receipt wins if the process died before updating the batch projection.
                boolean accepted="QUARANTINED".equals(rs.getString("receipt_state"));
                String state=accepted ? "QUARANTINED" : rs.getString("state");
                String errorCode=accepted ? null : rs.getString("error_code");
                var lease=rs.getObject("lease_until",OffsetDateTime.class);
                if (!accepted && state.equals("UPLOADING") && lease!=null && !lease.isAfter(now())) {
                    state=rs.getInt("attempts")>=5 ? "FAILED" : "RETRYABLE_FAILURE";
                    errorCode=rs.getInt("attempts")>=5 ? "ATTEMPTS_EXHAUSTED" : "UPLOAD_INTERRUPTED";
                }
                return new Item(rs.getObject("id",UUID.class),rs.getString("file_name"),rs.getLong("byte_size"),rs.getString("sha256"),state,
                    errorCode,rs.getInt("attempts"),accepted ? null : lease,rs.getObject("receipt_id",UUID.class));
            }).list();
    }
    private static boolean terminal(Item item) { return List.of("QUARANTINED","REJECTED_FORMAT","FAILED").contains(item.state()); }
    public Batch get(UUID actor,UUID id) {
        var h=header(actor,id); var items=items(h);
        return new Batch(id,h.poolId(),h.createdAt(),h.expiresAt(),h.actorId().equals(actor) && h.expiresAt().isAfter(now()),
            (int)items.stream().filter(BatchImportService::terminal).count(),items.size(),items);
    }
    public Page list(UUID actor,UUID pool,String cursor) {
        imports.policies(actor,pool); // Checks pool membership even if it has no policy.
        OffsetDateTime date=OffsetDateTime.parse("9999-01-01T00:00:00Z"); UUID id=new UUID(-1,-1);
        if (cursor!=null) {
            try { var parts=cursor.split("\\|",-1); if(parts.length!=2) throw new IllegalArgumentException(); date=OffsetDateTime.parse(parts[0]);id=UUID.fromString(parts[1]); }
            catch(RuntimeException invalid) { throw error(HttpStatus.BAD_REQUEST,"INVALID_CURSOR"); }
        }
        var ids=db.sql("SELECT id FROM import_batch WHERE pool_id=:pool AND (created_at<:date OR (created_at=:date AND id<:id)) ORDER BY created_at DESC,id DESC LIMIT 21")
            .param("pool",pool).param("date",date).param("id",id).query(UUID.class).list();
        var result=ids.stream().limit(20).map(batch -> get(actor,batch)).map(b -> new Summary(b.id(),b.createdAt(),b.completedFiles(),b.totalFiles())).toList();
        var last=result.isEmpty() ? null : result.getLast();
        return new Page(result,ids.size()>20 ? last.createdAt()+"|"+last.id() : null);
    }

    public Batch upload(UUID actor,UUID batchId,UUID itemId,MultipartFile file) throws IOException {
        var h=header(actor,batchId);
        if (!h.actorId().equals(actor)) throw error(HttpStatus.FORBIDDEN,"BATCH_OWNER_REQUIRED");
        if (!h.expiresAt().isAfter(now())) throw error(HttpStatus.CONFLICT,"IMPORT_EXPIRED");
        var item=items(h).stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow(() -> error(HttpStatus.NOT_FOUND,"IMPORT_ITEM_NOT_FOUND"));
        if (terminal(item) && !item.state().equals("QUARANTINED")) return get(actor,batchId);
        // Bind content to its reserved slot BEFORE changing state. A wrong reselection cannot poison the slot.
        if (!item.fileName().equals(file.getOriginalFilename()) || item.byteSize()!=file.getSize()) throw error(HttpStatus.CONFLICT,"FILE_FINGERPRINT_MISMATCH");
        var digest=ReceptionValidator.sha(); long size=0;
        try(var in=file.getInputStream()) {
            byte[] buffer=new byte[8192];
            for(int n;(n=in.read(buffer))!=-1;) { size+=n;if(size>ReceptionValidator.MAX_BYTES) throw error(HttpStatus.PAYLOAD_TOO_LARGE,"FILE_TOO_LARGE");digest.update(buffer,0,n); }
        }
        if (size!=item.byteSize() || !HexFormat.of().formatHex(digest.digest()).equals(item.sha256())) throw error(HttpStatus.CONFLICT,"FILE_FINGERPRINT_MISMATCH");
        if (terminal(item)) return get(actor,batchId);
        Claim claim=tx.execute(status -> {
            imports.lockActor(actor,h.poolId());
            var current=items(h).stream().filter(i -> i.id().equals(itemId)).findFirst().orElseThrow();
            if(terminal(current)) return new Claim(null,true);
            if(current.retryAfter()!=null && current.retryAfter().isAfter(now())) throw error(HttpStatus.CONFLICT,"IMPORT_IN_PROGRESS");
            if(current.attempts()>=5) {
                db.sql("UPDATE import_batch_item SET state='FAILED',error_code='ATTEMPTS_EXHAUSTED',lease_token=NULL,lease_until=NULL WHERE id=:id")
                    .param("id",itemId).update();audit(actor,batchId,itemId,"FILE_FAILED","ATTEMPTS_EXHAUSTED");return new Claim(null,true);
            }
            long active=db.sql("SELECT COUNT(*) FROM import_batch_item i JOIN import_batch b ON b.id=i.batch_id WHERE b.actor_id=:actor AND i.lease_until>:now")
                .param("actor",actor).param("now",now()).query(Long.class).single();
            if(active>=2) throw error(HttpStatus.TOO_MANY_REQUESTS,"IMPORT_BUSY");
            UUID token=UUID.randomUUID();
            db.sql("UPDATE import_batch_item SET state='UPLOADING',error_code=NULL,attempts=attempts+1,lease_token=:token,lease_until=:until WHERE id=:id")
                .param("token",token).param("until",now().plusMinutes(2)).param("id",itemId).update();
            audit(actor,batchId,itemId,"UPLOAD_STARTED",null); return new Claim(token,false);
        });
        if(claim.done()) return get(actor,batchId);
        try(var received=validator.receive(file)) {
            var basis=new ImportService.Basis(h.source(),h.purpose(),h.basisCode(),h.noticeVersion(),h.policyId());
            var policy=new ImportService.Policy(h.policyId(),h.policyLabel(),h.purpose(),h.basisCode(),h.retentionDays());
            imports.createForBatch(actor,h.poolId(),"batch-"+itemId,basis,received,policy,h.expiresAt());
            finish(actor,h,itemId,claim.token(),"QUARANTINED",null);
        } catch(Rejected rejected) {
            if(rejected.status==HttpStatus.FORBIDDEN || rejected.status==HttpStatus.NOT_FOUND) throw rejected;
            boolean invalid=rejected.status==HttpStatus.UNSUPPORTED_MEDIA_TYPE || rejected.status==HttpStatus.PAYLOAD_TOO_LARGE;
            finish(actor,h,itemId,claim.token(),invalid ? "REJECTED_FORMAT" : "RETRYABLE_FAILURE",rejected.code);
        } catch(IOException unavailable) {
            finish(actor,h,itemId,claim.token(),"RETRYABLE_FAILURE","RECEPTION_UNAVAILABLE");
        }
        return get(actor,batchId);
    }
    private void finish(UUID actor,Header h,UUID item,UUID token,String state,String errorCode) {
        tx.executeWithoutResult(status -> {
            imports.lockActor(actor,h.poolId());
            int updated=db.sql("""
                UPDATE import_batch_item SET state=CASE WHEN :state='RETRYABLE_FAILURE' AND attempts>=5 THEN 'FAILED' ELSE :state END,
                error_code=:error,lease_token=NULL,lease_until=NULL WHERE id=:id AND lease_token=:token
                """).param("state",state).param("error",errorCode).param("id",item).param("token",token).update();
            if(updated==1) audit(actor,h.id(),item,"UPLOAD_FINISHED",errorCode);
        });
    }
    private void audit(UUID actor,UUID batch,UUID item,String action,String errorCode) {
        db.sql("INSERT INTO import_batch_event(id,batch_id,item_id,actor_id,action,error_code) VALUES (:id,:batch,:item,:actor,:action,:error)")
            .param("id",UUID.randomUUID()).param("batch",batch).param("item",item).param("actor",actor).param("action",action).param("error",errorCode).update();
    }
}
