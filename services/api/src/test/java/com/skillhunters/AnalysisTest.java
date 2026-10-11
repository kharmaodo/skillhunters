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

class AnalysisTest extends IdentityTestSupport {
    @Autowired AnalysisService analysis;
    @Autowired TestQuarantineStorage storage;
    @MockitoBean DocumentAnalyzer analyzer;
    UUID id;
    DocumentAnalyzer.Result success() { return new DocumentAnalyzer.Result("EXTRACTED",null,List.of(new DocumentAnalyzer.Block("document","Synthetic Java 2.5")),List.of(),"test-1","ClamAV test/123/timestamp"); }
    @BeforeEach void document() throws Exception {
        storage.objects.clear();storage.fail=false;
        UUID policy=UUID.randomUUID();
        db.update("INSERT INTO import_policy(id,label,purpose,basis_code,retention_days) VALUES (?,'Synthetic','Test','TEST_ONLY',7)",policy);
        mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("files","synthetic.md","text/markdown","# Synthetic".getBytes()))
            .file(new MockMultipartFile("basis","","application/json",("{\"source\":\"Synthetic\",\"purpose\":\"Test\",\"basisCode\":\"TEST_ONLY\",\"retentionPolicyId\":\""+policy+"\"}").getBytes()))
            .param("poolId",poolA.toString()).header("Idempotency-Key","analysis-test-key").with(as("alice")).with(csrf()))
            .andExpect(status().isAccepted());
        id=db.queryForObject("SELECT id FROM document_import",UUID.class);
        reset(analyzer);
    }
    @Test void queuedAtomicallyAndExtractedResultIsScopedAndAudited() throws Exception {
        assertThat(analysis.get(alice,id).state()).isEqualTo("PENDING");
        when(analyzer.analyze(any(),any(),any())).thenReturn(success());analysis.runOnce();
        mvc.perform(get("/api/v1/imports/"+id+"/analysis").with(as("alice"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("EXTRACTED")).andExpect(jsonPath("$.result.blocks[0].text").value("Synthetic Java 2.5"));
        mvc.perform(get("/api/v1/imports/"+id+"/analysis").with(as("admin"))).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_analysis_event",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT state FROM document_import",String.class)).isEqualTo("QUARANTINED");
    }
    @Test void unavailableWorkerRetriesThreeTimesThenNeedsCsrfProtectedHumanRetry() throws Exception {
        when(analyzer.analyze(any(),any(),any())).thenThrow(new IllegalStateException());
        for(int i=0;i<3;i++) { db.update("UPDATE document_analysis SET available_at=CURRENT_TIMESTAMP");analysis.runOnce(); }
        assertThat(analysis.get(alice,id).state()).isEqualTo("FAILED");assertThat(analysis.get(alice,id).result()).isNull();
        mvc.perform(post("/api/v1/imports/"+id+"/analysis/retry").with(as("alice"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/imports/"+id+"/analysis/retry").with(as("admin")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/imports/"+id+"/analysis/retry").with(as("alice")).with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"));
    }
    @Test void revokedAccessAndExpiredSourceDiscardInFlightText() {
        var job=analysis.claim();db.update("DELETE FROM membership WHERE user_id=?",alice);analysis.complete(job,success());
        assertThat(db.queryForObject("SELECT state FROM document_analysis",String.class)).isEqualTo("CANCELLED");
        assertThat(db.queryForObject("SELECT result_json FROM document_analysis",String.class)).isNull();
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",alice,poolA);
        analysis.retry(alice,id);job=analysis.claim();db.update("UPDATE document_import SET expires_at=created_at");analysis.complete(job,success());
        assertThat(db.queryForObject("SELECT state FROM document_analysis",String.class)).isEqualTo("CANCELLED");
    }
    @Test void expiredLeaseIsReclaimedAndOldResultCannotOverwriteNewAttempt() {
        var first=analysis.claim();assertThat(analysis.claim()).isNull();
        db.update("UPDATE document_analysis SET lease_until=CURRENT_TIMESTAMP");
        var second=analysis.claim();assertThat(second.token()).isNotEqualTo(first.token());
        analysis.complete(first,success());assertThat(analysis.get(alice,id).state()).isEqualTo("RUNNING");
        analysis.complete(second,success());assertThat(analysis.get(alice,id).state()).isEqualTo("EXTRACTED");
        analysis.complete(first,new DocumentAnalyzer.Result("REJECTED","ANTIVIRUS_BLOCKED",List.of(),List.of(),null,null));
        assertThat(analysis.get(alice,id).state()).isEqualTo("EXTRACTED");
    }
    @Test void sourceDeletionDoesNotResurrectWorkerResult() {
        var job=analysis.claim();db.update("DELETE FROM document_audit");db.update("DELETE FROM document_import");analysis.complete(job,success());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_analysis",Integer.class)).isZero();
    }
    @Test void corruptObjectNeverReachesAntivirusOrParser() throws Exception {
        storage.objects.replaceAll((key,value)->"corrupt".getBytes());analysis.runOnce();
        verifyNoInteractions(analyzer);assertThat(analysis.get(alice,id).code()).isEqualTo("INTEGRITY_MISMATCH");
    }
    @Test void forgedSuccessWithoutAntivirusIsNotPublished() throws Exception {
        when(analyzer.analyze(any(),any(),any())).thenReturn(new DocumentAnalyzer.Result("EXTRACTED",null,List.of(),List.of(),"test",null));
        analysis.runOnce();assertThat(analysis.get(alice,id).state()).isEqualTo("RETRY");assertThat(analysis.get(alice,id).result()).isNull();
    }
    @Test void securityRejectionCannotBeManuallyBypassed() throws Exception {
        when(analyzer.analyze(any(),any(),any())).thenReturn(new DocumentAnalyzer.Result("REJECTED","ANTIVIRUS_BLOCKED",List.of(),List.of(),"test","ClamAV test"));
        analysis.runOnce();mvc.perform(post("/api/v1/imports/"+id+"/analysis/retry").with(as("alice")).with(csrf())).andExpect(status().isConflict());
    }
    @Test void revokedRequestDoesNotStartWorker() {
        db.update("DELETE FROM membership WHERE user_id=?",alice);analysis.runOnce();verifyNoInteractions(analyzer);
        assertThat(db.queryForObject("SELECT state FROM document_analysis",String.class)).isEqualTo("CANCELLED");
    }
}
