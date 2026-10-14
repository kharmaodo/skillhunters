package com.skillhunters;

import com.skillhunters.documents.*;
import com.skillhunters.talent.ReviewService.*;
import com.skillhunters.shared.ApiProblem.Rejected;
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

class WordExportTest extends IdentityTestSupport {
    @Autowired AnalysisService analysis;
    @Autowired ExtractionService extraction;
    @Autowired TestQuarantineStorage storage;
    @MockitoBean DocumentAnalyzer analyzer;
    @Autowired com.skillhunters.talent.CandidateService candidates;
    @Autowired com.skillhunters.talent.ReviewService reviews;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired com.skillhunters.exports.WordExportService exports;
    @Autowired com.skillhunters.exports.ExportPermissions permissions;
    @Autowired com.skillhunters.identity.IdentityStore identity;
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
        mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("files","synthetic.md","text/markdown","Compétence : Java 2,5\nExpérience : Projet synthétique 2024/25".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("basis","","application/json",("{\"source\":\"Synthetic\",\"purpose\":\"Test\",\"basisCode\":\"TEST_ONLY\",\"retentionPolicyId\":\""+policy+"\"}").getBytes()))
            .param("poolId",poolA.toString()).header("Idempotency-Key","extraction-test-key").with(as("alice")).with(csrf()))
            .andExpect(status().isAccepted());
        id=db.queryForObject("SELECT id FROM document_import",UUID.class);
        when(analyzer.analyze(any(),any(),any())).thenReturn(new DocumentAnalyzer.Result("EXTRACTED",null,
            List.of(new DocumentAnalyzer.Block("document","Compétence : Java 2,5\nExpérience : Projet synthétique 2024/25")),List.of(),"sh08-test","ClamAV synthetic"));
    }
    List<Item> accepted(UUID candidate) {
        var claims=reviews.get(alice,candidate).source().proposal().claims();
        var result=new ArrayList<Item>();
        for(int i=0;i<claims.size();i++) {
            var c=claims.get(i);boolean unknown=c.status()==StructuredExtractor.State.UNKNOWN;
            result.add(new Item(i,unknown?null:c.normalized(),unknown?Decision.UNKNOWN:Decision.VALIDATED,
                unknown?Precision.UNKNOWN:c.field()==StructuredExtractor.Field.DECLARED_LEVEL?Precision.DECLARED_LEVEL:Precision.TEXT,
                c.field()==StructuredExtractor.Field.EXPERIENCE&&!unknown?new Period("2024-01","2025-06",DatePrecision.MONTH,Kind.ACADEMIC):null,
                unknown?null:"Contrôle du document synthétique"));
        }
        return result;
    }
    Request request(List<Item> items) {return new Request(items,"Relecture synthétique");}
    @Test void onlyPublishedRevisionCanExportAndReceiptIsIdempotentAndAudited() throws Exception {
        var c=create();permissions.set(admin,alice,poolA,true);
        var draft=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),false);
        assertThatThrownBy(()->exports.create(alice,c.id(),draft.id())).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("REVISION_NOT_PUBLISHED"));
        var pub=reviews.save(alice,c.id(),"\"2\"",request(draft.items()),true);
        var first=exports.create(alice,c.id(),pub.id());
        assertThat(exports.create(alice,c.id(),pub.id())).isEqualTo(first);
        var file=exports.download(alice,first.id());
        assertThat(com.skillhunters.exports.WordRenderer.sha(file.content())).isEqualTo(first.sha256());
        assertThat(file.filename()).isEqualTo("cv-"+first.id()+".docx");
        assertThat(first.templateSha256()).hasSize(64);
        mvc.perform(get("/api/v1/exports/"+first.id()+"/download").with(as("alice"))).andExpect(status().isOk())
            .andExpect(header().string("Content-Type",com.skillhunters.exports.WordExportController.DOCX))
            .andExpect(header().string("Cache-Control","no-store"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_export_event WHERE action='GENERATED'",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_export_event WHERE action='DOWNLOAD_PREPARED'",Integer.class)).isEqualTo(2);
    }
    @Test void permissionIsExplicitAndRevokedAtDownloadWithoutAdminBypass() throws Exception {
        var c=create();var pub=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        assertThat(exports.options(alice,c.id()).canExport()).isFalse();
        assertThatThrownBy(()->exports.create(alice,c.id(),pub.id())).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("EXPORT_PERMISSION_REQUIRED"));
        permissions.set(admin,alice,poolA,true);var receipt=exports.create(alice,c.id(),pub.id());
        mvc.perform(get("/api/v1/exports/"+receipt.id()+"/download").with(as("admin"))).andExpect(status().isNotFound());
        permissions.set(admin,alice,poolA,false);
        mvc.perform(get("/api/v1/exports/"+receipt.id()+"/download").with(as("alice"))).andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_export_event WHERE action='DOWNLOAD_PREPARED'",Integer.class)).isZero();
        permissions.set(admin,alice,poolA,true);identity.replaceMemberships(admin,alice,poolA,List.of());
        identity.replaceMemberships(admin,alice,poolA,List.of("RECRUITER"));
        assertThat(exports.options(alice,c.id()).canExport()).isFalse();
    }
    @Test void grantAndGenerationRequireCsrfAndCorrectScope() throws Exception {
        var c=create();var pub=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        String grant="{\"userId\":\""+alice+"\",\"poolId\":\""+poolA+"\",\"allowed\":true}";
        mvc.perform(put("/api/v1/admin/export-permissions").with(as("admin")).contentType("application/json").content(grant)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/export-permissions").with(as("alice")).with(csrf()).contentType("application/json").content(grant)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/export-permissions").with(as("admin")).with(csrf()).contentType("application/json").content(grant)).andExpect(status().isOk());
        mvc.perform(post(candidateUrl(c.id())+"/exports").with(as("alice")).contentType("application/json").content("{\"revisionId\":\""+pub.id()+"\"}")).andExpect(status().isForbidden());
        assertThatThrownBy(()->permissions.set(admin,alice,poolB,true)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("EXPORT_MEMBERSHIP_REQUIRED"));
        assertThatThrownBy(()->exports.create(alice,c.id(),UUID.randomUUID())).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("REVIEW_NOT_FOUND"));
    }
    @Test void expiryAndDeletionIncludeStoredExportsAndEvents() throws Exception {
        var c=create();permissions.set(admin,alice,poolA,true);var pub=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var receipt=exports.create(alice,c.id(),pub.id());db.update("UPDATE document_import SET expires_at=created_at");
        mvc.perform(get("/api/v1/exports/"+receipt.id()+"/download").with(as("alice"))).andExpect(status().isNotFound());
        db.update("DELETE FROM document_audit");db.update("DELETE FROM document_import");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_export",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_export_event",Integer.class)).isZero();
    }
}
