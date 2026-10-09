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
    @Test void duplicatesStayWithinPoolAndNeverUseNamesOrChangeReceipts() throws Exception {
        String source = accept();
        mvc.perform(upload(poolA,"duplicate-key","# Synthetic CV").with(as("alice")).with(csrf())).andExpect(status().isAccepted());
        mvc.perform(upload(poolA,"different-key","# Other CV").with(as("alice")).with(csrf())).andExpect(status().isAccepted());
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",alice,poolB);
        mvc.perform(upload(poolB,"hidden-duplicate","# Synthetic CV").with(as("alice")).with(csrf())).andExpect(status().isAccepted());
        // Even membership in both pools must not broaden this comparison's scope.
        mvc.perform(get("/api/v1/imports/"+source+"/duplicates").with(as("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].poolId").value(poolA.toString()))
            .andExpect(jsonPath("$.items[0].items[0].state").value("QUARANTINED"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_import",Integer.class)).isEqualTo(4);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_audit",Integer.class)).isEqualTo(8);
        mvc.perform(get("/api/v1/imports/"+source+"/duplicates").with(as("admin"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/imports/"+UUID.randomUUID()+"/duplicates").with(as("admin"))).andExpect(status().isNotFound());
        db.update("DELETE FROM membership WHERE user_id=? AND pool_id=?",alice,poolA);
        mvc.perform(get("/api/v1/imports/"+source+"/duplicates").with(as("alice"))).andExpect(status().isNotFound());
    }
    @Test void duplicatesExcludeIncompleteAndExpiredFilesAndRejectIneligibleSource() throws Exception {
        String source = accept();
        mvc.perform(upload(poolA,"expired-duplicate","# Synthetic CV").with(as("alice")).with(csrf())).andExpect(status().isAccepted());
        db.update("UPDATE document_import SET expires_at=created_at WHERE idempotency_key='expired-duplicate'");
        storage.fail=true;
        mvc.perform(upload(poolA,"incomplete-duplicate","# Synthetic CV").with(as("alice")).with(csrf())).andExpect(status().isServiceUnavailable());
        mvc.perform(get("/api/v1/imports/"+source+"/duplicates").with(as("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        UUID incomplete=db.queryForObject("SELECT id FROM document_import WHERE idempotency_key='incomplete-duplicate'",UUID.class);
        mvc.perform(get("/api/v1/imports/"+incomplete+"/duplicates").with(as("alice"))).andExpect(status().isConflict());
        db.update("UPDATE document_import SET expires_at=created_at WHERE id=?",UUID.fromString(source));
        mvc.perform(get("/api/v1/imports/"+source+"/duplicates").with(as("alice"))).andExpect(status().isConflict());
    }
    @Test void duplicatePagesAreBoundedWithoutRepeatingSourceOrTiedRows() throws Exception {
        String source=accept();
        for(int i=0;i<22;i++) mvc.perform(upload(poolA,"duplicate-page-"+i,"# Synthetic CV").with(as("alice")).with(csrf())).andExpect(status().isAccepted());
        db.update("UPDATE document_import SET created_at='2026-01-01T00:00:00Z'");
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        var first=mapper.readTree(mvc.perform(get("/api/v1/imports/"+source+"/duplicates").with(as("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(20)).andReturn().getResponse().getContentAsString());
        var second=mapper.readTree(mvc.perform(get("/api/v1/imports/"+source+"/duplicates").param("cursor",first.get("nextCursor").asText()).with(as("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2)).andReturn().getResponse().getContentAsString());
        var ids=new java.util.HashSet<String>();
        first.get("items").forEach(item -> assertThat(ids.add(item.get("id").asText())).isTrue());
        second.get("items").forEach(item -> assertThat(ids.add(item.get("id").asText())).isTrue());
        assertThat(ids).doesNotContain(source).hasSize(22);
        mvc.perform(get("/api/v1/imports/"+source+"/duplicates").param("cursor","bad|cursor|extra").with(as("alice"))).andExpect(status().isBadRequest());
    }
    @Test void manyFilesAndInvalidKeyAreRejected() throws Exception {
        mvc.perform(upload(poolA,"valid-key","test").file(new MockMultipartFile("files","second.md","text/markdown",new byte[]{65})).with(as("alice")).with(csrf()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("SINGLE_FILE_REQUIRED"));
        mvc.perform(upload(poolA,"short","test").with(as("alice")).with(csrf())).andExpect(status().isBadRequest());
        assertThat(storage.objects).isEmpty();
    }
}
