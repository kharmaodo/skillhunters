package com.skillhunters;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class ImportSecurityTest extends IdentityTestSupport {
    @Autowired TestQuarantineStorage storage;
    @Autowired com.skillhunters.documents.ImportService imports;
    final UUID policy = UUID.fromString("30000000-0000-0000-0000-000000000001");
    @BeforeEach void policy() {
        storage.objects.clear(); storage.fail = false;
        db.update("INSERT INTO import_policy(id,label,purpose,basis_code,retention_days) VALUES (?,'Synthetic only','Test','TEST_ONLY',7)", policy);
    }
    MockMultipartHttpServletRequestBuilder upload(UUID pool, String key, String text) {
        var basis = new MockMultipartFile("basis", "", "application/json", ("{\"source\":\"Synthetic fixture\",\"purpose\":\"Test\",\"basisCode\":\"TEST_ONLY\",\"retentionPolicyId\":\""+policy+"\"}").getBytes(StandardCharsets.UTF_8));
        var request = multipart("/api/v1/imports").file(new MockMultipartFile("files","fixture.md","text/markdown",text.getBytes(StandardCharsets.UTF_8)))
            .file(basis);
        request.param("poolId",pool.toString()).header("Idempotency-Key",key);
        return request;
    }
    String accept() throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().readTree(mvc.perform(upload(poolA,"replay-key-1","# Synthetic CV").with(as("alice")).with(csrf()))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.items[0].state").value("QUARANTINED"))
            .andExpect(jsonPath("$.objectKey").doesNotExist()).andReturn().getResponse().getContentAsString()).get("id").asText();
    }
    @Test void acceptedImportIsPrivateAuditedAndReplayDoesNotDuplicate() throws Exception {
        String id = accept(); assertThat(accept()).isEqualTo(id);
        assertThat(storage.objects).hasSize(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_import",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_audit",Integer.class)).isEqualTo(2);
        mvc.perform(get("/api/v1/imports/"+id).with(as("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.items[0].sha256").isNotEmpty());
        mvc.perform(get("/api/v1/imports/"+id).with(as("admin"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/imports/"+UUID.randomUUID()).with(as("admin"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/imports/"+id+"/download").with(as("alice"))).andExpect(status().isNotFound());
        mvc.perform(upload(poolA,"replay-key-1","Different file").with(as("alice")).with(csrf()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }
    @Test void permissionAndCsrfAreRequiredBeforeStorageAndRevocationAppliesToHistory() throws Exception {
        mvc.perform(upload(poolB,"denied-001","test").with(as("alice")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(upload(poolA,"denied-002","test").with(as("admin")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(upload(poolA,"denied-003","test").with(as("alice"))).andExpect(status().isForbidden());
        assertThat(storage.objects).isEmpty();
        String id = accept();
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        mvc.perform(get("/api/v1/imports/"+id).with(as("alice"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/pools/"+poolA+"/imports").with(as("alice"))).andExpect(status().isNotFound());
        mvc.perform(upload(poolA,"replay-key-1","# Synthetic CV").with(as("alice")).with(csrf())).andExpect(status().isNotFound());
    }
    @Test void unavailableStorageNeverClaimsSuccessAndSameKeyResumes() throws Exception {
        storage.fail = true;
        mvc.perform(upload(poolA,"replay-key-1","# Synthetic CV").with(as("alice")).with(csrf()))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("QUARANTINE_UNAVAILABLE"));
        assertThat(db.queryForObject("SELECT state FROM document_import",String.class)).isEqualTo("RECEIVING");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_audit",Integer.class)).isEqualTo(1);
        storage.fail = false; accept();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_import",Integer.class)).isEqualTo(1);
    }
    @Test void resumeSingleReceiptWithoutOldBrowserKey() throws Exception {
        storage.fail=true;
        mvc.perform(upload(poolA,"old-browser-key","# Synthetic CV").with(as("alice")).with(csrf())).andExpect(status().isServiceUnavailable());
        UUID id=db.queryForObject("SELECT id FROM document_import",UUID.class); storage.fail=false;
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT,"/api/v1/imports/"+id+"/content")
            .file(new MockMultipartFile("file","fixture.md","text/markdown","# Synthetic CV".getBytes())).with(as("alice")).with(csrf()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].state").value("QUARANTINED"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_import",Integer.class)).isEqualTo(1);
    }
    @Test void disabledPolicyAndChangedPolicyCannotBeInventedByClient() throws Exception {
        db.update("UPDATE import_policy SET enabled=FALSE");
        mvc.perform(upload(poolA,"policy-key","test").with(as("alice")).with(csrf())).andExpect(status().isBadRequest());
        assertThat(storage.objects).isEmpty();
        db.update("UPDATE import_policy SET enabled=TRUE,purpose='Other'");
        mvc.perform(upload(poolA,"policy-key","test").with(as("alice")).with(csrf())).andExpect(status().isBadRequest());
    }
    @Test void importsArePagedAndIsolated() throws Exception {
        for (int i=0;i<22;i++) mvc.perform(upload(poolA,"page-key-"+i,"Synthetic "+i).with(as("alice")).with(csrf())).andExpect(status().isAccepted());
        var first = new com.fasterxml.jackson.databind.ObjectMapper().readTree(mvc.perform(get("/api/v1/pools/"+poolA+"/imports").with(as("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20)).andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/v1/pools/"+poolA+"/imports").param("cursor",first.get("nextCursor").asText()).with(as("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/api/v1/pools/"+poolA+"/imports").with(as("admin"))).andExpect(status().isNotFound());
    }
    @Test void concurrentSameKeyProducesOneReceiptAndOneAcceptanceAudit() throws Exception {
        var validator = new com.skillhunters.documents.ReceptionValidator();
        var basis = new com.skillhunters.documents.ImportService.Basis("Synthetic fixture","Test","TEST_ONLY",null,policy);
        try (var one = validator.receive(new MockMultipartFile("files","same.md",null,"# Same".getBytes()));
             var two = validator.receive(new MockMultipartFile("files","same.md",null,"# Same".getBytes()));
             var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> imports.create(alice,poolA,"concurrent-key",basis,one));
            var second = executor.submit(() -> imports.create(alice,poolA,"concurrent-key",basis,two));
            assertThat(first.get(20,java.util.concurrent.TimeUnit.SECONDS).id()).isEqualTo(second.get(20,java.util.concurrent.TimeUnit.SECONDS).id());
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_import",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_audit WHERE action='QUARANTINE_ACCEPTED'",Integer.class)).isEqualTo(1);
    }
    @Test void manyFilesAndInvalidKeyAreRejected() throws Exception {
        mvc.perform(upload(poolA,"valid-key","test").file(new MockMultipartFile("files","second.md","text/markdown",new byte[]{65})).with(as("alice")).with(csrf()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SINGLE_FILE_REQUIRED"));
        mvc.perform(upload(poolA,"short","test").with(as("alice")).with(csrf())).andExpect(status().isBadRequest());
        assertThat(storage.objects).isEmpty();
    }
}
