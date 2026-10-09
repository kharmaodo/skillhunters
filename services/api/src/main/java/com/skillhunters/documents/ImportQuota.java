package com.skillhunters.documents;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import com.skillhunters.shared.ApiProblem.Rejected;

@Component
public class ImportQuota {
    private final JdbcClient db;
    private final long maxFiles;
    private final long maxBytes;
    public ImportQuota(JdbcClient db, @Value("${app.imports.max-files-per-user:1000}") long maxFiles,
            @Value("${app.imports.max-bytes-per-user:3145728000}") long maxBytes) {
        this.db = db; this.maxFiles = maxFiles; this.maxBytes = maxBytes;
    }
    /** Caller holds app_user FOR UPDATE. Reserved batch slots count even before upload. */
    public void check(UUID actor, long addedFiles, long addedBytes) {
        String standalone = "FROM document_import d WHERE actor_id=:actor AND NOT EXISTS (SELECT 1 FROM import_batch_item i JOIN import_batch b ON b.id=i.batch_id WHERE b.actor_id=d.actor_id AND b.pool_id=d.pool_id AND d.idempotency_key=CONCAT('batch-',CAST(i.id AS VARCHAR)))";
        String batch = "FROM import_batch_item i JOIN import_batch b ON b.id=i.batch_id WHERE b.actor_id=:actor";
        long count = db.sql("SELECT COUNT(*) " + standalone).param("actor",actor).query(Long.class).single()
            + db.sql("SELECT COUNT(*) " + batch).param("actor",actor).query(Long.class).single();
        long bytes = db.sql("SELECT COALESCE(SUM(byte_size),0) " + standalone).param("actor",actor).query(Long.class).single()
            + db.sql("SELECT COALESCE(SUM(byte_size),0) " + batch).param("actor",actor).query(Long.class).single();
        if (count + addedFiles > maxFiles || bytes + addedBytes > maxBytes) throw new Rejected(HttpStatus.TOO_MANY_REQUESTS,"IMPORT_QUOTA_REACHED");
    }
}
