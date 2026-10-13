package com.skillhunters;

import com.skillhunters.documents.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ExtractionTest extends IdentityTestSupport {
    @Autowired AnalysisService analysis;
    @Autowired ExtractionService extraction;
    @Autowired TestQuarantineStorage storage;
    @MockitoBean DocumentAnalyzer analyzer;
    UUID id;
    String url() { return "/api/v1/imports/" + id + "/extraction"; }
    @BeforeEach void document() throws Exception {
        storage.objects.clear(); storage.fail=false;
        UUID policy=UUID.randomUUID();
        db.update("INSERT INTO import_policy(id,label,purpose,basis_code,retention_days) VALUES (?,'Synthetic','Test','TEST_ONLY',7)",policy);
        mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("files","synthetic.md","text/markdown","Compétence : Java 2,5".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("basis","","application/json",("{\"source\":\"Synthetic\",\"purpose\":\"Test\",\"basisCode\":\"TEST_ONLY\",\"retentionPolicyId\":\""+policy+"\"}").getBytes()))
            .param("poolId",poolA.toString()).header("Idempotency-Key","extraction-test-key").with(as("alice")).with(csrf()))
            .andExpect(status().isAccepted());
        id=db.queryForObject("SELECT id FROM document_import",UUID.class);
        when(analyzer.analyze(any(),any(),any())).thenReturn(new DocumentAnalyzer.Result("EXTRACTED",null,
            List.of(new DocumentAnalyzer.Block("document","Compétence : Java 2,5")),List.of(),"sh08-test","ClamAV synthetic"));
    }
    @Test void requiresCompletedAnalysisAndCsrfAndMembershipEvenForAdmin() throws Exception {
        mvc.perform(post(url()).with(as("alice")).with(csrf())).andExpect(status().isConflict());
        analysis.runOnce();
        mvc.perform(post(url()).with(as("alice"))).andExpect(status().isForbidden());
        mvc.perform(post(url()).with(as("admin")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get(url()).with(as("admin"))).andExpect(status().isNotFound());
        mvc.perform(get(url()).with(as("alice"))).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM extraction_revision",Integer.class)).isZero();
    }
    @Test void preparationIsIdempotentWithExactSnapshotAndAuditedRead() throws Exception {
        analysis.runOnce();
        var first=extraction.prepare(alice,id);
        assertThat(extraction.prepare(alice,id)).isEqualTo(first);
        mvc.perform(get(url()).with(as("alice"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.proposal.status").value("NEEDS_REVIEW"))
            .andExpect(jsonPath("$.proposal.claims[1].normalized").value("2.5"))
            .andExpect(jsonPath("$.proposal.claims[1].evidence[0].quote").value("Compétence : Java 2,5"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM extraction_revision",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT source_json FROM extraction_revision",String.class)).isEqualTo(db.queryForObject("SELECT result_json FROM document_analysis",String.class));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_audit WHERE action='EXTRACTION_PREPARED'",Integer.class)).isEqualTo(1);
        assertThat(first.documentSha256()).hasSize(64);
    }
    @Test void revokedExpiredAndDeletedSourcesCannotExposeOrRecreateRevisions() throws Exception {
        analysis.runOnce();extraction.prepare(alice,id);
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        mvc.perform(get(url()).with(as("alice"))).andExpect(status().isNotFound());
        mvc.perform(post(url()).with(as("alice")).with(csrf())).andExpect(status().isNotFound());
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",alice,poolA);
        db.update("UPDATE document_import SET expires_at=created_at");
        mvc.perform(get(url()).with(as("alice"))).andExpect(status().isConflict());
        db.update("DELETE FROM document_audit");db.update("DELETE FROM document_import");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM extraction_revision",Integer.class)).isZero();
        mvc.perform(post(url()).with(as("alice")).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void replacementAnalysisCreatesNewRevisionAndRetainsOldEvidence() {
        analysis.runOnce();var first=extraction.prepare(alice,id);
        db.update("UPDATE document_analysis SET state='FAILED'");analysis.retry(alice,id);
        var job=analysis.claim();
        analysis.complete(job,new DocumentAnalyzer.Result("EXTRACTED",null,List.of(new DocumentAnalyzer.Block("page:1","Compétence : Java 3")),List.of(),"new-parser","ClamAV synthetic"));
        var second=extraction.prepare(alice,id);
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.analysisSha256()).isNotEqualTo(first.analysisSha256());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM extraction_revision",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT revision_json FROM extraction_revision WHERE id=?",String.class,first.id())).contains("2.5");
    }
}
