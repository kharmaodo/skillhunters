package com.skillhunters;

import java.util.*;
import java.nio.charset.StandardCharsets;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.documents.ReceptionValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties="app.imports.max-files-per-user=100")
class BatchImportTest extends IdentityTestSupport {
    @Autowired TestQuarantineStorage storage;
    @Autowired ObjectMapper json;
    final UUID policy=UUID.fromString("30000000-0000-0000-0000-000000000001");
    @BeforeEach void setupPolicy() {
        storage.objects.clear();storage.fail=false;
        db.update("INSERT INTO import_policy(id,label,purpose,basis_code,retention_days) VALUES (?,'Synthetic only','Test','TEST_ONLY',7)",policy);
    }
    Map<String,Object> spec(String name,byte[] bytes) { return Map.of("fileName",name,"byteSize",bytes.length,"sha256",HexFormat.of().formatHex(ReceptionValidator.sha().digest(bytes))); }
    String body(UUID pool,List<Map<String,Object>> files) throws Exception {
        return json.writeValueAsString(Map.of("poolId",pool,"basis",Map.of("source","Synthetic fixture","purpose","Test","basisCode","TEST_ONLY","retentionPolicyId",policy),"files",files));
    }
    JsonNode create(String key,List<Map<String,Object>> files) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/import-batches").with(as("alice")).with(csrf()).header("Idempotency-Key",key)
            .contentType("application/json").content(body(poolA,files))).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
    }
    JsonNode upload(JsonNode batch,int index,byte[] bytes) throws Exception {
        var item=batch.get("items").get(index);
        return json.readTree(mvc.perform(multipart(org.springframework.http.HttpMethod.PUT,"/api/v1/import-batches/"+batch.get("id").asText()+"/items/"+item.get("id").asText()+"/content")
            .file(new MockMultipartFile("file",item.get("fileName").asText(),"application/octet-stream",bytes)).with(as("alice")).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    @Test void hundredFilesKeepNinetyNineSuccessesWhenOneContentIsInvalid() throws Exception {
        byte[] bytes="# Synthetic CV".getBytes(StandardCharsets.UTF_8);
        var files=new ArrayList<Map<String,Object>>();
        for(int i=0;i<100;i++) files.add(spec(i==49 ? "invalid.pdf" : "synthetic-"+i+".md",bytes));
        var batch=create("hundred-key",files);
        for(int i=0;i<100;i++) batch=upload(batch,i,bytes);
        assertThat(batch.get("completedFiles").asInt()).isEqualTo(100);
        int accepted=0,rejected=0;
        for(var item:batch.get("items")) {
            if(item.get("state").asText().equals("QUARANTINED")) accepted++;
            if(item.get("state").asText().equals("REJECTED_FORMAT")) { rejected++;assertThat(item.get("errorCode").asText()).isEqualTo("SIGNATURE_MISMATCH"); }
        }
        assertThat(accepted).isEqualTo(99);assertThat(rejected).isEqualTo(1);assertThat(storage.objects).hasSize(99);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_import",Integer.class)).isEqualTo(99);
        assertThat(create("hundred-key",files).get("id")).isEqualTo(batch.get("id"));
        mvc.perform(post("/api/v1/import-batches").with(as("alice")).with(csrf()).header("Idempotency-Key","another-key")
            .contentType("application/json").content(body(poolA,List.of(spec("extra.md",bytes))))).andExpect(status().isTooManyRequests());
    }
    @Test void retryUsesStoredManifestAndPreservesSuccessfulItemsAfterReload() throws Exception {
        byte[] bytes="# Synthetic".getBytes(); var files=List.of(spec("a.md",bytes),spec("b.md",bytes));
        var batch=create("resume-key",files); batch=upload(batch,0,bytes);
        storage.fail=true;batch=upload(batch,1,bytes);
        assertThat(batch.get("items").get(1).get("state").asText()).isEqualTo("RETRYABLE_FAILURE");
        assertThat(batch.get("items").get(1).get("attempts").asInt()).isEqualTo(1);
        storage.fail=false;
        var reloaded=json.readTree(mvc.perform(get("/api/v1/import-batches/"+batch.get("id").asText()).with(as("alice")))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        reloaded=upload(reloaded,1,bytes);
        assertThat(reloaded.get("items").get(1).get("attempts").asInt()).isEqualTo(2);
        reloaded=upload(reloaded,0,bytes);
        assertThat(reloaded.get("items").get(0).get("attempts").asInt()).isEqualTo(1);
        assertThat(reloaded.get("completedFiles").asInt()).isEqualTo(2);assertThat(storage.objects).hasSize(2);
    }
    @Test void wrongFileDoesNotConsumeAttemptAndManifestKeyCannotChange() throws Exception {
        byte[] bytes="one".getBytes();var batch=create("immutable-key",List.of(spec("a.md",bytes)));var item=batch.get("items").get(0);
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT,"/api/v1/import-batches/"+batch.get("id").asText()+"/items/"+item.get("id").asText()+"/content")
            .file(new MockMultipartFile("file","a.md",null,"two".getBytes())).with(as("alice")).with(csrf()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FILE_FINGERPRINT_MISMATCH"));
        assertThat(db.queryForObject("SELECT attempts FROM import_batch_item",Integer.class)).isZero();
        mvc.perform(post("/api/v1/import-batches").with(as("alice")).with(csrf()).header("Idempotency-Key","immutable-key")
            .contentType("application/json").content(body(poolA,List.of(spec("a.md","two".getBytes()))))).andExpect(status().isConflict());
    }
    @Test void expiredLeaseCanResumeAndCommittedReceiptWinsOverStaleProjection() throws Exception {
        byte[] bytes="test".getBytes();var batch=create("lease-key",List.of(spec("a.md",bytes))); UUID item=UUID.fromString(batch.get("items").get(0).get("id").asText());
        db.update("UPDATE import_batch_item SET state='UPLOADING',attempts=1,lease_token=?,lease_until=? WHERE id=?",UUID.randomUUID(),java.time.OffsetDateTime.now().minusMinutes(3),item);
        batch=upload(batch,0,bytes);assertThat(batch.get("completedFiles").asInt()).isEqualTo(1);
        db.update("UPDATE import_batch_item SET state='UPLOADING',attempts=5,lease_token=?,lease_until=? WHERE id=?",UUID.randomUUID(),java.time.OffsetDateTime.now().minusMinutes(3),item);
        batch=upload(batch,0,bytes);assertThat(batch.get("items").get(0).get("state").asText()).isEqualTo("QUARANTINED");assertThat(storage.objects).hasSize(1);
    }
    @Test void storageFailuresStopAfterFiveAttempts() throws Exception {
        byte[] bytes="test".getBytes();var batch=create("failed-key",List.of(spec("a.md",bytes)));storage.fail=true;
        for(int i=0;i<6;i++) batch=upload(batch,0,bytes);
        assertThat(batch.get("items").get(0).get("state").asText()).isEqualTo("FAILED");
        assertThat(batch.get("items").get(0).get("attempts").asInt()).isEqualTo(5);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM import_batch_event WHERE action='UPLOAD_STARTED'",Integer.class)).isEqualTo(5);
    }
    @Test void permissionCsrfAndRevocationApplyToManifestAndContent() throws Exception {
        byte[] bytes="test".getBytes();var files=List.of(spec("a.md",bytes));
        mvc.perform(post("/api/v1/import-batches").with(as("alice")).with(csrf()).header("Idempotency-Key","denied-key").contentType("application/json").content(body(poolB,files))).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/import-batches").with(as("alice")).header("Idempotency-Key","denied-key").contentType("application/json").content(body(poolA,files))).andExpect(status().isForbidden());
        var batch=create("security-key",files);String path="/api/v1/import-batches/"+batch.get("id").asText();
        mvc.perform(get(path).with(as("admin"))).andExpect(status().isNotFound());
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        mvc.perform(get(path).with(as("alice"))).andExpect(status().isNotFound());
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT,path+"/items/"+batch.get("items").get(0).get("id").asText()+"/content")
            .file(new MockMultipartFile("file","a.md",null,bytes)).with(as("alice")).with(csrf())).andExpect(status().isNotFound());
        assertThat(storage.objects).isEmpty();
    }
    @Test void boundsBatchAndListsWithPagination() throws Exception {
        var over=new ArrayList<Map<String,Object>>();for(int i=0;i<21;i++) over.add(Map.of("fileName","a"+i+".md","byteSize",15728640,"sha256","a".repeat(64)));
        mvc.perform(post("/api/v1/import-batches").with(as("alice")).with(csrf()).header("Idempotency-Key","oversize-key").contentType("application/json").content(body(poolA,over))).andExpect(status().isPayloadTooLarge());
        for(int i=0;i<21;i++) create("list-key-"+i,List.of(spec("a.md","test".getBytes())));
        var first=json.readTree(mvc.perform(get("/api/v1/pools/"+poolA+"/import-batches").with(as("alice"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(20)).andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/v1/pools/"+poolA+"/import-batches").param("cursor",first.get("nextCursor").asText()).with(as("alice")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
    }
    @Test void activeLeasesApplyBackpressureWithoutConsumingAnAttempt() throws Exception {
        byte[] bytes="test".getBytes();var batch=create("pressure-key",List.of(spec("a.md",bytes),spec("b.md",bytes),spec("c.md",bytes)));
        for(int i=0;i<2;i++) db.update("UPDATE import_batch_item SET state='UPLOADING',attempts=1,lease_token=?,lease_until=? WHERE id=?",
            UUID.randomUUID(),java.time.OffsetDateTime.now().plusMinutes(2),UUID.fromString(batch.get("items").get(i).get("id").asText()));
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT,"/api/v1/import-batches/"+batch.get("id").asText()+"/items/"+batch.get("items").get(2).get("id").asText()+"/content")
            .file(new MockMultipartFile("file","c.md",null,bytes)).with(as("alice")).with(csrf())).andExpect(status().isTooManyRequests());
        assertThat(db.queryForObject("SELECT attempts FROM import_batch_item WHERE ordinal=2",Integer.class)).isZero();
    }
    @Test void batchPolicyIsFrozenAndExpiredBatchCannotReceiveMoreData() throws Exception {
        byte[] bytes="test".getBytes();var batch=create("frozen-key",List.of(spec("a.md",bytes),spec("b.md",bytes)));
        db.update("UPDATE import_policy SET purpose='Changed',retention_days=30,enabled=FALSE");
        batch=upload(batch,0,bytes);
        assertThat(db.queryForObject("SELECT purpose FROM document_import",String.class)).isEqualTo("Test");
        assertThat(db.queryForObject("SELECT retention_days FROM document_import",Integer.class)).isEqualTo(7);
        UUID id=UUID.fromString(batch.get("id").asText());db.update("UPDATE import_batch SET expires_at=? WHERE id=?",java.time.OffsetDateTime.now().minusDays(1),id);
        mvc.perform(multipart(org.springframework.http.HttpMethod.PUT,"/api/v1/import-batches/"+id+"/items/"+batch.get("items").get(1).get("id").asText()+"/content")
            .file(new MockMultipartFile("file","b.md",null,bytes)).with(as("alice")).with(csrf())).andExpect(status().isConflict());
        assertThat(storage.objects).hasSize(1);
    }
    @Test void singleFileEndpointCannotUseInternalBatchNamespace() throws Exception {
        var basis=new MockMultipartFile("basis","","application/json",json.writeValueAsBytes(Map.of("source","test","purpose","Test","basisCode","TEST_ONLY","retentionPolicyId",policy)));
        mvc.perform(multipart("/api/v1/imports").file(basis).file(new MockMultipartFile("files","a.md",null,"test".getBytes()))
            .param("poolId",poolA.toString()).header("Idempotency-Key","batch-internal").with(as("alice")).with(csrf()))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("RESERVED_IDEMPOTENCY_KEY"));
        assertThat(storage.objects).isEmpty();
    }
    @Test void twoConcurrentManifestSubmissionsReserveOnlyOnce() throws Exception {
        String request=body(poolA,List.of(spec("a.md","test".getBytes())));
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<String> submit=() -> mvc.perform(post("/api/v1/import-batches").with(as("alice")).with(csrf())
                .header("Idempotency-Key","concurrent-key").contentType("application/json").content(request)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
            var a=executor.submit(submit);var b=executor.submit(submit);
            assertThat(json.readTree(a.get()).get("id")).isEqualTo(json.readTree(b.get()).get("id"));
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM import_batch",Integer.class)).isEqualTo(1);
    }
}
