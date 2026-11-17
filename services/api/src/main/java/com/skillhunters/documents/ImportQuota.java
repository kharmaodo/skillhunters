package com.skillhunters.documents;

import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import com.skillhunters.shared.ApiProblem.Rejected;

@Component
public class ImportQuota {
    private final JdbcClient db;
    public ImportQuota(JdbcClient db) {this.db=db;}
    /** Caller holds app_user FOR UPDATE. Reserved batch slots count even before upload. */
    public void check(UUID actor, long addedFiles, long addedBytes, com.skillhunters.settings.ImportSettings.Version rules) {
        String standalone = "FROM document_import d WHERE actor_id=:actor AND NOT EXISTS (SELECT 1 FROM import_batch_item i JOIN import_batch b ON b.id=i.batch_id WHERE b.actor_id=d.actor_id AND b.pool_id=d.pool_id AND d.idempotency_key=CONCAT('batch-',CAST(i.id AS VARCHAR)))";
        String batch = "FROM import_batch_item i JOIN import_batch b ON b.id=i.batch_id WHERE b.actor_id=:actor";
        long count = db.sql("SELECT COUNT(*) " + standalone).param("actor",actor).query(Long.class).single()
            + db.sql("SELECT COUNT(*) " + batch).param("actor",actor).query(Long.class).single();
        long bytes = db.sql("SELECT COALESCE(SUM(byte_size),0) " + standalone).param("actor",actor).query(Long.class).single()
            + db.sql("SELECT COALESCE(SUM(byte_size),0) " + batch).param("actor",actor).query(Long.class).single();
        if (count + addedFiles > rules.maxFilesPerUser() || bytes + addedBytes > rules.maxBytesPerUser()) throw new Rejected(HttpStatus.TOO_MANY_REQUESTS,"IMPORT_QUOTA_REACHED");
    }
}
