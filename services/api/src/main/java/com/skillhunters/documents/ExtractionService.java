package com.skillhunters.documents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExtractionService {
    public record Revision(UUID id, UUID importId, String documentSha256, String analysisSha256,
                           String parserVersion, String avVersion, UUID createdBy, OffsetDateTime createdAt,
                           StructuredExtractor.Proposal proposal) {}
    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final ImportService imports;
    private final StructuredExtractor extractor;
    private final ObjectMapper json;
    public ExtractionService(JdbcClient db, TransactionTemplate tx, ImportService imports, StructuredExtractor extractor, ObjectMapper json) {
        this.db = db; this.tx = tx; this.imports = imports; this.extractor = extractor; this.json = json;
    }
    public Revision get(UUID actor, UUID id) { return access(actor, id, false); }
    public Revision prepare(UUID actor, UUID id) { return access(actor, id, true); }
    private Revision access(UUID actor, UUID id, boolean create) {
        return tx.execute(status -> {
            var receipt = imports.get(actor, id);
            imports.lockActor(actor, receipt.poolId());
            // Serialize with completion/retry. The original is immutable; deletion cascades to revisions.
            var source = db.sql("SELECT state,result_json FROM document_analysis WHERE import_id=:id FOR UPDATE")
                .param("id", id).query((rs,n) -> new String[]{rs.getString(1),rs.getString(2)}).optional();
            if (source.isEmpty() || !"EXTRACTED".equals(source.get()[0]) || source.get()[1] == null)
                throw new Rejected(HttpStatus.CONFLICT, "ANALYSIS_NOT_EXTRACTED");
            live(id);
            String snapshot = source.get()[1];
            String hash = HexFormat.of().formatHex(ReceptionValidator.sha().digest(snapshot.getBytes(StandardCharsets.UTF_8)));
            var saved = db.sql("SELECT revision_json FROM extraction_revision WHERE import_id=:id AND analysis_sha256=:hash AND rules_version=:rules")
                .param("id", id).param("hash", hash).param("rules", StructuredExtractor.RULES).query(String.class).optional();
            Revision revision;
            if (saved.isPresent()) revision = read(saved.get(), Revision.class);
            else {
                if (!create) throw new Rejected(HttpStatus.NOT_FOUND, "EXTRACTION_NOT_PREPARED");
                var analysis = read(snapshot, DocumentAnalyzer.Result.class);
                if (!"EXTRACTED".equals(analysis.state()) || analysis.avVersion() == null || !analysis.avVersion().startsWith("ClamAV "))
                    throw new Rejected(HttpStatus.CONFLICT, "ANALYSIS_NOT_EXTRACTED");
                var proposal = extractor.extract(analysis.blocks());
                revision = new Revision(UUID.randomUUID(), id, receipt.items().getFirst().sha256(), hash,
                    analysis.parserVersion(), analysis.avVersion(), actor, OffsetDateTime.now(ZoneOffset.UTC), proposal);
                live(id); // No result publication after expiry, including during computation.
                db.sql("""
                    INSERT INTO extraction_revision(id,import_id,analysis_sha256,rules_version,source_json,revision_json)
                    VALUES (:revision,:id,:hash,:rules,:source,:result)
                    """).param("revision", revision.id()).param("id", id).param("hash", hash).param("rules", StructuredExtractor.RULES)
                    .param("source", snapshot).param("result", write(revision)).update();
                audit(actor, id, "EXTRACTION_PREPARED");
            }
            audit(actor, id, "EXTRACTION_READ");
            return revision;
        });
    }
    private void live(UUID id) {
        if (db.sql("SELECT COUNT(*) FROM document_import WHERE id=:id AND state='QUARANTINED' AND expires_at>:now")
                .param("id", id).param("now", OffsetDateTime.now(ZoneOffset.UTC)).query(Integer.class).single() != 1)
            throw new Rejected(HttpStatus.CONFLICT, "IMPORT_EXPIRED");
    }
    private void audit(UUID actor, UUID id, String action) {
        db.sql("INSERT INTO document_audit(id,import_id,actor_id,action) VALUES (:event,:id,:actor,:action)")
            .param("event", UUID.randomUUID()).param("id", id).param("actor", actor).param("action", action).update();
    }
    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception invalid) { throw new IllegalStateException("INVALID_STORED_EXTRACTION"); }
    }
    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalStateException("EXTRACTION_SERIALIZATION"); }
    }
}
