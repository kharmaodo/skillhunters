package com.skillhunters.documents;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.skillhunters.identity.IdentityStore;
import com.skillhunters.shared.ApiProblem.Rejected;

@Service
public class ImportService {
    public record Basis(@NotBlank @Size(max=200) String source, @NotBlank @Size(max=200) String purpose,
            @NotBlank @Size(max=80) String basisCode, @Size(max=80) String noticeVersion, @NotNull UUID retentionPolicyId) {}
    public record Policy(UUID id, String label, String purpose, String basisCode, int retentionDays) {}
    public record Item(UUID id, String fileName, String state, String mediaType, long byteSize, String sha256) {}
    public record Receipt(UUID id, UUID poolId, OffsetDateTime createdAt, OffsetDateTime expiresAt,
            String validationVersion, List<Item> items) {}
    public record Page(List<Receipt> items, String nextCursor) {}
    private final JdbcClient db;
    private final IdentityStore identity;
    private final TransactionTemplate tx;
    private final QuarantineStorage storage;
    private final ImportQuota quota;

    public ImportService(JdbcClient db, IdentityStore identity, TransactionTemplate tx, QuarantineStorage storage, ImportQuota quota) {
        this.quota = quota; this.db = db; this.identity = identity; this.tx = tx; this.storage = storage;
    }

    public List<Policy> policies(UUID actor, UUID pool) {
        identity.pool(actor, pool);
        return db.sql("SELECT id,label,purpose,basis_code,retention_days FROM import_policy WHERE enabled=TRUE ORDER BY label,id")
            .query(Policy.class).list();
    }

    /** Lock shares the same actor row as membership replacement: no revocation/write race. */
    void lockActor(UUID actor, UUID pool) {
        var enabled = db.sql("SELECT enabled FROM app_user WHERE id=:id FOR UPDATE").param("id", actor).query(Boolean.class).optional();
        if (!enabled.orElse(false)) throw new Rejected(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        identity.pool(actor, pool);
    }

    public Receipt create(UUID actor, UUID pool, String key, Basis basis, ReceptionValidator.Received file) {
        if (key != null && key.startsWith("batch-")) throw new Rejected(HttpStatus.BAD_REQUEST, "RESERVED_IDEMPOTENCY_KEY");
        return create(actor, pool, key, basis, file, null, null);
    }

    Receipt createForBatch(UUID actor, UUID pool, String key, Basis basis, ReceptionValidator.Received file,
            Policy policy, OffsetDateTime expiresAt) {
        return create(actor, pool, key, basis, file, policy, expiresAt);
    }

    private Receipt create(UUID actor, UUID pool, String key, Basis basis, ReceptionValidator.Received file,
            Policy frozenPolicy, OffsetDateTime batchExpiry) {
        if (key == null || !key.matches("[A-Za-z0-9_-]{8,128}")) throw new Rejected(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY");
        // Length-prefixed fields avoid ambiguity; actor/pool form the DB uniqueness scope.
        String[] fields = {file.sha256(), file.name(), file.mediaType(), basis.source(), basis.purpose(),
            basis.basisCode(), Objects.toString(basis.noticeVersion(), ""), basis.retentionPolicyId().toString()};
        var fingerprint = new StringBuilder();
        for (String field : fields) fingerprint.append(field.length()).append(':').append(field);
        String hash = HexFormat.of().formatHex(ReceptionValidator.sha().digest(fingerprint.toString().getBytes(StandardCharsets.UTF_8)));
        UUID id = tx.execute(status -> {
            lockActor(actor, pool);
            var existing = db.sql("SELECT id,request_hash FROM document_import WHERE actor_id=:actor AND pool_id=:pool AND idempotency_key=:key")
                .param("actor", actor).param("pool", pool).param("key", key)
                .query((rs, n) -> {
                    if (!hash.equals(rs.getString("request_hash"))) throw new Rejected(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
                    return rs.getObject("id", UUID.class);
                }).optional();
            if (existing.isPresent()) return existing.get();
            var policy = frozenPolicy != null ? frozenPolicy : policies(actor, pool).stream().filter(p -> p.id().equals(basis.retentionPolicyId())).findFirst()
                .orElseThrow(() -> new Rejected(HttpStatus.BAD_REQUEST, "IMPORT_POLICY_UNAVAILABLE"));
            if (!policy.purpose().equals(basis.purpose()) || !policy.basisCode().equals(basis.basisCode())) {
                throw new Rejected(HttpStatus.BAD_REQUEST, "IMPORT_POLICY_MISMATCH");
            }
            if (frozenPolicy == null) quota.check(actor, 1, file.size());
            UUID created = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            db.sql("""
                INSERT INTO document_import(id,pool_id,actor_id,idempotency_key,request_hash,object_key,file_name,
                media_type,byte_size,sha256,source,purpose,basis_code,notice_version,policy_id,retention_days,
                created_at,expires_at,state,validation_version)
                VALUES (:id,:pool,:actor,:key,:hash,:object,:name,:type,:size,:sha,:source,:purpose,:basis,:notice,
                :policy,:days,:now,:expires,'RECEIVING',:version)
                """)
                .param("id", created).param("pool", pool).param("actor", actor).param("key", key).param("hash", hash)
                .param("object", "quarantine/" + pool + "/" + created).param("name", file.name())
                .param("type", file.mediaType()).param("size", file.size()).param("sha", file.sha256())
                .param("source", basis.source()).param("purpose", policy.purpose()).param("basis", policy.basisCode())
                .param("notice", basis.noticeVersion()).param("policy", policy.id()).param("days", policy.retentionDays())
                .param("now", now).param("expires", batchExpiry != null ? batchExpiry : now.plusDays(policy.retentionDays())).param("version", ReceptionValidator.VERSION).update();
            audit(created, actor, "RECEPTION_RESERVED");
            return created;
        });
        // The durable intent precedes S3. A crash or timeout leaves a tracked RECEIVING object;
        // replaying the same key/hash resumes it and can never mark an unwritten object quarantined.
        return tx.execute(status -> {
            lockActor(actor, pool);
            var row = db.sql("SELECT object_key,state,expires_at FROM document_import WHERE id=:id FOR UPDATE").param("id", id)
                .query((rs,n) -> new Stored(rs.getString("object_key"), rs.getString("state"), rs.getObject("expires_at", OffsetDateTime.class))).single();
            if (row.expiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) throw new Rejected(HttpStatus.CONFLICT, "IMPORT_EXPIRED");
            if (row.state().equals("RECEIVING")) {
                storage.put(row.objectKey(), file.path());
                db.sql("UPDATE document_import SET state='QUARANTINED' WHERE id=:id").param("id", id).update();
                audit(id, actor, "QUARANTINE_ACCEPTED");
            }
            return get(actor, id);
        });
    }
    public Receipt resume(UUID actor, UUID id, ReceptionValidator.Received file) {
        get(actor, id); // Same 404 for absent and inaccessible objects.
        var saved = db.sql("SELECT * FROM document_import WHERE id=:id AND actor_id=:actor").param("id",id).param("actor",actor)
            .query((rs,n) -> new Resume(rs.getObject("pool_id",UUID.class),rs.getString("idempotency_key"),
                rs.getString("file_name"),rs.getString("sha256"),rs.getLong("byte_size"),rs.getString("media_type"),
                new Basis(rs.getString("source"),rs.getString("purpose"),rs.getString("basis_code"),rs.getString("notice_version"),rs.getObject("policy_id",UUID.class))))
            .optional().orElseThrow(() -> new Rejected(HttpStatus.FORBIDDEN,"BATCH_OWNER_REQUIRED"));
        if(saved.key().startsWith("batch-")) throw new Rejected(HttpStatus.CONFLICT,"BATCH_RESUME_REQUIRED");
        if(!saved.name().equals(file.name()) || !saved.sha().equals(file.sha256()) || saved.size()!=file.size() || !saved.type().equals(file.mediaType())) {
            throw new Rejected(HttpStatus.CONFLICT,"FILE_FINGERPRINT_MISMATCH");
        }
        return create(actor,saved.pool(),saved.key(),saved.basis(),file);
    }
    private record Resume(UUID pool,String key,String name,String sha,long size,String type,Basis basis) {}

    private record Stored(String objectKey, String state, OffsetDateTime expiresAt) {}

    private void audit(UUID id, UUID actor, String action) {
        db.sql("INSERT INTO document_audit(id,import_id,actor_id,action) VALUES (:audit,:id,:actor,:action)")
            .param("audit", UUID.randomUUID()).param("id", id).param("actor", actor).param("action", action).update();
    }
    private static Receipt map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new Receipt(id, rs.getObject("pool_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class),
            rs.getObject("expires_at", OffsetDateTime.class), rs.getString("validation_version"),
            List.of(new Item(id, rs.getString("file_name"), rs.getString("state"), rs.getString("media_type"), rs.getLong("byte_size"), rs.getString("sha256"))));
    }
    public Receipt get(UUID actor, UUID id) {
        return db.sql("""
            SELECT d.* FROM document_import d WHERE d.id=:id AND EXISTS
            (SELECT 1 FROM membership m WHERE m.pool_id=d.pool_id AND m.user_id=:actor)
            """).param("id", id).param("actor", actor).query(ImportService::map).optional()
            .orElseThrow(() -> new Rejected(HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND"));
    }
    /** Metadata-only comparison; never interprets a CV or associates candidates. */
    public Page duplicates(UUID actor, UUID sourceId, String cursor) {
        return tx.execute(status -> {
            Receipt source = get(actor, sourceId);
            lockActor(actor, source.poolId());
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            if (!source.expiresAt().isAfter(now) || !source.items().getFirst().state().equals("QUARANTINED")) {
                throw new Rejected(HttpStatus.CONFLICT, "IMPORT_NOT_COMPARABLE");
            }
            OffsetDateTime date = OffsetDateTime.parse("9999-01-01T00:00:00Z");
            UUID id = new UUID(-1L, -1L);
            if (cursor != null) {
                try {
                    String[] parts = cursor.split("\\|", -1);
                    if (parts.length != 2) throw new IllegalArgumentException();
                    date = OffsetDateTime.parse(parts[0]); id = UUID.fromString(parts[1]);
                } catch (RuntimeException invalid) { throw new Rejected(HttpStatus.BAD_REQUEST, "INVALID_CURSOR"); }
            }
            var rows = db.sql("""
                SELECT d.* FROM document_import d WHERE d.pool_id=:pool AND d.sha256=:sha
                AND d.byte_size=:size AND d.id<>:source AND d.state='QUARANTINED' AND d.expires_at>:now
                AND EXISTS (SELECT 1 FROM membership m WHERE m.pool_id=d.pool_id AND m.user_id=:actor)
                AND (d.created_at<:date OR (d.created_at=:date AND d.id<:id))
                ORDER BY d.created_at DESC,d.id DESC LIMIT 21
                """).param("pool", source.poolId()).param("sha", source.items().getFirst().sha256())
                .param("size", source.items().getFirst().byteSize()).param("source", sourceId)
                .param("now", now).param("actor", actor).param("date", date).param("id", id)
                .query(ImportService::map).list();
            var items = rows.stream().limit(20).toList();
            var last = items.isEmpty() ? null : items.getLast();
            return new Page(items, rows.size() > 20 ? last.createdAt() + "|" + last.id() : null);
        });
    }

    public Page list(UUID actor, UUID pool, String cursor) {
        identity.pool(actor, pool);
        OffsetDateTime date = OffsetDateTime.parse("9999-01-01T00:00:00Z");
        UUID id = new UUID(-1L,-1L);
        if (cursor != null) {
            try { String[] parts = cursor.split("\\|", -1); date = OffsetDateTime.parse(parts[0]); id = UUID.fromString(parts[1]); }
            catch (RuntimeException invalid) { throw new Rejected(HttpStatus.BAD_REQUEST, "INVALID_CURSOR"); }
        }
        var rows = db.sql("""
            SELECT * FROM document_import WHERE pool_id=:pool AND
            (created_at<:date OR (created_at=:date AND id<:id)) ORDER BY created_at DESC,id DESC LIMIT 21
            """).param("pool", pool).param("date", date).param("id", id).query(ImportService::map).list();
        var items = rows.stream().limit(20).toList();
        var last = items.isEmpty() ? null : items.getLast();
        return new Page(items, rows.size() > 20 ? last.createdAt() + "|" + last.id() : null);
    }
}
