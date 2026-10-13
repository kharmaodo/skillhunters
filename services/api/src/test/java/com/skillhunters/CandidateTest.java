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

class CandidateTest extends IdentityTestSupport {
    @Autowired AnalysisService analysis;
    @Autowired ExtractionService extraction;
    @Autowired TestQuarantineStorage storage;
    @MockitoBean DocumentAnalyzer analyzer;
    @Autowired com.skillhunters.talent.CandidateService candidates;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    UUID id;
    com.skillhunters.talent.CandidateService.Candidate create() {
        analysis.runOnce();return candidates.create(alice,id,extraction.prepare(alice,id).id());
    }
    String candidateUrl(UUID candidate) { return "/api/v1/candidates/"+candidate; }

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
    @Test void unknownByDefaultAndIdempotentWithImmutableProvenance() throws Exception {
        var candidate=create();
        assertThat(candidate.profile().availability().status()).isEqualTo("UNKNOWN");
        assertThat(candidate.profile().availability().availableFrom()).isNull();
        assertThat(candidate.contact()).isNull();
        assertThat(candidates.create(alice,id,candidate.revisionId()).id()).isEqualTo(candidate.id());
        mvc.perform(get(candidateUrl(candidate.id())).with(as("alice"))).andExpect(status().isOk())
            .andExpect(header().string("ETag","\"1\""));
        mvc.perform(post("/api/v1/imports/"+id+"/candidate").with(as("alice"))
            .contentType("application/json").content("{\"revisionId\":\""+candidate.revisionId()+"\"}"))
            .andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_profile",Integer.class)).isEqualTo(1);
        assertThat(candidates.history(alice,candidate.id(),0,25).items().getFirst().provenance()).contains(candidate.revisionId().toString());
    }
    @Test void staleWriteAndMissingPreconditionNeverOverwriteAndChangesAreAudited() throws Exception {
        var c=create();String url=candidateUrl(c.id());
        String patch="{\"professionalTitle\":\"Développeur Java\",\"reason\":\"Entretien synthétique\"}";
        mvc.perform(patch(url).with(as("alice")).with(csrf()).contentType("application/json").content(patch)).andExpect(status().is(428));
        mvc.perform(patch(url).with(as("alice")).with(csrf()).header("If-Match","\"1\"").contentType("application/json").content(patch))
            .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        mvc.perform(patch(url).with(as("alice")).with(csrf()).header("If-Match","\"1\"").contentType("application/json")
            .content(patch.replace("Java","Python"))).andExpect(status().isPreconditionFailed());
        assertThat(candidates.get(alice,c.id()).profile().professionalTitle()).isEqualTo("Développeur Java");
        var event=candidates.history(alice,c.id(),0,1).items().getFirst();
        assertThat(event.fields()).containsExactly("professionalTitle");assertThat(event.actorId()).isEqualTo(alice);
        assertThat(event.occurredAt()).isNotNull();assertThat(event.provenance()).contains("Entretien synthétique");
        assertThat(candidates.history(alice,c.id(),0,1).nextCursor()).isEqualTo("1");
    }
    @Test void contactValuesAndHistoryAreMaskedAndAdminHasNoImplicitAccess() throws Exception {
        var c=create();String url=candidateUrl(c.id());
        String patch="{\"contact\":{\"email\":\"synthetic@example.com\",\"phone\":null},\"reason\":\"Coordonnées fournies\"}";
        mvc.perform(patch(url).with(as("alice")).with(csrf()).header("If-Match","\"1\"").contentType("application/json").content(patch)).andExpect(status().isForbidden());
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITMENT_LEAD')",admin,poolA);
        mvc.perform(patch(url).with(as("admin")).with(csrf()).header("If-Match","\"1\"").contentType("application/json").content(patch))
            .andExpect(status().isOk()).andExpect(jsonPath("$.contact.email").value("synthetic@example.com"));
        var masked=mvc.perform(get(url).with(as("alice"))).andExpect(status().isOk()).andExpect(jsonPath("$.contactsVisible").value(false)).andReturn();
        assertThat(masked.getResponse().getContentAsString()).doesNotContain("synthetic@example.com");
        assertThat(candidates.history(alice,c.id(),0,25).items().getFirst().provenance()).isEqualTo("CONTACT_REDACTED");
        db.update("DELETE FROM membership WHERE user_id=?",admin);
        mvc.perform(get(url).with(as("admin"))).andExpect(status().isNotFound());
        mvc.perform(get(url+"/history").with(as("admin"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/candidates").param("poolId",poolB.toString()).with(as("alice"))).andExpect(status().isNotFound());
    }
    @Test void availabilityRequiresDateAndDeclarationAndCanReturnToUnknown() throws Exception {
        var c=create();String url=candidateUrl(c.id());
        for(String availability:List.of("{\"status\":\"DECLARED\"}","{\"status\":\"UNKNOWN\",\"availableFrom\":\"2026-01-01\"}",
            "{\"status\":\"DECLARED\",\"availableFrom\":\"2026-01-01\",\"declaredOn\":\"9999-01-01\",\"declaration\":\"Test\"}")) {
            mvc.perform(patch(url).with(as("alice")).with(csrf()).header("If-Match","\"1\"").contentType("application/json")
                .content("{\"availability\":"+availability+",\"reason\":\"Test\"}")).andExpect(status().isBadRequest());
        }
        var updated=candidates.update(alice,c.id(),"\"1\"",json.readTree("{\"availability\":{\"status\":\"DECLARED\",\"availableFrom\":\"2026-01-01\",\"declaredOn\":\"2025-12-01\",\"declaration\":\"Entretien synthétique\"},\"reason\":\"Actualisation\"}"));
        assertThat(updated.profile().availability().status()).isEqualTo("DECLARED");
        assertThat(candidates.update(alice,c.id(),"\"2\"",json.readTree("{\"availability\":{\"status\":\"UNKNOWN\"},\"reason\":\"Information périmée\"}")).profile().availability().availableFrom()).isNull();
    }
    @Test void revokedExpiredAndDeletedSourcesHideAndCascadeProfiles() throws Exception {
        var c=create();
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        mvc.perform(get(candidateUrl(c.id())).with(as("alice"))).andExpect(status().isNotFound());
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",alice,poolA);
        db.update("UPDATE document_import SET expires_at=created_at");
        mvc.perform(get(candidateUrl(c.id())).with(as("alice"))).andExpect(status().isNotFound());
        assertThat(candidates.list(alice,poolA,0,25).items()).isEmpty();
        db.update("DELETE FROM document_audit");db.update("DELETE FROM document_import");
        for(String table:List.of("candidate_profile","candidate_contact","candidate_edit")) assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
    }
    @Test void simultaneousWritersWithDifferentActorsCannotLoseAnUpdate() throws Exception {
        var c=create();
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",admin,poolA);
        var ready=new java.util.concurrent.CountDownLatch(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.function.Function<UUID,java.util.concurrent.Callable<Integer>> writer=actor->()-> {
                ready.countDown();start.await();
                try {candidates.update(actor,c.id(),"\"1\"",json.readTree("{\"displayName\":\"Profil synthétique\",\"reason\":\"Entretien\"}"));return 200;}
                catch(com.skillhunters.shared.ApiProblem.Rejected e) {return e.status.value();}
            };
            var a=executor.submit(writer.apply(alice));var b=executor.submit(writer.apply(admin));
            assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();start.countDown();
            assertThat(List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,412);
        }
        assertThat(candidates.get(alice,c.id()).version()).isEqualTo(2);
        assertThat(candidates.history(alice,c.id(),0,25).items()).hasSize(2);
    }

}
