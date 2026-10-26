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
    @Autowired com.skillhunters.talent.SkillPeriodService periods;
    @Test void skillPeriodsRequireHumanLinkKeepAcademicSeparateAndResetOnPublication() throws Exception {
        var c=create();var r=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var state=reviews.get(alice,c.id());var claims=state.source().proposal().claims();
        int skill=-1,experience=-1;
        for(int i=0;i<claims.size();i++) {if(claims.get(i).field()==StructuredExtractor.Field.SKILL) skill=i;if(claims.get(i).field()==StructuredExtractor.Field.EXPERIENCE) experience=i;}
        var link=new com.skillhunters.talent.SkillPeriodService.Link(skill,experience,r.items().get(experience).period(),claims.get(experience).evidence().getFirst().quote(),true);
        assertThat(periods.get(alice,c.id(),null).skills().getFirst().professional().minimumMonths()).isNull();
        var input=new com.skillhunters.talent.SkillPeriodService.Request(r.id(),List.of(link),"Association vérifiée");
        var report=periods.save(alice,c.id(),"\"2\"",input);
        assertThat(report.version()).isEqualTo(3);assertThat(report.skills().getFirst().byKind().get(Kind.ACADEMIC).minimumMonths()).isEqualTo(18);
        assertThat(report.skills().getFirst().professional().minimumMonths()).isNull();
        assertThatThrownBy(()->periods.save(alice,c.id(),"\"2\"",input)).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->periods.get(admin,c.id(),null)).isInstanceOf(Rejected.class);
        mvc.perform(put(candidateUrl(c.id())+"/skill-periods").with(as("alice")).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isForbidden());
        reviews.save(alice,c.id(),"\"3\"",request(r.items()),true);
        assertThat(periods.get(alice,c.id(),null).links()).isEmpty();assertThat(periods.history(alice,c.id(),0)).hasSize(1);
        assertThatThrownBy(()->periods.save(alice,c.id(),"\"4\"",input)).isInstanceOf(Rejected.class);
        db.update("DELETE FROM document_audit WHERE import_id=?",id);
        db.update("DELETE FROM document_import WHERE id=?",id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_period_links",Integer.class)).isZero();
    }
    @Test void periodsRejectInventedEvidenceUnconfirmedAssociationAndOutOfMissionDates() {
        var c=create();var r=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var claims=reviews.get(alice,c.id()).source().proposal().claims();int skill=0;int experience=0;
        for(int i=0;i<claims.size();i++) {if(claims.get(i).field()==StructuredExtractor.Field.SKILL) skill=i;if(claims.get(i).field()==StructuredExtractor.Field.EXPERIENCE) experience=i;}
        var valid=r.items().get(experience).period();var quote=claims.get(experience).evidence().getFirst().quote();
        var invalid=List.of(new com.skillhunters.talent.SkillPeriodService.Link(skill,experience,valid,"invented evidence",true),
            new com.skillhunters.talent.SkillPeriodService.Link(skill,experience,valid,quote,false),
            new com.skillhunters.talent.SkillPeriodService.Link(skill,experience,new Period("2020-01","2025-06",DatePrecision.MONTH,Kind.ACADEMIC),quote,true));
        for(var link:invalid) assertThatThrownBy(()->periods.save(alice,c.id(),"\"2\"",new com.skillhunters.talent.SkillPeriodService.Request(r.id(),List.of(link),"Test"))).isInstanceOf(Rejected.class);
        assertThat(periods.history(alice,c.id(),0)).isEmpty();
    }
    @Autowired com.skillhunters.search.HybridSearchService hybrid;
    @MockitoBean com.skillhunters.search.EmbeddingPort embeddings;
    void hybridDictionary() {
        int version=0;
        for(String name:List.of("Java","Kafka","Spring Boot")) {
            taxonomy.edit(admin,null,"\""+version+++"\"",new com.skillhunters.taxonomy.TaxonomyService.Edit(name,"Synthetic",name.equals("Java")?List.of("JDK"):List.of(),"Test"));
        }
    }
    void sameVectors() throws Exception {
        when(embeddings.encode(any())).thenAnswer(invocation->{
            List<String> labels=invocation.getArgument(0);
            return labels.stream().map(label->{var v=new ArrayList<>(Collections.nCopies(384,0.0));v.set(0,1.0);return (List<Double>)v;}).toList();
        });
    }
    @Test void hybridSimilaritiesNeverBecomeProofAndOnlyCanonicalLabelsReachModel() throws Exception {
        hybridDictionary();sameVectors();var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var result=hybrid.search(alice,new com.skillhunters.search.HybridSearchService.Request(searchQuery(false,"Spring Boot",null),0.65));
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().criteria().getFirst().presence()).isEqualTo(com.skillhunters.search.StructuredMatcher.Status.NOT_DOCUMENTED);
        assertThat(result.retrieval().signals()).anySatisfy(signal->{assertThat(signal.channel()).isEqualTo("VECTOR");assertThat(signal.citations()).isNotEmpty();});
        verify(embeddings).encode(List.of("Java","Kafka","Spring Boot"));
        assertThat(hybrid.search(alice,new com.skillhunters.search.HybridSearchService.Request(searchQuery(true,"Spring Boot",null),0.65)).items()).isEmpty();
    }
    @Test void hybridRetainsValidatedAliasWithEvidence() throws Exception {
        hybridDictionary();sameVectors();var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var result=hybrid.search(alice,new com.skillhunters.search.HybridSearchService.Request(searchQuery(true,"JDK",null),0.99));
        assertThat(result.items()).hasSize(1);
        assertThat(result.retrieval().signals()).anySatisfy(signal->{assertThat(signal.channel()).isEqualTo("STRUCTURED");assertThat(signal.matchedSkill()).isEqualTo("Java");});
    }
    @Test void hybridChecksRightsAndExpiryBeforeCallingModelAndRequiresCsrf() throws Exception {
        hybridDictionary();var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var query=new com.skillhunters.search.HybridSearchService.Request(searchQuery(false,"Java",null),0.65);
        assertThatThrownBy(()->hybrid.search(admin,query)).isInstanceOf(Rejected.class);verifyNoInteractions(embeddings);
        mvc.perform(post("/api/v1/search/hybrid").with(as("alice")).contentType("application/json").content(json.writeValueAsString(query))).andExpect(status().isForbidden());
        db.update("UPDATE document_import SET pool_id=? WHERE id=?",poolB,id);
        assertThat(hybrid.search(alice,query).items()).isEmpty();verifyNoInteractions(embeddings);
        db.update("UPDATE document_import SET pool_id=?,expires_at=CURRENT_TIMESTAMP-INTERVAL '1' DAY WHERE id=?",poolA,id);
        assertThat(hybrid.search(alice,query).items()).isEmpty();verifyNoInteractions(embeddings);
    }
    @Test void cachedVectorsDoNotBypassRevokedMembership() throws Exception {
        hybridDictionary();sameVectors();var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var query=new com.skillhunters.search.HybridSearchService.Request(searchQuery(false,"Java",null),0.65);
        assertThat(hybrid.search(alice,query).retrieval().indexedLabels()).isEqualTo(2);
        reset(embeddings);when(embeddings.encode(any())).thenThrow(new IllegalStateException("offline"));
        assertThat(hybrid.search(alice,query).retrieval().reusedLabels()).isEqualTo(2);verifyNoInteractions(embeddings);
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        assertThatThrownBy(()->hybrid.search(alice,query)).isInstanceOf(Rejected.class);verifyNoInteractions(embeddings);
    }
    @Test void professionalMentionsUsePublishedRevisionAndNeverBecomeExactProof() throws Exception {
        hybridDictionary();sameVectors();
        when(analyzer.analyze(any(),any(),any())).thenReturn(new DocumentAnalyzer.Result("EXTRACTED",null,
            List.of(new DocumentAnalyzer.Block("document","Compétence : Java\nExpérience : Migration Spring Boot\nNom : Kafka")),List.of(),"sh08-test","ClamAV synthetic"));
        var c=create();var items=accepted(c.id());reviews.save(alice,c.id(),"\"1\"",request(items),true);
        var query=new com.skillhunters.search.HybridSearchService.Request(searchQuery(false,"Spring Boot",null),0.99);
        var first=hybrid.search(alice,query);
        assertThat(first.retrieval().signals()).anySatisfy(signal->{
            assertThat(signal.matchedSkill()).isEqualTo("Spring Boot");
            assertThat(signal.sourceField()).isEqualTo(StructuredExtractor.Field.EXPERIENCE);
            assertThat(signal.channel()).isEqualTo("LEXICAL");assertThat(signal.citations()).isNotEmpty();
        });
        assertThat(first.retrieval().signals()).noneMatch(signal->signal.sourceField()==StructuredExtractor.Field.FULL_NAME);
        assertThat(first.items().getFirst().criteria().getFirst().presence()).isNotEqualTo(com.skillhunters.search.StructuredMatcher.Status.DOCUMENTED);
        var changed=new ArrayList<>(items);
        for(int i=0;i<changed.size();i++) if(reviews.get(alice,c.id()).source().proposal().claims().get(i).field()==StructuredExtractor.Field.EXPERIENCE)
            changed.set(i,new Item(i,null,Decision.REJECTED,Precision.UNKNOWN,null,"Removed mention"));
        reviews.save(alice,c.id(),"\"2\"",request(changed),false);
        assertThat(hybrid.search(alice,query).retrieval().signals()).anyMatch(signal->signal.sourceField()==StructuredExtractor.Field.EXPERIENCE);
        reviews.save(alice,c.id(),"\"3\"",request(changed),true);
        assertThat(hybrid.search(alice,query).retrieval().signals()).noneMatch(signal->signal.sourceField()==StructuredExtractor.Field.EXPERIENCE);
        db.update("DELETE FROM document_audit WHERE import_id=?",id);db.update("DELETE FROM document_import WHERE id=?",id);
        assertThat(hybrid.search(alice,query).items()).isEmpty();
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="SH16_BENCHMARK_VECTORS",matches=".+")
    void annotatedCorpusUsesRealModelAndExhaustiveProductionRetrieval() throws Exception {HybridCorpusBenchmark.run(this);}
    @Test void hybridCursorRejectsAChangedThreshold() {
        var q=searchQuery(false,"Java",null);
        String cursor=Base64.getUrlEncoder().withoutPadding().encodeToString((com.skillhunters.search.EmbeddingPort.MODEL+"|0.65|opaque").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var changed=new com.skillhunters.search.StructuredSearchService.Query(q.poolId(),q.criteria(),q.strict(),q.experienceKinds(),null,null,cursor,20);
        assertThatThrownBy(()->hybrid.search(alice,new com.skillhunters.search.HybridSearchService.Request(changed,0.7))).isInstanceOf(Rejected.class);
        verifyNoInteractions(embeddings);
    }
    @Test void hybridFailsExplicitlyWhenModelIsUnavailableOrInvalid() throws Exception {
        hybridDictionary();var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var query=new com.skillhunters.search.HybridSearchService.Request(searchQuery(false,"Java",null),0.65);
        when(embeddings.encode(any())).thenThrow(new IllegalStateException());
        mvc.perform(post("/api/v1/search/hybrid").with(as("alice")).with(csrf()).contentType("application/json").content(json.writeValueAsString(query))).andExpect(status().isServiceUnavailable());
        doReturn(List.of(List.of(Double.NaN))).when(embeddings).encode(any());
        assertThatThrownBy(()->hybrid.search(alice,query)).isInstanceOf(Rejected.class);
    }
    @Autowired com.skillhunters.search.StructuredSearchService search;
    com.skillhunters.search.StructuredSearchService.Query searchQuery(boolean strict,String skill,Integer months) {
        return new com.skillhunters.search.StructuredSearchService.Query(poolA,List.of(
            new com.skillhunters.search.StructuredSearchService.Criterion(skill,com.skillhunters.search.StructuredSearchService.Importance.REQUIRED,months,null),
            new com.skillhunters.search.StructuredSearchService.Criterion("Kafka",com.skillhunters.search.StructuredSearchService.Importance.DESIRED,null,null)),
            strict,List.of(Kind.EMPLOYMENT,Kind.INTERNSHIP,Kind.FREELANCE),null,null,null,20);
    }
    @Test void searchDistinguishesRequiredAndDesiredEvidenceAndStrictModeIsReversible() {
        var c=create();assertThat(search.search(alice,searchQuery(false,"Java",null)).items()).isEmpty();
        reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var r=search.search(alice,searchQuery(true,"Java",null));assertThat(r.items()).hasSize(1);
        assertThat(r.items().getFirst().criteria().getFirst().status()).isEqualTo(com.skillhunters.search.StructuredMatcher.Status.DOCUMENTED);
        assertThat(r.items().getFirst().criteria().getLast().status()).isEqualTo(com.skillhunters.search.StructuredMatcher.Status.NOT_DOCUMENTED);
        assertThat(r.items().getFirst().criteria().getFirst().evidence().getFirst().citations()).isNotEmpty();
        assertThat(search.search(alice,searchQuery(true,"Java",12)).items()).isEmpty();
        assertThat(search.search(alice,searchQuery(false,"Java",12)).items().getFirst().criteria().getFirst().duration()).isEqualTo(com.skillhunters.search.StructuredMatcher.Status.UNKNOWN);
        assertThat(search.search(alice,searchQuery(true,"JavaScript",null)).items()).isEmpty();
        assertThat(search.search(alice,searchQuery(true,"Spring Boot",null)).items()).isEmpty();
    }
    @Test void searchUsesDictionaryAliasesAndKeepsAcademicDurationsOptIn() {
        taxonomy.edit(admin,null,"\"0\"",new com.skillhunters.taxonomy.TaxonomyService.Edit("Java","Language",List.of("JDK"),"Test"));
        var c=create();var rev=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        assertThat(search.search(alice,searchQuery(true,"JDK",null)).items()).hasSize(1);
        var claims=reviews.get(alice,c.id()).source().proposal().claims();int skill=-1,experience=-1;
        for(int i=0;i<claims.size();i++){if(claims.get(i).field()==StructuredExtractor.Field.SKILL)skill=i;if(claims.get(i).field()==StructuredExtractor.Field.EXPERIENCE)experience=i;}
        var link=new com.skillhunters.talent.SkillPeriodService.Link(skill,experience,rev.items().get(experience).period(),claims.get(experience).evidence().getFirst().quote(),true);
        periods.save(alice,c.id(),"\"2\"",new com.skillhunters.talent.SkillPeriodService.Request(rev.id(),List.of(link),"Vérifié"));
        assertThat(search.search(alice,searchQuery(true,"JDK",12)).items()).isEmpty();
        var q=searchQuery(true,"JDK",12);
        var academic=new com.skillhunters.search.StructuredSearchService.Query(q.poolId(),q.criteria(),true,List.of(Kind.ACADEMIC),null,null,null,20);
        assertThat(search.search(alice,academic).items().getFirst().criteria().getFirst().coverage().minimumMonths()).isEqualTo(18);
    }
    @Test void searchDeniesCrossPoolAdminCsrfExpiredAndUnsupportedPersonalCriteria() throws Exception {
        var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        assertThatThrownBy(()->search.search(admin,searchQuery(false,"Java",null))).isInstanceOf(Rejected.class);
        mvc.perform(post("/api/v1/search/structured").with(as("alice")).contentType("application/json").content(json.writeValueAsString(searchQuery(false,"Java",null)))).andExpect(status().isForbidden());
        var personal=json.valueToTree(searchQuery(false,"Java",null));((com.fasterxml.jackson.databind.node.ObjectNode)personal).put("age",30);
        mvc.perform(post("/api/v1/search/structured").with(as("alice")).with(csrf()).contentType("application/json").content(json.writeValueAsString(personal))).andExpect(status().isBadRequest());
        db.update("UPDATE document_import SET pool_id=? WHERE id=?",poolB,id);
        assertThat(search.search(alice,searchQuery(false,"Java",null)).items()).isEmpty();
        db.update("UPDATE document_import SET pool_id=?,expires_at=CURRENT_TIMESTAMP-INTERVAL '1' DAY WHERE id=?",poolA,id);
        assertThat(search.search(alice,searchQuery(false,"Java",null)).items()).isEmpty();
    }
    @Test void unpublishedCorrectionsCannotReplaceSearchEvidenceAndExplicitUnknownStaysUnknown() {
        var c=create();var first=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var items=new ArrayList<>(first.items());var claims=reviews.get(alice,c.id()).source().proposal().claims();
        for(int i=0;i<items.size();i++) if(claims.get(i).field()==StructuredExtractor.Field.SKILL||claims.get(i).field()==StructuredExtractor.Field.DECLARED_LEVEL)
            items.set(i,new Item(i,null,Decision.UNKNOWN,Precision.UNKNOWN,null,"À revérifier"));
        reviews.save(alice,c.id(),"\"2\"",request(items),false);
        assertThat(search.search(alice,searchQuery(true,"Java",null)).items()).hasSize(1);
        reviews.save(alice,c.id(),"\"3\"",request(items),true);
        assertThat(search.search(alice,searchQuery(false,"Java",null)).items().getFirst().criteria().getFirst().presence()).isEqualTo(com.skillhunters.search.StructuredMatcher.Status.UNKNOWN);
    }
    @Test void searchKeysetPagesAreBoundToCriteriaAndNeverRepeatTheFirstCandidate() throws Exception {
        var first=create();reviews.save(alice,first.id(),"\"1\"",request(accepted(first.id())),true);UUID original=id;
        UUID policy=db.queryForObject("SELECT id FROM import_policy",UUID.class);
        mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("files","second.md","text/markdown","Second synthetic source".getBytes()))
            .file(new MockMultipartFile("basis","","application/json",("{\"source\":\"Synthetic\",\"purpose\":\"Test\",\"basisCode\":\"TEST_ONLY\",\"retentionPolicyId\":\""+policy+"\"}").getBytes()))
            .param("poolId",poolA.toString()).header("Idempotency-Key","search-second").with(as("alice")).with(csrf())).andExpect(status().isAccepted());
        id=db.queryForObject("SELECT id FROM document_import WHERE id<>?",UUID.class,original);
        var second=create();reviews.save(alice,second.id(),"\"1\"",request(accepted(second.id())),true);
        var q=searchQuery(false,"Java",null);
        var page=new com.skillhunters.search.StructuredSearchService.Query(poolA,q.criteria(),false,q.experienceKinds(),null,null,null,1);
        var a=search.search(alice,page);assertThat(a.items()).hasSize(1);assertThat(a.nextCursor()).isNotNull();
        var next=new com.skillhunters.search.StructuredSearchService.Query(poolA,q.criteria(),false,q.experienceKinds(),a.trace().referenceDate(),a.trace().dictionaryVersion(),a.nextCursor(),1);
        var b=search.search(alice,next);assertThat(b.items()).hasSize(1);assertThat(b.items().getFirst().candidateId()).isNotEqualTo(a.items().getFirst().candidateId());assertThat(b.nextCursor()).isNull();
        assertThatThrownBy(()->search.search(alice,new com.skillhunters.search.StructuredSearchService.Query(poolA,q.criteria(),true,q.experienceKinds(),a.trace().referenceDate(),a.trace().dictionaryVersion(),a.nextCursor(),1))).isInstanceOf(Rejected.class);
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

    @Autowired com.skillhunters.scoring.ScoredSearchService scored;
    com.skillhunters.scoring.ScoredSearchService.Request scoredRequest(boolean hybridMode,String skill) {
        var q=searchQuery(false,skill,null);
        var single=new com.skillhunters.search.StructuredSearchService.Query(q.poolId(),List.of(q.criteria().getFirst()),q.strict(),q.experienceKinds(),q.referenceDate(),q.dictionaryVersion(),null,q.limit());
        return new com.skillhunters.scoring.ScoredSearchService.Request(single,hybridMode,0.65);
    }
    @Test void scoreSnapshotReplaysExactlyAfterPublicationChangesAndKeepsEvidence() {
        var c=create();var published=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var first=scored.create(alice,scoredRequest(false,"Java"));var item=first.items().getFirst();
        assertThat(item.score().documented()).isEqualTo(100);assertThat(item.hit().publicationId()).isEqualTo(published.id());
        assertThat(item.hit().criteria().getFirst().evidence()).isNotEmpty();
        var changed=new ArrayList<>(published.items());var skill=changed.getFirst();
        changed.set(0,new Item(0,"Python",skill.decision(),skill.precision(),null,"Correction synthétique"));
        reviews.save(alice,c.id(),"\"2\"",request(changed),true);
        assertThat(scored.replay(alice,first.metadata().searchId(),c.id())).isEqualTo(item);
        assertThat(scored.create(alice,scoredRequest(false,"Java")).items().getFirst().score().documented()).isZero();
    }
    @Test void scoreSnapshotsEnforceCsrfOwnershipPoolRevocationExpiryAndDeletion() throws Exception {
        var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var body=json.writeValueAsString(scoredRequest(false,"Java"));
        mvc.perform(post("/api/v1/search/scored").with(as("alice")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/search/scored").with(as("admin")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isNotFound());
        var snapshot=scored.create(alice,scoredRequest(false,"Java"));var sid=snapshot.metadata().searchId();
        String route="/api/v1/search/scored/"+sid;
        mvc.perform(get(route).with(as("admin"))).andExpect(status().isNotFound());
        mvc.perform(post(route+"/candidates/"+c.id()+"/recalculate").with(as("alice"))).andExpect(status().isForbidden());
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        assertThatThrownBy(()->scored.page(alice,sid,0,20)).isInstanceOf(Rejected.class);
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",alice,poolA);
        db.update("UPDATE document_import SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE id=?",id);
        assertThat(scored.page(alice,sid,0,20).items()).isEmpty();assertThatThrownBy(()->scored.replay(alice,sid,c.id())).isInstanceOf(Rejected.class);
        scored.cleanup();assertThat(db.queryForObject("SELECT COUNT(*) FROM scored_search_item",Integer.class)).isZero();
        db.update("UPDATE scored_search SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY");scored.cleanup();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scored_search",Integer.class)).isZero();
    }
    @Test void semanticProximityNeverBecomesScoreAndCascadeErasesFrozenCv() throws Exception {
        hybridDictionary();sameVectors();var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var snapshot=scored.create(alice,scoredRequest(true,"Spring Boot"));var item=snapshot.items().getFirst();
        assertThat(item.signals()).isNotEmpty();assertThat(item.score().documented()).isZero();assertThat(item.score().upper()).isEqualTo(100);
        assertThat(item.score().obligationsToVerify()).isTrue();
        db.update("DELETE FROM document_audit WHERE import_id=?",id);
        db.update("DELETE FROM document_import WHERE id=?",id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scored_search_item",Integer.class)).isZero();
        assertThat(scored.page(alice,snapshot.metadata().searchId(),0,20).items()).isEmpty();
    }

    @Test void scoreRankingTraversesAllPagesAndUnknownProfilesStayVisible() throws Exception {
        var first=create();reviews.save(alice,first.id(),"\"1\"",request(accepted(first.id())),true);
        UUID originalImport=id;UUID policy=db.queryForObject("SELECT id FROM import_policy",UUID.class);
        mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("files","second.md","text/markdown","Synthetic second profile".getBytes()))
            .file(new MockMultipartFile("basis","","application/json",("{\"source\":\"Synthetic\",\"purpose\":\"Test\",\"basisCode\":\"TEST_ONLY\",\"retentionPolicyId\":\""+policy+"\"}").getBytes()))
            .param("poolId",poolA.toString()).header("Idempotency-Key","scoring-second-profile").with(as("alice")).with(csrf())).andExpect(status().isAccepted());
        id=db.queryForObject("SELECT id FROM document_import WHERE id<>?",UUID.class,originalImport);
        var second=create();var corrected=new ArrayList<>(accepted(second.id()));var skill=corrected.getFirst();
        corrected.set(0,new Item(0,"Python",skill.decision(),skill.precision(),null,"Python documenté"));
        reviews.save(alice,second.id(),"\"1\"",request(corrected),true);
        var base=scoredRequest(false,"Java").query();
        var query=new com.skillhunters.search.StructuredSearchService.Query(base.poolId(),base.criteria(),false,base.experienceKinds(),null,null,null,1);
        var page=scored.create(alice,new com.skillhunters.scoring.ScoredSearchService.Request(query,false,0.65));
        assertThat(page.metadata().scanned()).isEqualTo(2);assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().hit().candidateId()).isEqualTo(first.id());assertThat(page.nextCursor()).isEqualTo(1);
        var next=scored.page(alice,page.metadata().searchId(),page.nextCursor(),1);
        assertThat(next.items().getFirst().hit().candidateId()).isEqualTo(second.id());assertThat(next.items().getFirst().score().documented()).isZero();assertThat(next.nextCursor()).isNull();
    }

    @Autowired com.skillhunters.scoring.ScoringConfigurations scoringConfigs;
    @Test void configuredScoresFreezeWeightsAndLegacySnapshotsStillReplay() throws Exception {
        var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var q=searchQuery(false,"Java",null);
        var initial=scored.create(alice,new com.skillhunters.scoring.ScoredSearchService.Request(q,false,0.65));
        assertThat(initial.items().getFirst().score().documented()).isEqualTo(70);
        // Emulate a stored SH-18 metadata record before the policy field existed.
        var stored=json.readTree(db.queryForObject("SELECT metadata_json FROM scored_search WHERE id=?",String.class,initial.metadata().searchId()));
        ((com.fasterxml.jackson.databind.node.ObjectNode)stored).remove("policy");
        db.update("UPDATE scored_search SET metadata_json=? WHERE id=?",json.writeValueAsString(stored),initial.metadata().searchId());
        db.update("UPDATE membership SET role='RECRUITMENT_LEAD' WHERE user_id=?",alice);
        scoringConfigs.save(alice,poolA,"\"0\"",new com.skillhunters.scoring.ScoringConfigurations.Edit("Souhaitées prioritaires",new com.skillhunters.scoring.ScoringConfigurations.Weights(1,3,0,0),"Test pondération"));
        var custom=scored.create(alice,new com.skillhunters.scoring.ScoredSearchService.Request(q,false,0.65,1L));
        assertThat(custom.items().getFirst().score().documented()).isEqualTo(25);
        scoringConfigs.save(alice,poolA,"\"1\"",new com.skillhunters.scoring.ScoringConfigurations.Edit("Obligatoires prioritaires",new com.skillhunters.scoring.ScoringConfigurations.Weights(1,0,0,0),"Nouvelle priorité"));
        assertThat(scored.replay(alice,custom.metadata().searchId(),c.id())).isEqualTo(custom.items().getFirst());
        assertThat(scored.replay(alice,initial.metadata().searchId(),c.id())).isEqualTo(initial.items().getFirst());
        var zero=scored.create(alice,new com.skillhunters.scoring.ScoredSearchService.Request(q,false,0.65,2L)).items().getFirst().score();
        assertThat(zero.documented()).isEqualTo(100);assertThat(zero.components()).hasSize(1);
        scoringConfigs.save(alice,poolA,"\"2\"",new com.skillhunters.scoring.ScoringConfigurations.Edit("Durée seule",new com.skillhunters.scoring.ScoringConfigurations.Weights(0,0,1,0),"Pas de durée demandée"));
        int count=db.queryForObject("SELECT COUNT(*) FROM scored_search",Integer.class);
        assertThatThrownBy(()->scored.create(alice,new com.skillhunters.scoring.ScoredSearchService.Request(q,false,0.65,3L))).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("SCORING_NO_ACTIVE_WEIGHT"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scored_search",Integer.class)).isEqualTo(count);
    }
}
