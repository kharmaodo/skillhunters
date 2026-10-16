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

class HumanReviewTest extends IdentityTestSupport {
    @Autowired AnalysisService analysis;
    @Autowired ExtractionService extraction;
    @Autowired TestQuarantineStorage storage;
    @MockitoBean DocumentAnalyzer analyzer;
    @Autowired com.skillhunters.talent.CandidateService candidates;
    @Autowired com.skillhunters.talent.ReviewService reviews;
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
    @Autowired com.skillhunters.taxonomy.TaxonomyService taxonomy;
    @Test void publicationFreezesDictionaryAndPreservesSourceAcrossRename() {
        var dictionary=taxonomy.edit(admin,null,"\"0\"",new com.skillhunters.taxonomy.TaxonomyService.Edit("Java","Language",List.of(),"Test"));
        var c=create();var r=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var match=reviews.normalizations(alice,c.id(),r.id()).getFirst();
        assertThat(match.original()).isEqualTo("Java");assertThat(match.canonical()).isEqualTo("Java");assertThat(match.dictionaryVersion()).isEqualTo(1);
        taxonomy.edit(admin,dictionary.skills().getFirst().id(),"\"1\"",new com.skillhunters.taxonomy.TaxonomyService.Edit("Java platform","Language",List.of("Java"),"Rename"));
        assertThat(reviews.normalizations(alice,c.id(),r.id()).getFirst()).isEqualTo(match);
        assertThat(reviews.publication(alice,c.id()).items()).anySatisfy(i->assertThat(i.taxonomy()).isEqualTo(match));
        assertThatThrownBy(()->reviews.normalizations(admin,c.id(),r.id())).isInstanceOf(Rejected.class);
    }
    @Test void dateCorrectionIsPublishedAndIndexedWithExactSourceAndBeforeAfterHistory() {
        var c=create();var original=reviews.get(alice,c.id()).source();
        assertThat(reviews.publication(alice,c.id()).items()).isEmpty();
        var r=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        assertThat(r.status()).isEqualTo("PUBLISHED");assertThat(r.actorId()).isEqualTo(alice);
        var indexed=reviews.publication(alice,c.id());
        assertThat(indexed.revisionId()).isEqualTo(r.id());
        assertThat(indexed.items()).anySatisfy(i->{assertThat(i.field()).isEqualTo(StructuredExtractor.Field.EXPERIENCE);assertThat(i.value()).isNull();assertThat(i.period().start()).isEqualTo("2024-01");assertThat(i.period().kind()).isEqualTo(Kind.ACADEMIC);});
        assertThat(indexed.items()).allSatisfy(i->assertThat(i.field()).isIn(StructuredExtractor.Field.SKILL,StructuredExtractor.Field.DECLARED_LEVEL,StructuredExtractor.Field.EXPERIENCE));
        assertThat(r.changes()).anySatisfy(change->{assertThat(change.before().period()).isNull();assertThat(change.after().period()).isNotNull();assertThat(change.before().value()).contains("2024/25");});
        assertThat(reviews.get(alice,c.id()).source()).isEqualTo(original);
        assertThat(reviews.revision(alice,c.id(),r.id())).isEqualTo(r);
        assertThat(candidates.get(alice,c.id()).profile().availability().status()).isEqualTo("UNKNOWN");
    }
    @Test void draftAndNewExtractionNeverReplacePublishedCorrections() {
        var c=create();var first=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var items=new ArrayList<>(first.items());var before=items.getFirst();
        items.set(0,new Item(0,"Java SE",before.decision(),before.precision(),null,"Libellé précisé"));
        var draft=reviews.save(alice,c.id(),"\"2\"",request(items),false);
        assertThat(reviews.publication(alice,c.id()).revisionId()).isEqualTo(first.id());
        assertThat(reviews.publication(alice,c.id()).items().getFirst().value()).isEqualTo("Java");
        db.update("UPDATE document_analysis SET state='FAILED'");analysis.retry(alice,id);var job=analysis.claim();
        analysis.complete(job,new DocumentAnalyzer.Result("EXTRACTED",null,List.of(new DocumentAnalyzer.Block("document","Compétence : Python 3")),List.of(),"new-parser","ClamAV synthetic"));
        assertThat(extraction.prepare(alice,id).id()).isNotEqualTo(first.sourceRevisionId());
        assertThat(reviews.get(alice,c.id()).latest()).isEqualTo(draft);
        assertThat(reviews.get(alice,c.id()).source().id()).isEqualTo(first.sourceRevisionId());
        var second=reviews.save(alice,c.id(),"\"3\"",request(items),true);
        assertThat(reviews.publication(alice,c.id()).items().getFirst().value()).isEqualTo("Java SE");
        assertThat(second.parentId()).isEqualTo(draft.id());
        assertThat(reviews.revision(alice,c.id(),first.id())).isEqualTo(first);
    }
    @Test void unknownDatesAndUnreviewedOrInvalidValuesCannotBecomeFacts() throws Exception {
        var c=create();var items=accepted(c.id());
        int experience=2;assertThat(reviews.get(alice,c.id()).source().proposal().claims().get(experience).field()).isEqualTo(StructuredExtractor.Field.EXPERIENCE);
        var base=items.get(experience);
        for(Period period:List.of(new Period("2025-03","2024-01",DatePrecision.MONTH,Kind.EMPLOYMENT),new Period("2025-02-30","2025-03-01",DatePrecision.DAY,Kind.EMPLOYMENT),new Period("2024",null,DatePrecision.YEAR,Kind.EMPLOYMENT))) {
            var copy=new ArrayList<>(items);copy.set(experience,new Item(experience,base.value(),base.decision(),base.precision(),period,base.reason()));
            assertThatThrownBy(()->reviews.save(alice,c.id(),"\"1\"",request(copy),true)).isInstanceOf(Rejected.class);
        }
        var pending=new ArrayList<>(items);var skill=pending.getFirst();pending.set(0,new Item(0,skill.value(),Decision.PENDING,skill.precision(),null,skill.reason()));
        assertThatThrownBy(()->reviews.save(alice,c.id(),"\"1\"",request(pending),true)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("REVIEW_INCOMPLETE"));
        var badLevel=new ArrayList<>(items);badLevel.set(1,new Item(1,"5",Decision.VALIDATED,Precision.DECLARED_LEVEL,null,"Test"));
        assertThatThrownBy(()->reviews.save(alice,c.id(),"\"1\"",request(badLevel),true)).isInstanceOf(Rejected.class);
        var rejected=new ArrayList<>(items);rejected.set(0,new Item(0,null,Decision.REJECTED,Precision.UNKNOWN,null,"Écarté"));
        assertThatThrownBy(()->reviews.save(alice,c.id(),"\"1\"",request(rejected),true)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("REVIEW_CONTRADICTION"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_revision",Integer.class)).isZero();
        assertThat(candidates.get(alice,c.id()).version()).isEqualTo(1);
        var unknown=new ArrayList<>(items);unknown.set(experience,new Item(experience,base.value(),base.decision(),base.precision(),null,base.reason()));
        reviews.save(alice,c.id(),"\"1\"",request(unknown),true);
        assertThat(reviews.publication(alice,c.id()).items()).noneMatch(i->i.field()==StructuredExtractor.Field.EXPERIENCE);
    }
    @Test void csrfVersionAndViviersAreCheckedForEveryReviewRoute() throws Exception {
        var c=create();String body=json.writeValueAsString(request(accepted(c.id())));String url=candidateUrl(c.id());
        mvc.perform(post(url+"/validate").with(as("alice")).header("If-Match","\"1\"").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post(url+"/validate").with(as("alice")).with(csrf()).contentType("application/json").content(body)).andExpect(status().is(428));
        mvc.perform(post(url+"/validate").with(as("admin")).with(csrf()).header("If-Match","\"1\"").contentType("application/json").content(body)).andExpect(status().isNotFound());
        var r=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        mvc.perform(post(url+"/validate").with(as("alice")).with(csrf()).header("If-Match","\"1\"").contentType("application/json").content(body)).andExpect(status().isPreconditionFailed());
        for(String suffix:List.of("/review","/publication","/revisions","/revisions/"+r.id())) mvc.perform(get(url+suffix).with(as("admin"))).andExpect(status().isNotFound());
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        mvc.perform(get(url+"/review").with(as("alice"))).andExpect(status().isNotFound());
    }
    @Test void expiryAndDeletionRemoveAccessAndAllDerivedIndexAndAudit() throws Exception {
        var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        db.update("UPDATE document_import SET expires_at=created_at");
        for(String suffix:List.of("/review","/publication","/revisions")) mvc.perform(get(candidateUrl(c.id())+suffix).with(as("alice"))).andExpect(status().isNotFound());
        db.update("DELETE FROM document_audit");db.update("DELETE FROM document_import");
        for(String table:List.of("candidate_revision","candidate_assertion_index","candidate_edit")) assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).isZero();
    }
    @Test void concurrentPublicationOnlyCommitsOneCompleteRevision() throws Exception {
        var c=create();var request=request(accepted(c.id()));
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",admin,poolA);
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.function.Function<UUID,java.util.concurrent.Callable<Integer>> writer=actor->()->{start.await();try{reviews.save(actor,c.id(),"\"1\"",request,true);return 200;}catch(Rejected e){return e.status.value();}};
            var a=executor.submit(writer.apply(alice));var b=executor.submit(writer.apply(admin));start.countDown();
            assertThat(List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,412);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_revision",Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(DISTINCT revision_id) FROM candidate_assertion_index",Integer.class)).isEqualTo(1);
        assertThat(candidates.get(alice,c.id()).version()).isEqualTo(2);
    }
}
