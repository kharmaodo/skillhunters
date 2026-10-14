package com.skillhunters.exports;

import com.skillhunters.documents.ExtractionService;
import com.skillhunters.talent.CandidateService;
import com.skillhunters.talent.ReviewService;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WordExportService {
    public record Options(boolean canExport,UUID publishedRevisionId,String templateVersion) {}
    public record Receipt(UUID id,UUID candidateId,UUID revisionId,String templateVersion,String templateSha256,String rendererVersion,String sha256,OffsetDateTime createdAt) {}
    public record Download(String filename,byte[] content) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final CandidateService candidates;
    private final ReviewService reviews;private final ExportPermissions permissions;private final WordRenderer renderer;
    public WordExportService(JdbcClient db,TransactionTemplate tx,CandidateService candidates,ReviewService reviews,ExportPermissions permissions,WordRenderer renderer) {
        this.db=db;this.tx=tx;this.candidates=candidates;this.reviews=reviews;this.permissions=permissions;this.renderer=renderer;
    }
    public Options options(UUID actor,UUID id) {
        return tx.execute(s->{var c=candidates.get(actor,id);return new Options(permissions.allowed(actor,c.poolId()),reviews.get(actor,id).publishedRevisionId(),WordRenderer.TEMPLATE);});
    }
    private CandidateService.Candidate authorize(UUID actor,UUID id) {
        var c=candidates.get(actor,id); // Actor lock is retained by the encompassing transaction, including download.
        if(!permissions.allowed(actor,c.poolId())) throw new Rejected(HttpStatus.FORBIDDEN,"EXPORT_PERMISSION_REQUIRED");
        return c;
    }
    public Receipt create(UUID actor,UUID candidate,UUID revisionId) {
        return tx.execute(s->{authorize(actor,candidate);
            if(revisionId==null) throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_EXPORT_REQUEST");
            var state=reviews.get(actor,candidate); // Also locks the candidate against deletion / concurrent publication.
            var revision=reviews.revision(actor,candidate,revisionId);
            if(!"PUBLISHED".equals(revision.status())) throw new Rejected(HttpStatus.CONFLICT,"REVISION_NOT_PUBLISHED");
            var existing=db.sql("SELECT id FROM candidate_export WHERE candidate_id=:candidate AND revision_id=:revision AND template_version=:template AND template_sha256=:hash AND renderer_version=:renderer")
                .param("candidate",candidate).param("revision",revisionId).param("template",WordRenderer.TEMPLATE).param("hash",renderer.templateHash()).param("renderer",WordRenderer.VERSION).query(UUID.class).optional();
            if(existing.isPresent()) return receipt(existing.get());
            ExtractionService.Revision source=state.source();
            if(!source.id().equals(revision.sourceRevisionId())) throw new Rejected(HttpStatus.CONFLICT,"EXPORT_SOURCE_CHANGED");
            byte[] bytes=renderer.render(source,revision);
            if(bytes.length>4*1024*1024) throw new Rejected(HttpStatus.PAYLOAD_TOO_LARGE,"EXPORT_TOO_LARGE");
            authorize(actor,candidate);UUID id=UUID.randomUUID();
            db.sql("INSERT INTO candidate_export(id,candidate_id,revision_id,template_version,template_sha256,renderer_version,sha256,content,created_by) VALUES (:id,:candidate,:revision,:template,:hash,:renderer,:sha,:content,:actor)")
                .param("id",id).param("candidate",candidate).param("revision",revisionId).param("template",WordRenderer.TEMPLATE).param("hash",renderer.templateHash())
                .param("renderer",WordRenderer.VERSION).param("sha",WordRenderer.sha(bytes)).param("content",bytes).param("actor",actor).update();
            audit(actor,id,"GENERATED");return receipt(id);
        });
    }
    public Download download(UUID actor,UUID id) {
        return tx.execute(s->{var r=receipt(id);authorize(actor,r.candidateId());
            byte[] bytes=db.sql("SELECT content FROM candidate_export WHERE id=:id").param("id",id).query((rs,n)->rs.getBytes(1)).single();
            authorize(actor,r.candidateId());audit(actor,id,"DOWNLOAD_PREPARED");
            return new Download("cv-"+id+".docx",bytes);
        });
    }
    private Receipt receipt(UUID id) {
        return db.sql("SELECT id,candidate_id,revision_id,template_version,template_sha256,renderer_version,sha256,created_at FROM candidate_export WHERE id=:id")
            .param("id",id).query((rs,n)->new Receipt(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getObject(8,OffsetDateTime.class)))
            .optional().orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"EXPORT_NOT_FOUND"));
    }
    private void audit(UUID actor,UUID id,String action) {
        db.sql("INSERT INTO candidate_export_event(id,export_id,actor_id,action) VALUES (:event,:id,:actor,:action)")
            .param("event",UUID.randomUUID()).param("id",id).param("actor",actor).param("action",action).update();
    }
}
