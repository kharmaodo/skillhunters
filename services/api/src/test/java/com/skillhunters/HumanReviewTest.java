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

    @Autowired com.skillhunters.scoring.ScoreReviews scoreReviews;
    @Test void contestCorrectionAndSelectionPreserveOriginalAndPermissions() throws Exception {
        var c=create();var publication=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var snapshot=scored.create(alice,scoredRequest(false,"Java"));var sid=snapshot.metadata().searchId();var original=snapshot.items().getFirst();
        var contest=new com.skillhunters.scoring.ScoreReviews.Edit(com.skillhunters.scoring.ScoreReviews.Action.CONTEST,"Preuve contestée",0);
        var correction=new com.skillhunters.scoring.ScoreReviews.Edit(com.skillhunters.scoring.ScoreReviews.Action.CORRECTION,"Correction publiée vérifiée",null);
        assertThatThrownBy(()->scoreReviews.save(alice,sid,c.id(),"\"0\"",correction)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("SCORE_CONTEST_REQUIRED"));
        scoreReviews.save(alice,sid,c.id(),"\"0\"",contest);
        assertThatThrownBy(()->scoreReviews.save(alice,sid,c.id(),"\"0\"",contest)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("SCORE_REVIEW_CHANGED"));
        assertThatThrownBy(()->scoreReviews.save(alice,sid,c.id(),"\"1\"",correction)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("SCORE_CORRECTION_REQUIRED"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM scored_search",Integer.class)).isEqualTo(1);
        var changed=new ArrayList<>(publication.items());var skill=changed.getFirst();
        changed.set(0,new Item(0,"Python",skill.decision(),skill.precision(),null,"Correction synthétique"));
        var fixed=reviews.save(alice,c.id(),"\"2\"",request(changed),true);
        var event=scoreReviews.save(alice,sid,c.id(),"\"1\"",correction);
        assertThat(event.score().documented()).isZero();assertThat(event.publicationId()).isEqualTo(fixed.id());assertThat(event.actorId()).isEqualTo(alice);
        assertThat(scored.replay(alice,sid,c.id())).isEqualTo(original);
        var selected=scoreReviews.save(alice,event.replacementSearchId(),c.id(),"\"0\"",new com.skillhunters.scoring.ScoreReviews.Edit(com.skillhunters.scoring.ScoreReviews.Action.SELECT,"Vérification humaine malgré score nul",null));
        assertThat(selected.score().documented()).isZero();assertThat(scoreReviews.history(alice,sid,c.id(),0).items()).hasSize(2);
        String route="/api/v1/search/scored/"+sid+"/candidates/"+c.id()+"/reviews";
        mvc.perform(get(route).with(as("admin"))).andExpect(status().isNotFound());
        mvc.perform(post(route).with(as("alice")).contentType("application/json").content(json.writeValueAsString(contest))).andExpect(status().isForbidden());
        mvc.perform(post(route).with(as("alice")).with(csrf()).contentType("application/json").content(json.writeValueAsString(contest))).andExpect(status().isPreconditionRequired());
        mvc.perform(get(route).with(as("alice"))).andExpect(status().isOk()).andExpect(header().string("ETag","\"2\""));
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        assertThatThrownBy(()->scoreReviews.history(alice,sid,c.id(),0)).isInstanceOf(Rejected.class);
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",alice,poolA);
        db.update("UPDATE document_import SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE id=?",id);
        assertThatThrownBy(()->scoreReviews.history(alice,sid,c.id(),0)).isInstanceOf(Rejected.class);
        scored.cleanup();assertThat(db.queryForObject("SELECT COUNT(*) FROM score_review_event",Integer.class)).isZero();
    }
    @Test void scoreReviewValidatesAndSerializesConcurrentDecisions() throws Exception {
        var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var snapshot=scored.create(alice,scoredRequest(false,"Java"));var sid=snapshot.metadata().searchId();
        var action=com.skillhunters.scoring.ScoreReviews.Action.CONTEST;
        for(var edit:List.of(new com.skillhunters.scoring.ScoreReviews.Edit(action," ",0),new com.skillhunters.scoring.ScoreReviews.Edit(action,"Test",99),new com.skillhunters.scoring.ScoreReviews.Edit(action,"Test",null),new com.skillhunters.scoring.ScoreReviews.Edit(action,"x".repeat(501),0)))
            assertThatThrownBy(()->scoreReviews.save(alice,sid,c.id(),"\"0\"",edit)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("INVALID_SCORE_REVIEW"));
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",admin,poolA);
        assertThatThrownBy(()->scoreReviews.history(admin,sid,c.id(),0)).isInstanceOf(Rejected.class);
        var edit=new com.skillhunters.scoring.ScoreReviews.Edit(action,"Preuve contestée",0);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<String> task=()->{start.await();try{scoreReviews.save(alice,sid,c.id(),"\"0\"",edit);return "OK";}catch(Rejected e){return e.code;}};
            var a=executor.submit(task);var b=executor.submit(task);start.countDown();
            assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder("OK","SCORE_REVIEW_CHANGED");
        }
        assertThat(scoreReviews.history(alice,sid,c.id(),0).version()).isEqualTo(1);
        scoreReviews.save(alice,sid,c.id(),"\"1\"",new com.skillhunters.scoring.ScoreReviews.Edit(com.skillhunters.scoring.ScoreReviews.Action.WITHDRAW,"Relecture humaine",null));
        db.update("DELETE FROM candidate_profile WHERE id=?",c.id());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM score_review_event",Integer.class)).isZero();
    }

    @Autowired com.skillhunters.talent.CandidateTracking tracking;
    com.skillhunters.talent.CandidateTracking.Edit follow(com.skillhunters.talent.CandidateTracking.Status status,String note,List<String> tags,List<String> lists) {
        return new com.skillhunters.talent.CandidateTracking.Edit(status,note,tags,lists,"Relecture professionnelle",true);
    }
    @Test void trackingIsExplicitPrivateAndFilterableWithBeforeAfterHistory() throws Exception {
        var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        assertThat(tracking.get(alice,c.id()).version()).isZero();
        assertThat(tracking.list(alice,poolA,com.skillhunters.talent.CandidateTracking.Status.NEW,null,null,0).items()).hasSize(1);
        scored.create(alice,scoredRequest(false,"Java"));
        assertThat(tracking.get(alice,c.id()).values().status()).isEqualTo(com.skillhunters.talent.CandidateTracking.Status.NEW);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_tracking",Integer.class)).isZero();
        var edit=follow(com.skillhunters.talent.CandidateTracking.Status.SHORTLISTED,"Preuve Java publiée",List.of("Java"),List.of("Poste Backend"));
        var saved=tracking.save(alice,c.id(),"\"0\"",edit);
        assertThat(saved.values().tags()).containsExactly("java");assertThat(saved.values().lists()).containsExactly("poste backend");
        assertThat(tracking.list(alice,poolA,com.skillhunters.talent.CandidateTracking.Status.SHORTLISTED,"POSTE BACKEND","JAVA",0).items()).hasSize(1);
        assertThat(tracking.list(alice,poolA,com.skillhunters.talent.CandidateTracking.Status.NEW,null,null,0).items()).isEmpty();
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",admin,poolA);
        assertThat(tracking.get(admin,c.id()).version()).isZero();assertThat(tracking.get(admin,c.id()).values().note()).isEmpty();
        assertThat(tracking.history(admin,c.id(),0).items()).isEmpty();assertThat(tracking.list(admin,poolA,null,"poste backend",null,0).items()).isEmpty();
        var after=tracking.save(alice,c.id(),"\"1\"",follow(com.skillhunters.talent.CandidateTracking.Status.TO_REVIEW,"Vérification technique prévue",List.of(),List.of()));
        var h=tracking.history(alice,c.id(),0).items();assertThat(h).hasSize(2);assertThat(h.getFirst().before()).isEqualTo(saved.values());assertThat(h.getFirst().after()).isEqualTo(after.values());assertThat(h.getFirst().actorId()).isEqualTo(alice);
        assertThat(tracking.list(alice,poolA,null,"poste backend",null,0).items()).isEmpty();
        scored.create(alice,scoredRequest(false,"Java"));assertThat(tracking.get(alice,c.id())).isEqualTo(after);
        String route="/api/v1/candidates/"+c.id()+"/tracking";
        mvc.perform(get(route).with(as("alice"))).andExpect(status().isOk()).andExpect(header().string("ETag","\"2\""));
        mvc.perform(put(route).with(as("alice")).contentType("application/json").content(json.writeValueAsString(edit))).andExpect(status().isForbidden());
        mvc.perform(put(route).with(as("alice")).with(csrf()).contentType("application/json").content(json.writeValueAsString(edit))).andExpect(status().isPreconditionRequired());
        mvc.perform(put(route).with(as("alice")).with(csrf()).header("If-Match","\"2\"").contentType("application/json").content(json.writeValueAsString(edit))).andExpect(status().isOk());
    }
    @Test void trackingRejectsInvalidContentAndConcurrentOverwrite() throws Exception {
        var c=create();var status=com.skillhunters.talent.CandidateTracking.Status.TO_REVIEW;
        var valid=follow(status,"Relecture du document",List.of("java"),List.of("backend"));
        for(var edit:List.of(follow(status,"<script>bad</script>",List.of(),List.of()),follow(status,"x".repeat(2001),List.of(),List.of()),follow(status,"Test",List.of("Java","java"),List.of()),follow(status,"Test",List.of("＜b＞"),List.of()),new com.skillhunters.talent.CandidateTracking.Edit(status,"Test",List.of(),List.of(),"Motif",false)))
            assertThatThrownBy(()->tracking.save(alice,c.id(),"\"0\"",edit)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("INVALID_TRACKING"));
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<String> task=()->{start.await();try{tracking.save(alice,c.id(),"\"0\"",valid);return "OK";}catch(Rejected e){return e.code;}};
            var a=executor.submit(task);var b=executor.submit(task);start.countDown();assertThat(List.of(a.get(),b.get())).containsExactlyInAnyOrder("OK","TRACKING_CHANGED");
        }
        assertThat(tracking.history(alice,c.id(),0).items()).hasSize(1);
        for(int n=1;n<=25;n++)tracking.save(alice,c.id(),"\""+n+"\"",follow(status,"Note factuelle "+n,List.of(),List.of()));
        assertThat(tracking.history(alice,c.id(),0).items()).hasSize(25);assertThat(tracking.history(alice,c.id(),0).nextCursor()).isEqualTo("25");assertThat(tracking.history(alice,c.id(),25).items()).hasSize(1);
        db.update("DELETE FROM candidate_profile WHERE id=?",c.id());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_tracking_event",Integer.class)).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_tracking_label",Integer.class)).isZero();
    }
    @Test void trackingChecksScopeRevocationAndSourceRetention() {
        var c=create();var edit=follow(com.skillhunters.talent.CandidateTracking.Status.CONTACTED,"Contact professionnel consigné",List.of("java"),List.of("backend"));
        assertThatThrownBy(()->tracking.get(admin,c.id())).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->tracking.list(alice,poolB,null,null,null,0)).isInstanceOf(Rejected.class);
        tracking.save(alice,c.id(),"\"0\"",edit);
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        assertThatThrownBy(()->tracking.get(alice,c.id())).isInstanceOf(Rejected.class);assertThatThrownBy(()->tracking.history(alice,c.id(),0)).isInstanceOf(Rejected.class);assertThatThrownBy(()->tracking.save(alice,c.id(),"\"1\"",edit)).isInstanceOf(Rejected.class);
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",alice,poolA);
        db.update("UPDATE document_import SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE id=?",id);
        assertThat(tracking.list(alice,poolA,null,null,null,0).items()).isEmpty();assertThatThrownBy(()->tracking.get(alice,c.id())).isInstanceOf(Rejected.class);
        tracking.cleanup();assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_tracking",Integer.class)).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_tracking_event",Integer.class)).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_tracking_label",Integer.class)).isZero();
    }

    @Autowired com.skillhunters.scoring.ProfileComparisons comparisons;
    UUID comparisonCandidate(String skill) throws Exception {
        UUID policy=db.queryForObject("SELECT id FROM import_policy LIMIT 1",UUID.class);
        String file="compare-"+UUID.randomUUID()+".md";
        mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("files",file,"text/markdown",file.getBytes()))
            .file(new MockMultipartFile("basis","","application/json",json.writeValueAsBytes(Map.of("source","Synthetic","purpose","Test","basisCode","TEST_ONLY","retentionPolicyId",policy))))
            .param("poolId",poolA.toString()).header("Idempotency-Key",UUID.randomUUID().toString()).with(as("alice")).with(csrf())).andExpect(status().isAccepted());
        id=db.queryForObject("SELECT id FROM document_import WHERE file_name=?",UUID.class,file);
        analysis.runOnce(); // Drain the initial fixture import before the additional document.
        var c=create();var items=new ArrayList<>(accepted(c.id()));var first=items.getFirst();
        items.set(0,new Item(0,skill,first.decision(),first.precision(),null,"Compétence relue"));
        reviews.save(alice,c.id(),"\"1\"",request(items),true);return c.id();
    }
    @Test void comparisonUsesSameFrozenCriteriaForThreeProfilesAndDoesNotChangeTracking() throws Exception {
        var a=comparisonCandidate("Java");var b=comparisonCandidate("Python");var c=comparisonCandidate("Java");
        var snapshot=scored.create(alice,scoredRequest(false,"Java"));
        var selection=new com.skillhunters.scoring.ProfileComparisons.Request(List.of(c,b,a));
        var result=comparisons.compare(alice,snapshot.metadata().searchId(),selection);
        assertThat(result.profiles()).extracting(p->p.snapshot().hit().candidateId()).containsExactly(c,b,a);
        assertThat(result.profiles()).allSatisfy(p->{
            assertThat(p.snapshot().hit().criteria()).extracting(r->r.criterion()).containsExactlyElementsOf(snapshot.metadata().trace().criteria());
            assertThat(p.declaredExperience()).isNotEmpty();assertThat(p.declaredExperience().getFirst().citations()).isNotEmpty();
            assertThat(p.snapshot().hit().criteria().getFirst().coverage().minimumMonths()).isNull();
        });
        assertThat(result.profiles().get(1).snapshot().score().documented()).isZero();
        assertThat(result.profiles().getFirst().snapshot().score().documented()).isEqualTo(100);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM profile_comparison_member",Integer.class)).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_tracking",Integer.class)).isZero();
        var edits=new ArrayList<>(accepted(a));var first=edits.getFirst();
        edits.set(0,new Item(0,"Go",first.decision(),first.precision(),null,"Correction ultérieure"));
        reviews.save(alice,a,"\"2\"",request(edits),true);
        assertThat(comparisons.compare(alice,snapshot.metadata().searchId(),selection).profiles()).isEqualTo(result.profiles());
        String route="/api/v1/search/scored/"+snapshot.metadata().searchId()+"/comparisons";
        mvc.perform(post(route).with(as("alice")).contentType("application/json").content(json.writeValueAsString(selection))).andExpect(status().isForbidden());
        mvc.perform(post(route).with(as("alice")).with(csrf()).contentType("application/json").content(json.writeValueAsString(selection)))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.profiles.length()").value(3));
    }
    @Test void comparisonRejectsInvalidSelectionsOtherActorsAndMixedSnapshotsAtomically() throws Exception {
        var a=comparisonCandidate("Java");var b=comparisonCandidate("Python");var c=comparisonCandidate("Java");var d=comparisonCandidate("Java");
        var sid=scored.create(alice,scoredRequest(false,"Java")).metadata().searchId();
        var request=new com.skillhunters.scoring.ProfileComparisons.Request(List.of(a,b));
        assertThat(comparisons.compare(alice,sid,new com.skillhunters.scoring.ProfileComparisons.Request(List.of(a,b,c,d))).profiles()).hasSize(4);
        for(var ids:List.of(List.<UUID>of(),List.of(a),List.of(a,a),List.of(a,b,c,d,UUID.randomUUID()),Arrays.asList(a,null)))
            assertThatThrownBy(()->comparisons.compare(alice,sid,new com.skillhunters.scoring.ProfileComparisons.Request(ids)))
                .isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("INVALID_COMPARISON"));
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",admin,poolA);
        assertThatThrownBy(()->comparisons.compare(admin,sid,request)).isInstanceOf(Rejected.class);
        var later=comparisonCandidate("Java");
        assertThatThrownBy(()->comparisons.compare(alice,sid,new com.skillhunters.scoring.ProfileComparisons.Request(List.of(a,later)))).isInstanceOf(Rejected.class);
        db.update("UPDATE document_import SET pool_id=? WHERE id=(SELECT import_id FROM candidate_profile WHERE id=?)",poolB,b);
        assertThatThrownBy(()->comparisons.compare(alice,sid,request)).isInstanceOf(Rejected.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM profile_comparison",Integer.class)).isEqualTo(1);
    }
    @Test void comparisonRechecksRevocationExpiryAndCascadesAuditIdentifiers() throws Exception {
        var a=comparisonCandidate("Java");var b=comparisonCandidate("Java");
        var sid=scored.create(alice,scoredRequest(false,"Java")).metadata().searchId();
        var request=new com.skillhunters.scoring.ProfileComparisons.Request(List.of(a,b));
        comparisons.compare(alice,sid,request);
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        assertThatThrownBy(()->comparisons.compare(alice,sid,request)).isInstanceOf(Rejected.class);
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",alice,poolA);
        db.update("UPDATE document_import SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE id=(SELECT import_id FROM candidate_profile WHERE id=?)",b);
        assertThatThrownBy(()->comparisons.compare(alice,sid,request)).isInstanceOf(Rejected.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM profile_comparison",Integer.class)).isEqualTo(1);
        scored.cleanup();assertThat(db.queryForObject("SELECT COUNT(*) FROM profile_comparison_member WHERE candidate_id=?",Integer.class,b)).isZero();
        db.update("DELETE FROM candidate_profile WHERE id=?",a);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM profile_comparison_member",Integer.class)).isZero();
        db.update("UPDATE scored_search SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY");scored.cleanup();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM profile_comparison",Integer.class)).isZero();
    }

    @Autowired com.skillhunters.communication.ContactDrafts contactDrafts;
    UUID contactPublication(){return db.queryForObject("SELECT id FROM candidate_revision WHERE status='PUBLISHED' ORDER BY version DESC LIMIT 1",UUID.class);}
    com.skillhunters.communication.ContactDrafts.Input contactInput(String template,String subject,String body) {
        var vars=new HashMap<String,String>();
        for(String key:com.skillhunters.communication.ContactDrafts.VARIABLES)vars.put(key,"Synthétique");
        vars.put("candidateFirstName","");vars.put("candidateLastName","");
        vars.put("contactEmail","recruiter@example.com");vars.put("unsubscribeOrObjectionLink","https://example.com/objection");
        vars.put("jobDescription","Projet <script>alert(1)</script> & équipe");
        return new com.skillhunters.communication.ContactDrafts.Input(contactPublication(),template,vars,List.of(0),subject,body);
    }
    @Test void contactDraftsUsePublishedSkillsEscapeHtmlAndTrackEdits() throws Exception {
        var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITMENT_LEAD')",alice,poolA);
        db.update("UPDATE candidate_contact SET email='candidate@example.com' WHERE candidate_id=?",c.id());
        assertThat(contactDrafts.context(alice,c.id()).templates()).hasSize(2);
        for(String template:List.of("professional","direct")) {
            var preview=contactDrafts.preview(alice,c.id(),contactInput(template,null,null));
            assertThat(preview.matchingSkills().getFirst().value()).isEqualTo("Java");
            assertThat(preview.body()).contains("Bonjour,","Java");
            assertThat(preview.htmlBody()).doesNotContain("<script>").contains("&lt;script&gt;");
            assertThat(preview.missingFields()).isEmpty();assertThat(preview.sendBlocked()).isTrue();
        }
        var saved=contactDrafts.save(alice,c.id(),"\"0\"",contactInput("professional",null,null));
        var edited=contactDrafts.save(alice,c.id(),"\"1\"",contactInput("professional","Objet relu","Message édité"));
        var staleInput=contactInput("professional",null,null);
        reviews.save(alice,c.id(),"\"2\"",request(accepted(c.id())),true);
        assertThatThrownBy(()->contactDrafts.preview(alice,c.id(),staleInput)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("CONTACT_PUBLICATION_CHANGED"));
        var history=contactDrafts.history(alice,c.id(),0).items();
        assertThat(history).hasSize(2);assertThat(history.getFirst()).isEqualTo(edited);assertThat(history.getLast()).isEqualTo(saved);
        assertThatThrownBy(()->contactDrafts.save(alice,c.id(),"\"1\"",contactInput("direct",null,null)))
            .isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("CONTACT_DRAFT_CHANGED"));
        var route="/api/v1/candidates/"+c.id()+"/contact-draft";
        mvc.perform(put(route).with(as("alice")).header("If-Match","\"2\"").contentType("application/json").content(json.writeValueAsString(contactInput("direct",null,null)))).andExpect(status().isForbidden());
        mvc.perform(get(route).with(as("alice"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
    }
    @Test void contactDraftsBlockMissingVariablesAndRejectUnprovenSkillsAndHeaderInjection() {
        var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITMENT_LEAD')",alice,poolA);
        var missing=contactDrafts.preview(alice,c.id(),new com.skillhunters.communication.ContactDrafts.Input(contactPublication(),"professional",Map.of(),List.of(),null,null));
        assertThat(missing.missingFields()).contains("jobTitle","matchingSkills","recipient","unsubscribeOrObjectionLink");assertThat(missing.sendBlocked()).isTrue();
        var input=contactInput("professional",null,null);
        assertThatThrownBy(()->contactDrafts.preview(alice,c.id(),new com.skillhunters.communication.ContactDrafts.Input(contactPublication(),"professional",input.variables(),List.of(999),null,null))).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->contactDrafts.preview(alice,c.id(),contactInput("professional","Sujet\r\nBcc: injected@example.com",null))).isInstanceOf(Rejected.class);
        var vars=new HashMap<>(input.variables());vars.put("jobTitle","Poste\nBcc: injected@example.com");
        assertThatThrownBy(()->contactDrafts.preview(alice,c.id(),new com.skillhunters.communication.ContactDrafts.Input(contactPublication(),"professional",vars,List.of(0),null,null))).isInstanceOf(Rejected.class);
        var unresolved=contactDrafts.preview(alice,c.id(),contactInput("professional",null,"Bonjour {{invented}}"));assertThat(unresolved.missingFields()).contains("unresolvedVariables");
    }
    @Test void contactDraftsRecheckContactRoleOwnershipRetentionAndCascade() {
        var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        assertThatThrownBy(()->contactDrafts.context(alice,c.id())).isInstanceOf(Rejected.class);
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITMENT_LEAD')",alice,poolA);
        contactDrafts.save(alice,c.id(),"\"0\"",contactInput("professional",null,null));
        assertThatThrownBy(()->contactDrafts.history(admin,c.id(),0)).isInstanceOf(Rejected.class);
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITMENT_LEAD')",admin,poolA);
        assertThat(contactDrafts.history(admin,c.id(),0).items()).isEmpty();
        db.update("DELETE FROM membership WHERE user_id=? AND role='RECRUITMENT_LEAD'",alice);
        assertThatThrownBy(()->contactDrafts.history(alice,c.id(),0)).isInstanceOf(Rejected.class);
        db.update("UPDATE document_import SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE id=?",id);
        assertThatThrownBy(()->contactDrafts.history(admin,c.id(),0)).isInstanceOf(Rejected.class);
        contactDrafts.cleanup();assertThat(db.queryForObject("SELECT COUNT(*) FROM contact_draft_revision",Integer.class)).isZero();
    }

    @Test void candidateReadAndMutationAuditContainsPurposeWithoutCvText() throws Exception {
        var c=create();
        long before=db.queryForObject("SELECT COUNT(*) FROM governance_audit WHERE object_id=? AND action='CANDIDATE_READ'",Long.class,c.id());
        mvc.perform(get("/api/v1/candidates/"+c.id()).with(as("alice"))).andExpect(status().isOk());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM governance_audit WHERE object_id=? AND action='CANDIDATE_READ'",Long.class,c.id())).isEqualTo(before+1);
        var event=db.queryForMap("SELECT actor_id,pool_id,purpose,action FROM governance_audit WHERE object_id=? AND action='CANDIDATE_CREATED'",c.id());
        assertThat(event.get("purpose")).isEqualTo("Test");assertThat(event.get("actor_id")).isEqualTo(alice);assertThat(event.get("pool_id")).isEqualTo(poolA);
        long count=db.queryForObject("SELECT COUNT(*) FROM governance_audit WHERE object_id=?",Long.class,c.id());
        mvc.perform(get("/api/v1/candidates/"+c.id()).with(as("admin"))).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM governance_audit WHERE object_id=?",Long.class,c.id())).isEqualTo(count);
        db.update("DELETE FROM document_audit WHERE import_id=?",id);
        db.update("DELETE FROM document_import WHERE id=?",id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM governance_audit WHERE object_id=?",Long.class,c.id())).isEqualTo(count);
    }

    @Autowired com.skillhunters.governance.CandidateGovernance governance;
    @Autowired org.springframework.transaction.support.TransactionTemplate transaction;
    private com.skillhunters.governance.CandidateGovernance.Edit legalEdit() {
        var policy=db.queryForObject("SELECT id FROM import_policy LIMIT 1",UUID.class);
        return new com.skillhunters.governance.CandidateGovernance.Edit(policy,"Import synthétique","Justification vérifiée par conformité","notice-1","preuve-synthetique-1",java.time.OffsetDateTime.now().minusDays(1));
    }
    private void complianceRole() { db.update("INSERT INTO global_role(user_id,role) VALUES (?,'COMPLIANCE')",alice); }
    private void contactRole() { db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITMENT_LEAD')",alice,poolA); }
    @Test void governanceRequiresComplianceAndMembershipAndVersionedEvidence() throws Exception {
        var c=create();
        assertThatThrownBy(()->governance.get(alice,c.id())).isInstanceOf(Rejected.class);
        db.update("INSERT INTO global_role(user_id,role) VALUES (?,'COMPLIANCE')",admin);
        assertThatThrownBy(()->governance.get(admin,c.id())).isInstanceOf(Rejected.class);
        complianceRole();contactRole();
        assertThat(governance.get(alice,c.id()).justification()).isNull();
        assertThat(governance.eligibility(alice,c.id()).allowed()).isFalse();
        var state=governance.save(alice,c.id(),"\"0\"",legalEdit());
        assertThat(state.version()).isEqualTo(1);assertThat(state.opposed()).isFalse();
        assertThat(governance.eligibility(alice,c.id()).allowed()).isTrue();
        assertThatThrownBy(()->governance.save(alice,c.id(),"\"0\"",legalEdit())).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->governance.save(alice,c.id(),"\"1\"",new com.skillhunters.governance.CandidateGovernance.Edit(state.policyId(),"Source","","v1","preuve",java.time.OffsetDateTime.now().minusDays(1)))).isInstanceOf(Rejected.class);
        mvc.perform(post("/api/v1/candidates/"+c.id()+"/governance/opposition").with(as("alice")).header("If-Match","\"1\"")).andExpect(status().isForbidden());
        governance.oppose(alice,c.id(),"\"1\"");
        assertThat(governance.save(alice,c.id(),"\"2\"",legalEdit()).opposed()).isTrue();
        assertThat(governance.history(alice,c.id(),3L).items()).extracting(com.skillhunters.governance.CandidateGovernance.State::version).containsExactly(2L,1L);
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        assertThatThrownBy(()->governance.get(alice,c.id())).isInstanceOf(Rejected.class);
    }
    @Test void opaqueOppositionBlocksExistingDraftAndIsIdempotentWithoutDisclosingCandidate() throws Exception {
        var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);contactRole();complianceRole();
        contactDrafts.save(alice,c.id(),"\"0\"",contactInput("professional",null,null));
        governance.save(alice,c.id(),"\"0\"",legalEdit());
        transaction.executeWithoutResult(t->governance.requireContactAllowed(alice,c.id()));
        var link=governance.issue(alice,c.id());String token=link.url().split("#opposition=")[1];
        assertThat(token).hasSize(43);assertThat(link.url()).doesNotContain(c.id().toString());
        assertThat(db.queryForObject("SELECT token_hash FROM contact_opposition_token WHERE candidate_id=?",String.class,c.id())).doesNotContain(token).hasSize(64);
        mvc.perform(get("/api/v1/public/contact-opposition")).andExpect(status().isUnauthorized());
        assertThat(governance.get(alice,c.id()).opposed()).isFalse();
        for(int i=0;i<2;i++)mvc.perform(post("/api/v1/public/contact-opposition").contentType("application/json").content("{\"token\":\""+token+"\"}")).andExpect(status().isNoContent()).andExpect(content().string("")).andExpect(header().string("Cache-Control","no-store"));
        assertThat(governance.get(alice,c.id()).opposed()).isTrue();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM governance_audit WHERE object_id=? AND action='CONTACT_OPPOSED'",Integer.class,c.id())).isEqualTo(1);
        assertThatThrownBy(()->transaction.executeWithoutResult(t->governance.requireContactAllowed(alice,c.id()))).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("CONTACT_OPPOSED"));
        assertThatThrownBy(()->contactDrafts.history(alice,c.id(),0)).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->contactDrafts.save(alice,c.id(),"\"1\"",contactInput("professional",null,null))).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->governance.issue(alice,c.id())).isInstanceOf(Rejected.class);
    }
    @Test void oppositionLinksExpireAndDisabledPolicyOrDeletedSourceFailClosed() throws Exception {
        var c=create();contactRole();complianceRole();governance.save(alice,c.id(),"\"0\"",legalEdit());
        var token=governance.issue(alice,c.id()).url().split("#opposition=")[1];
        db.update("UPDATE contact_opposition_token SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE candidate_id=?",c.id());
        mvc.perform(post("/api/v1/public/contact-opposition").contentType("application/json").content("{\"token\":\""+token+"\"}")).andExpect(status().isGone());
        assertThat(governance.get(alice,c.id()).opposed()).isFalse();
        db.update("UPDATE import_policy SET enabled=FALSE");assertThat(governance.eligibility(alice,c.id()).reason()).isEqualTo("GOVERNANCE_POLICY_DISABLED");
        assertThatThrownBy(()->governance.issue(alice,c.id())).isInstanceOf(Rejected.class);
        db.update("UPDATE import_policy SET enabled=TRUE");var active=governance.issue(alice,c.id()).url().split("#opposition=")[1];
        db.update("DELETE FROM document_audit WHERE import_id=?",id);db.update("DELETE FROM document_import WHERE id=?",id);
        assertThatThrownBy(()->governance.redeem(active)).isInstanceOf(Rejected.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM contact_opposition_token WHERE candidate_id=?",Integer.class,c.id())).isZero();
    }

    @Autowired com.skillhunters.communication.ContactDelivery deliveries;
    @MockitoBean com.skillhunters.communication.ContactTransport smtp;
    @BeforeEach void syntheticSmtp(){when(smtp.sender()).thenReturn("no-reply@example.com");when(smtp.enabled()).thenReturn(true);when(smtp.send(any())).thenReturn(new com.skillhunters.communication.ContactTransport.Result("ACCEPTED","SMTP_ACCEPTED"));}
    record ReadyContact(UUID candidate,com.skillhunters.communication.ContactDrafts.Draft draft) {}
    ReadyContact readyContact(){
        var c=create();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);contactRole();complianceRole();
        db.update("UPDATE candidate_contact SET email='candidate@example.com' WHERE candidate_id=?",c.id());
        governance.save(alice,c.id(),"\"0\"",legalEdit());
        var input=contactInput("professional",null,null);var vars=new HashMap<>(input.variables());vars.put("unsubscribeOrObjectionLink",governance.issue(alice,c.id()).url());
        var d=contactDrafts.save(alice,c.id(),"\"0\"",new com.skillhunters.communication.ContactDrafts.Input(input.publicationId(),input.templateId(),vars,input.skillIndices(),null,null));return new ReadyContact(c.id(),d);
    }
    com.skillhunters.communication.ContactDelivery.Approval approval(com.skillhunters.communication.ContactDrafts.Draft d){return new com.skillhunters.communication.ContactDelivery.Approval(d.version(),d.recipient(),d.subject(),d.body(),true);}
    @Test void exactApprovalAndRepeatedSendProduceOneSmtpAttempt() throws Exception {
        var f=readyContact();var a=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        assertThat(a.contentHash()).hasSize(64);assertThat(a.status()).isEqualTo("APPROVED");
        mvc.perform(post("/api/v1/candidates/"+f.candidate()+"/contact-deliveries/"+a.id()+"/send").with(as("alice"))).andExpect(status().isForbidden());
        assertThat(deliveries.send(alice,f.candidate(),a.id()).status()).isEqualTo("ACCEPTED");
        assertThat(deliveries.send(alice,f.candidate(),a.id()).status()).isEqualTo("ACCEPTED");
        verify(smtp,times(1)).send(argThat(e->e.recipient().equals(f.draft().recipient())&&e.body().equals(f.draft().body())&&e.subject().equals(f.draft().subject())&&e.messageId().equals(a.messageId())));
        assertThat(deliveries.approve(alice,f.candidate(),approval(f.draft())).id()).isEqualTo(a.id());
        assertThatThrownBy(()->deliveries.get(admin,f.candidate(),a.id())).isInstanceOf(Rejected.class);
    }
    @Test void draftEditInvalidatesPriorApprovalAndRequiresExactFreshReview(){
        var f=readyContact();var a=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        var d=f.draft();var edited=contactDrafts.save(alice,f.candidate(),"\"1\"",new com.skillhunters.communication.ContactDrafts.Input(d.publicationId(),d.templateId(),d.variables(),List.of(0),"Objet relu modifié",d.body()));
        assertThat(deliveries.send(alice,f.candidate(),a.id()).status()).isEqualTo("INVALIDATED");verify(smtp,never()).send(any());
        assertThatThrownBy(()->deliveries.approve(alice,f.candidate(),approval(d))).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->deliveries.approve(alice,f.candidate(),new com.skillhunters.communication.ContactDelivery.Approval(edited.version(),edited.recipient(),edited.subject(),edited.body(),false))).isInstanceOf(Rejected.class);
        var next=deliveries.approve(alice,f.candidate(),approval(edited));assertThat(next.contentHash()).isNotEqualTo(a.contentHash());
    }
    @Test void oppositionAndChangedRecipientAreRecheckedAtSend(){
        var f=readyContact();var a=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        governance.oppose(alice,f.candidate(),"\"1\"");
        assertThatThrownBy(()->deliveries.send(alice,f.candidate(),a.id())).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("CONTACT_OPPOSED"));
        verify(smtp,never()).send(any());assertThat(deliveries.get(alice,f.candidate(),a.id()).status()).isEqualTo("INVALIDATED");
    }
    @Test void changedRecipientExpiresApprovalBeforeTransmission(){
        var f=readyContact();var a=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        db.update("UPDATE candidate_contact SET email='changed@example.com' WHERE candidate_id=?",f.candidate());
        assertThatThrownBy(()->deliveries.send(alice,f.candidate(),a.id())).isInstanceOf(Rejected.class);verify(smtp,never()).send(any());
    }
    @Test void timeoutOrInterruptedAttemptNeverTriggersAutomaticResend(){
        var f=readyContact();var a=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        when(smtp.send(any())).thenThrow(new IllegalStateException("simulated timeout"));
        assertThat(deliveries.send(alice,f.candidate(),a.id()).status()).isEqualTo("UNCERTAIN");
        assertThat(deliveries.send(alice,f.candidate(),a.id()).status()).isEqualTo("UNCERTAIN");verify(smtp,times(1)).send(any());
        db.update("UPDATE contact_delivery SET status='SENDING',attempted_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE id=?",a.id());
        assertThat(deliveries.get(alice,f.candidate(),a.id()).status()).isEqualTo("UNCERTAIN");
        deliveries.send(alice,f.candidate(),a.id());verify(smtp,times(1)).send(any());
    }
    @Test void expiredLinkAndRevokedAccessPreventSmtp(){
        var f=readyContact();var a=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        db.update("UPDATE contact_opposition_token SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE candidate_id=?",f.candidate());
        assertThatThrownBy(()->deliveries.send(alice,f.candidate(),a.id())).isInstanceOf(Rejected.class);verify(smtp,never()).send(any());
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        assertThatThrownBy(()->deliveries.get(alice,f.candidate(),a.id())).isInstanceOf(Rejected.class);
    }
    @Test void simultaneousRequestsDoNotDuplicateSmtp() throws Exception {
        var f=readyContact();var a=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var one=executor.submit(()->deliveries.send(alice,f.candidate(),a.id()));var two=executor.submit(()->deliveries.send(alice,f.candidate(),a.id()));
            assertThat(one.get(15,java.util.concurrent.TimeUnit.SECONDS).status()).isIn("ACCEPTED","SENDING");assertThat(two.get(15,java.util.concurrent.TimeUnit.SECONDS).status()).isIn("ACCEPTED","SENDING");
        }
        verify(smtp,times(1)).send(any());
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="SH25_SMTP_TEST_HOST",matches=".+")
    void approvedMessageReallyReachesMailpitOnce() throws Exception {
        var f=readyContact();var a=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        var real=new com.skillhunters.communication.SmtpContactTransport(true,System.getenv("SH25_SMTP_TEST_HOST"),1025,"","",false,"no-reply@example.com");
        when(smtp.send(any())).thenAnswer(invocation->real.send(invocation.getArgument(0)));
        assertThat(deliveries.send(alice,f.candidate(),a.id()).status()).isEqualTo("ACCEPTED");
        deliveries.send(alice,f.candidate(),a.id());verify(smtp,times(1)).send(any());
        var client=java.net.http.HttpClient.newHttpClient();String base=System.getenv("SH25_SMTP_TEST_HTTP");
        var list=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/v1/messages")).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
        assertThat(list.statusCode()).isEqualTo(200);var messages=json.readTree(list.body()).get("messages");
        int count=0;
        for(var m:messages){
            var response=client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+"/api/v1/message/"+m.get("ID").asText())).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            var body=json.readTree(response.body());if(body.get("MessageID").asText().replace("<","").replace(">","").equals(a.messageId().replace("<","").replace(">",""))){
                count++;assertThat(body.get("Text").asText().replace("\r\n","\n").stripTrailing()).isEqualTo(f.draft().body().stripTrailing());
                assertThat(body.get("Subject").asText()).isEqualTo(f.draft().subject());
                assertThat(body.get("To").get(0).get("Address").asText()).isEqualTo(f.draft().recipient());
                assertThat(body.get("HTML").asText()).isEmpty();
            }
        }
        assertThat(count).isEqualTo(1);
        assertThat(contactHistory.detail(alice,f.candidate(),a.id()).events()).extracting(e->e.status()).containsExactly("APPROVED","SENDING","ACCEPTED");
    }

    @Autowired com.skillhunters.communication.ContactHistory contactHistory;
    @Autowired com.skillhunters.communication.ContactJournal contactJournal;

    @Test void contactHistorySharesApprovedMessagesButKeepsPreparedDraftsPrivate() throws Exception {
        var f=readyContact();
        assertThat(contactHistory.list(alice,f.candidate(),null,null).prepared().draftVersion()).isEqualTo(1);
        assertThatThrownBy(()->contactHistory.list(admin,f.candidate(),null,null)).isInstanceOf(Rejected.class);
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITMENT_LEAD')",admin,poolA);
        assertThat(contactHistory.list(admin,f.candidate(),null,null).prepared()).isNull();
        var a=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        deliveries.send(alice,f.candidate(),a.id());
        var page=contactHistory.list(admin,f.candidate(),null,null);
        assertThat(page.prepared()).isNull();assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().status()).isEqualTo("ACCEPTED");
        var detail=contactHistory.detail(admin,f.candidate(),a.id());
        assertThat(detail.body()).isEqualTo(f.draft().body());
        assertThat(detail.events()).extracting(e->e.status()).containsExactly("APPROVED","SENDING","ACCEPTED");
        assertThatThrownBy(()->deliveries.send(admin,f.candidate(),a.id())).isInstanceOf(Rejected.class);
        governance.oppose(alice,f.candidate(),"\"1\"");
        assertThat(contactHistory.detail(alice,f.candidate(),a.id()).body()).isEqualTo(f.draft().body());
        mvc.perform(get(candidateUrl(f.candidate())+"/contact-history").with(as("alice")))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        db.update("DELETE FROM membership WHERE user_id=?",admin);
        assertThatThrownBy(()->contactHistory.detail(admin,f.candidate(),a.id())).isInstanceOf(Rejected.class);
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITMENT_LEAD')",admin,poolB);
        assertThatThrownBy(()->contactHistory.list(admin,f.candidate(),null,null)).isInstanceOf(Rejected.class);
        verify(smtp,times(1)).send(any());
    }
    @Test void contactHistoryPaginatesTiedDatesAndBindsCursorToFilterAndCandidate() {
        var f=readyContact();var a=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        for(int i=2;i<=29;i++)db.update("INSERT INTO contact_delivery(id,candidate_id,actor_id,pool_id,draft_version,content_hash,sender,recipient,reply_to,subject,body,message_id,status,approved_at,expires_at,history_expires_at) SELECT ?,candidate_id,actor_id,pool_id,?,content_hash,sender,recipient,reply_to,subject,body,?,status,approved_at,expires_at,history_expires_at FROM contact_delivery WHERE id=?",UUID.randomUUID(),i,"synthetic-"+i,a.id());
        var first=contactHistory.list(alice,f.candidate(),null,"APPROVED");
        var second=contactHistory.list(alice,f.candidate(),first.nextCursor(),"APPROVED");
        assertThat(first.items()).hasSize(25);assertThat(second.items()).hasSize(4);assertThat(second.nextCursor()).isNull();
        var ids=new HashSet<UUID>();first.items().forEach(x->ids.add(x.id()));second.items().forEach(x->ids.add(x.id()));assertThat(ids).hasSize(29);
        assertThat(contactHistory.list(alice,f.candidate(),null,"ACCEPTED").items()).isEmpty();
        assertThatThrownBy(()->contactHistory.list(alice,f.candidate(),first.nextCursor(),null)).isInstanceOf(Rejected.class);
        String bad=Base64.getUrlEncoder().encodeToString((f.candidate()+"|invalid-date|"+a.id()+"|").getBytes());
        assertThatThrownBy(()->contactHistory.list(alice,f.candidate(),bad,null)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("INVALID_CONTACT_HISTORY_FILTER"));
        assertThatThrownBy(()->contactHistory.list(alice,f.candidate(),"%%%",null)).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->contactHistory.list(alice,f.candidate(),null,"DELIVERED")).isInstanceOf(Rejected.class);
        verify(smtp,never()).send(any());
    }
    @Test void expiredContactPayloadsDisappearBeforePurgeAndCannotResurrectAttempt() {
        var f=readyContact();var a=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        db.update("UPDATE contact_delivery SET history_expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE id=?",a.id());
        db.update("UPDATE contact_draft_revision SET created_at=CURRENT_TIMESTAMP - INTERVAL '100' DAY WHERE candidate_id=?",f.candidate());
        assertThat(contactHistory.list(alice,f.candidate(),null,null).items()).isEmpty();
        assertThatThrownBy(()->contactHistory.detail(alice,f.candidate(),a.id())).isInstanceOf(Rejected.class);
        assertThat(contactDrafts.history(alice,f.candidate(),0).items()).isEmpty();
        assertThat(contactDrafts.history(alice,f.candidate(),0).latestVersion()).isEqualTo(1);
        contactJournal.cleanup();
        assertThat(db.queryForObject("SELECT body FROM contact_delivery WHERE id=?",String.class,a.id())).isEmpty();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM contact_delivery_event WHERE delivery_id=?",Integer.class,a.id())).isZero();
        assertThat(db.queryForObject("SELECT payload_json FROM contact_draft_revision WHERE candidate_id=?",String.class,f.candidate())).isEqualTo("{}");
        assertThatThrownBy(()->deliveries.send(alice,f.candidate(),a.id())).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->deliveries.approve(alice,f.candidate(),approval(f.draft()))).isInstanceOf(Rejected.class);
        var d=f.draft();var fresh=contactDrafts.save(alice,f.candidate(),"\"1\"",new com.skillhunters.communication.ContactDrafts.Input(d.publicationId(),d.templateId(),d.variables(),List.of(0),null,null));
        assertThat(fresh.version()).isEqualTo(2);
        assertThat(contactHistory.list(alice,f.candidate(),null,null).prepared().draftVersion()).isEqualTo(2);
        verify(smtp,never()).send(any());
    }
    @Test void contactHistoryReconcilesExpiredAndInterruptedStatesExactlyOnce() {
        var f=readyContact();var a=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        db.update("UPDATE contact_delivery SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE id=?",a.id());
        assertThat(contactHistory.detail(alice,f.candidate(),a.id()).summary().status()).isEqualTo("INVALIDATED");
        assertThat(contactHistory.detail(alice,f.candidate(),a.id()).events()).hasSize(2);
        db.update("UPDATE contact_delivery SET status='SENDING',attempted_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE id=?",a.id());
        assertThat(contactHistory.detail(alice,f.candidate(),a.id()).summary().status()).isEqualTo("UNCERTAIN");
        assertThat(contactHistory.detail(alice,f.candidate(),a.id()).events()).hasSize(3);
        verify(smtp,never()).send(any());
    }
    @Test void sourceExpiryBlocksHistoryAndPurgesDeliveryContent() {
        var f=readyContact();var a=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        assertThat(contactHistory.detail(alice,f.candidate(),a.id()).summary().retainedUntil()).isBeforeOrEqualTo(candidates.get(alice,f.candidate()).expiresAt());
        db.update("UPDATE document_import SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE id=?",id);
        assertThatThrownBy(()->contactHistory.list(alice,f.candidate(),null,null)).isInstanceOf(Rejected.class);
        contactJournal.cleanup();
        assertThat(db.queryForObject("SELECT body FROM contact_delivery WHERE id=?",String.class,a.id())).isEmpty();
        db.update("DELETE FROM document_audit WHERE import_id=?",id);db.update("DELETE FROM document_import WHERE id=?",id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM contact_delivery",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM contact_delivery_event",Integer.class)).isZero();
    }


    @Autowired com.skillhunters.talent.CandidateLineage lineage;
    com.skillhunters.talent.CandidateService.Candidate anotherCandidate(UUID pool,String email) throws Exception {
        UUID policy=db.queryForObject("SELECT policy_id FROM document_import WHERE id=?",UUID.class,id);
        mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("files","another-synthetic.md","text/markdown","Compétence : Java 2,5\nExpérience : autre source synthétique".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .file(new MockMultipartFile("basis","","application/json",("{\"source\":\"Synthetic\",\"purpose\":\"Test\",\"basisCode\":\"TEST_ONLY\",\"retentionPolicyId\":\""+policy+"\"}").getBytes()))
            .param("poolId",pool.toString()).header("Idempotency-Key","lineage-"+UUID.randomUUID()).with(as("alice")).with(csrf())).andExpect(status().isAccepted());
        var source=db.queryForObject("SELECT id FROM document_import WHERE id<>? ORDER BY created_at DESC LIMIT 1",UUID.class,id);
        analysis.runOnce();var c=candidates.create(alice,source,extraction.prepare(alice,source).id());reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        return candidates.update(alice,c.id(),"\"2\"",json.readTree("{\"contact\":{\"email\":\""+email+"\",\"phone\":null},\"reason\":\"Vérification synthétique\"}"));
    }
    void verifyEmail(UUID c){var state=candidates.get(alice,c);lineage.verify(alice,c,"\""+state.version()+"\"",true);}
    com.skillhunters.talent.CandidateLineage.Merge mergeRequest(UUID first,UUID second,UUID active){
        return new com.skillhunters.talent.CandidateLineage.Merge(second,candidates.get(alice,first).version(),candidates.get(alice,second).version(),lineage.state(alice,first).version(),lineage.state(alice,second).version(),active,true,"Sources synthétiques comparées");
    }
    @Test void verifiedEmailSuggestsButNeverMergesAutomaticallyAndContactChangesInvalidateVerification() throws Exception {
        var f=readyContact();var other=anotherCandidate(poolA,"CANDIDATE@example.com");
        assertThatThrownBy(()->lineage.suggestions(alice,f.candidate(),null)).isInstanceOf(Rejected.class);
        verifyEmail(f.candidate());assertThat(lineage.suggestions(alice,f.candidate(),null).items()).isEmpty();
        verifyEmail(other.id());assertThat(lineage.suggestions(alice,f.candidate(),null).items()).extracting(x->x.candidateId()).containsExactly(other.id());
        assertThat(lineage.state(alice,f.candidate()).members()).hasSize(1);
        var request=mergeRequest(f.candidate(),other.id(),f.candidate());
        candidates.update(alice,other.id(),"\""+other.version()+"\"",json.readTree("{\"contact\":{\"email\":\"changed@example.com\",\"phone\":null},\"reason\":\"Correction synthétique\"}"));
        assertThat(lineage.state(alice,other.id()).emailVerified()).isFalse();
        assertThat(lineage.suggestions(alice,f.candidate(),null).items()).isEmpty();
        assertThatThrownBy(()->lineage.merge(alice,f.candidate(),request)).isInstanceOf(Rejected.class);
        verify(smtp,never()).send(any());
    }
    @Test void mergePreservesSourcesRecordsImmutableReferencesAndSelectsOnlyOneActiveSearchResult() throws Exception {
        var f=readyContact();var other=anotherCandidate(poolA,"candidate@example.com");verifyEmail(f.candidate());verifyEmail(other.id());
        var receipt=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        var sourceRevision=candidates.get(alice,f.candidate()).revisionId();var publication=reviews.publication(alice,f.candidate()).revisionId();
        var request=mergeRequest(f.candidate(),other.id(),other.id());var state=lineage.merge(alice,f.candidate(),request);
        assertThat(state.members()).hasSize(2);assertThat(state.members().stream().filter(x->x.active())).hasSize(1);
        assertThat(candidates.get(alice,f.candidate()).activeVersion()).isFalse();
        assertThat(candidates.list(alice,poolA,0,25).items()).extracting(x->x.id()).containsExactly(other.id());
        assertThat(search.search(alice,searchQuery(false,"Java",null)).items()).extracting(x->x.candidateId()).containsExactly(other.id());
        assertThat(deliveries.get(alice,f.candidate(),receipt.id()).status()).isEqualTo("INVALIDATED");
        assertThat(lineage.history(alice,f.candidate(),null).items().getFirst().sourceRevisionId()).isEqualTo(sourceRevision);
        assertThat(lineage.history(alice,f.candidate(),null).items().getFirst().publicationId()).isEqualTo(publication);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_import",Integer.class)).isEqualTo(2);
        assertThatThrownBy(()->candidates.update(alice,f.candidate(),"\"2\"",json.readTree("{\"displayName\":\"Changed\",\"reason\":\"Test\"}"))).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->reviews.save(alice,f.candidate(),"\"2\"",request(accepted(f.candidate())),true)).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->lineage.merge(alice,f.candidate(),request)).isInstanceOf(Rejected.class);
        var next=lineage.activate(alice,f.candidate(),new com.skillhunters.talent.CandidateLineage.Activation(state.version(),f.candidate(),true,"Choix explicite de la source conservée"));
        assertThat(candidates.get(alice,f.candidate()).activeVersion()).isTrue();assertThat(candidates.get(alice,other.id()).activeVersion()).isFalse();
        assertThat(lineage.history(alice,f.candidate(),null).items()).hasSize(2);assertThat(next.version()).isEqualTo(state.version()+1);
        verify(smtp,never()).send(any());
    }
    @Test void lineageDoesNotRevealOtherPoolsOrPermitAdminOnlyAndRequiresCsrfAndConfirmation() throws Exception {
        var f=readyContact();verifyEmail(f.candidate());
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITMENT_LEAD')",alice,poolB);
        var other=anotherCandidate(poolB,"candidate@example.com");verifyEmail(other.id());
        assertThat(lineage.suggestions(alice,f.candidate(),null).items()).isEmpty();
        assertThatThrownBy(()->lineage.merge(alice,f.candidate(),mergeRequest(f.candidate(),other.id(),other.id()))).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->lineage.state(admin,f.candidate())).isInstanceOf(Rejected.class);
        mvc.perform(get(candidateUrl(f.candidate())+"/lineage").with(as("alice"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post(candidateUrl(f.candidate())+"/lineage/verify-email").with(as("alice")).header("If-Match","\"2\"").contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isForbidden());
        assertThatThrownBy(()->lineage.verify(alice,f.candidate(),"\"2\"",false)).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->lineage.verify(alice,f.candidate(),"\"0\"",true)).isInstanceOf(Rejected.class);
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        assertThatThrownBy(()->lineage.suggestions(alice,f.candidate(),null)).isInstanceOf(Rejected.class);
    }
    @Test void oppositionFollowsMergedIdentityAndSurvivesRemovalOfOpposedSource() throws Exception {
        var f=readyContact();var other=anotherCandidate(poolA,"candidate@example.com");verifyEmail(f.candidate());verifyEmail(other.id());
        governance.oppose(alice,f.candidate(),"\"1\"");
        lineage.merge(alice,f.candidate(),mergeRequest(f.candidate(),other.id(),other.id()));
        assertThat(governance.eligibility(alice,other.id()).reason()).isEqualTo("CONTACT_OPPOSED");
        assertThat(governance.get(alice,other.id()).opposed()).isTrue();
        db.update("DELETE FROM document_audit WHERE import_id=?",id);db.update("DELETE FROM document_import WHERE id=?",id);
        assertThat(governance.eligibility(alice,other.id()).reason()).isEqualTo("CONTACT_OPPOSED");
        assertThat(governance.get(alice,other.id()).opposed()).isTrue();
        assertThat(lineage.state(alice,other.id()).members()).hasSize(1);
    }
    @Test void laterPublicOppositionOnInactiveSourceBlocksActiveSourceAndPendingAttemptBlocksMerge() throws Exception {
        var f=readyContact();var other=anotherCandidate(poolA,"candidate@example.com");verifyEmail(f.candidate());verifyEmail(other.id());
        var token=governance.issue(alice,f.candidate()).url().split("#opposition=")[1];
        var receipt=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        db.update("UPDATE contact_delivery SET status='SENDING',attempted_at=CURRENT_TIMESTAMP WHERE id=?",receipt.id());
        assertThatThrownBy(()->lineage.merge(alice,f.candidate(),mergeRequest(f.candidate(),other.id(),other.id()))).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("CONTACT_ATTEMPT_PENDING"));
        db.update("UPDATE contact_delivery SET status='UNCERTAIN' WHERE id=?",receipt.id());
        lineage.merge(alice,f.candidate(),mergeRequest(f.candidate(),other.id(),other.id()));governance.redeem(token);
        assertThat(governance.eligibility(alice,other.id()).reason()).isEqualTo("CONTACT_OPPOSED");
        assertThat(governance.get(alice,other.id()).opposed()).isTrue();
        verify(smtp,never()).send(any());
    }
    @Test void expiredLinkedSourcesStayHiddenAndAreNeverAutomaticallyReactivated() throws Exception {
        var f=readyContact();var other=anotherCandidate(poolA,"candidate@example.com");verifyEmail(f.candidate());verifyEmail(other.id());
        lineage.merge(alice,f.candidate(),mergeRequest(f.candidate(),other.id(),other.id()));
        db.update("UPDATE document_import SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE id=?",other.importId());
        assertThat(lineage.state(alice,f.candidate()).members()).hasSize(1);
        assertThat(candidates.list(alice,poolA,0,25).items()).isEmpty();
        assertThatThrownBy(()->lineage.state(alice,other.id())).isInstanceOf(Rejected.class);
        lineage.cleanup();assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_lineage_event WHERE candidate_id=?",Integer.class,other.id())).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_contact_verification WHERE candidate_id=?",Integer.class,other.id())).isZero();
    }
    @Test void concurrentActiveChoicesHaveOneWinnerAndKeepOneActiveVersion() throws Exception {
        var f=readyContact();var other=anotherCandidate(poolA,"candidate@example.com");verifyEmail(f.candidate());verifyEmail(other.id());
        var state=lineage.merge(alice,f.candidate(),mergeRequest(f.candidate(),other.id(),other.id()));
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITMENT_LEAD')",admin,poolA);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)){
            java.util.concurrent.Callable<Boolean> one=()->{try{lineage.activate(alice,f.candidate(),new com.skillhunters.talent.CandidateLineage.Activation(state.version(),f.candidate(),true,"Choix un"));return true;}catch(Rejected e){return false;}};
            java.util.concurrent.Callable<Boolean> two=()->{try{lineage.activate(admin,other.id(),new com.skillhunters.talent.CandidateLineage.Activation(state.version(),other.id(),true,"Choix deux"));return true;}catch(Rejected e){return false;}};
            var a=executor.submit(one);var b=executor.submit(two);assertThat(List.of(a.get(15,java.util.concurrent.TimeUnit.SECONDS),b.get(15,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
        assertThat(lineage.state(alice,f.candidate()).members().stream().filter(x->x.active())).hasSize(1);
    }

    @Autowired com.skillhunters.retention.RetentionService retention;
    void retryErasure(){db.update("UPDATE erasure_tombstone SET next_attempt_at=CURRENT_TIMESTAMP");retention.runOnce();}
    @Test void erasureRemovesOriginalAndAllCandidateDerivativesButKeepsMinimalProof() throws Exception {
        var f=readyContact();verifyEmail(f.candidate());
        var delivery=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        tracking.save(alice,f.candidate(),"\"0\"",follow(com.skillhunters.talent.CandidateTracking.Status.TO_REVIEW,"Synthetic",List.of("java"),List.of()));
        scored.create(alice,scoredRequest(false,"Java"));
        assertThat(storage.objects).isNotEmpty();
        assertThat(retention.request(alice,id,true).erasureState()).isEqualTo("PENDING");
        assertThatThrownBy(()->candidates.get(alice,f.candidate())).isInstanceOf(Rejected.class);
        mvc.perform(get("/api/v1/imports/"+id).with(as("alice"))).andExpect(status().isNotFound());
        retention.runOnce();
        assertThat(retention.get(alice,id).erasureState()).isEqualTo("COMPLETE");
        assertThat(retention.get(alice,id).categories()).contains("ORIGINAL_ALL_VERSIONS","CONTACT_MESSAGES");
        for(var table:List.of("document_import","document_analysis","extraction_revision","candidate_profile","candidate_contact","candidate_revision","candidate_assertion_index","candidate_tracking","contact_delivery","contact_draft_revision","candidate_identity_member","candidate_contact_verification","scored_search_item"))
            assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).as(table).isZero();
        assertThat(storage.objects).isEmpty();assertThat(erasureLedger.records).containsKey(id);
        String proof=json.writeValueAsString(retention.proofs(alice,poolA,null));
        assertThat(proof).contains(id.toString(),"COMPLETE").doesNotContain("synthetic.md","candidate@example.com","Java","Compétence");
        assertThatThrownBy(()->retentionGuard.replayAllowed("IMPORT",alice,poolA,"extraction-test-key")).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("SOURCE_ERASED"));
    }
    @Test void boundedHoldPreservesExpiredPayloadsWithoutExtendingAccessThenReleases() {
        var f=readyContact();verifyEmail(f.candidate());var a=deliveries.approve(alice,f.candidate(),approval(f.draft()));
        scored.create(alice,scoredRequest(false,"Java"));
        tracking.save(alice,f.candidate(),"\"0\"",follow(com.skillhunters.talent.CandidateTracking.Status.TO_REVIEW,"Synthetic",List.of(),List.of()));
        var until=java.time.OffsetDateTime.now().plusDays(2);
        retention.hold(alice,id,"\"0\"",new com.skillhunters.retention.RetentionService.Hold(until,"Exception synthétique approuvée",true));
        db.update("UPDATE document_import SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY WHERE id=?",id);
        db.update("UPDATE scored_search SET expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY");
        db.update("UPDATE contact_delivery SET history_expires_at=CURRENT_TIMESTAMP - INTERVAL '1' DAY");
        db.update("UPDATE contact_draft_revision SET created_at=CURRENT_TIMESTAMP - INTERVAL '100' DAY");
        contactJournal.cleanup();contactDrafts.cleanup();tracking.cleanup();lineage.cleanup();scored.cleanup();retention.runOnce();
        assertThat(db.queryForObject("SELECT body FROM contact_delivery WHERE id=?",String.class,a.id())).isNotEmpty();
        for(var table:List.of("candidate_tracking","candidate_contact_verification","scored_search_item"))assertThat(db.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class)).as(table).isPositive();
        assertThat(retentionGuard.erased(id)).isFalse();assertThat(storage.objects).isNotEmpty();
        assertThatThrownBy(()->candidates.get(alice,f.candidate())).isInstanceOf(Rejected.class);
        assertThatThrownBy(()->retention.request(alice,id,true)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("RETENTION_HOLD_ACTIVE"));
        retention.hold(alice,id,"\"1\"",new com.skillhunters.retention.RetentionService.Hold(null,"Fin du gel synthétique",true));
        retention.runOnce();assertThat(retention.get(alice,id).erasureState()).isEqualTo("COMPLETE");assertThat(storage.objects).isEmpty();
    }
    @Test void storageOrLedgerFailureNeverAcknowledgesPhysicalErasureAndRetryIsIdempotent() {
        complianceRole();erasureLedger.fail=true;
        assertThatThrownBy(()->retention.request(alice,id,true)).isInstanceOf(Rejected.class);
        retention.runOnce();assertThat(retention.get(alice,id).erasureState()).isEqualTo("PENDING");assertThat(storage.objects).isNotEmpty();
        erasureLedger.fail=false;storage.fail=true;retryErasure();
        assertThat(retention.get(alice,id).resultCode()).isEqualTo("ERASURE_RETRY_REQUIRED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_import",Integer.class)).isEqualTo(1);
        storage.fail=false;retryErasure();retryErasure();
        assertThat(retention.get(alice,id).erasureState()).isEqualTo("COMPLETE");assertThat(storage.objects).isEmpty();
    }
    @Test void restoreReplaysIndependentLedgerAndBlocksDelayedWorker() throws Exception {
        complianceRole();var job=analysis.claim();assertThat(job).isNotNull();
        var row=db.queryForMap("SELECT * FROM document_import WHERE id=?",id);
        var objects=new HashMap<>(storage.objects);
        retention.request(alice,id,true);retention.runOnce();
        assertThat(retention.get(alice,id).erasureState()).isEqualTo("COMPLETE");
        db.update("DELETE FROM erasure_tombstone");
        db.update("INSERT INTO document_import ("+String.join(",",row.keySet())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());
        storage.objects.putAll(objects);
        retention.replay();assertThat(retentionGuard.ready()).isTrue();
        mvc.perform(get("/api/v1/imports/"+id).with(as("alice"))).andExpect(status().isNotFound());
        analysis.complete(job,new DocumentAnalyzer.Result("EXTRACTED",null,List.of(new DocumentAnalyzer.Block("document","Late synthetic text")),List.of(),"test","test"));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM document_analysis",Integer.class)).isZero();
        retention.runOnce();assertThat(storage.objects).isEmpty();assertThat(retention.get(alice,id).erasureState()).isEqualTo("COMPLETE");
        erasureLedger.fail=true;assertThatThrownBy(retention::replay).isInstanceOf(IllegalStateException.class);
        assertThat(retentionGuard.ready()).isFalse();
        mvc.perform(get("/api/v1/imports").with(as("alice"))).andExpect(status().isServiceUnavailable());
        erasureLedger.fail=false;var original=erasureLedger.records.get(id);
        erasureLedger.records.put(id,new com.skillhunters.retention.ErasureRecord(id,"IMPORT",poolA,original.replayHash(),original.requestedAt(),"0".repeat(64)));
        assertThatThrownBy(retention::replay).isInstanceOf(IllegalStateException.class);assertThat(retentionGuard.ready()).isFalse();
    }
    @Test void retentionRequiresCompliancePoolCsrfConfirmationAndCurrentVersion() throws Exception {
        var url="/api/v1/imports/"+id+"/retention";
        mvc.perform(get(url).with(as("alice"))).andExpect(status().isForbidden());
        mvc.perform(get(url).with(as("admin"))).andExpect(status().isForbidden());
        complianceRole();
        mvc.perform(post(url+"/erase").with(as("alice")).contentType("application/json").content("{\"confirmed\":true}")).andExpect(status().isForbidden());
        assertThatThrownBy(()->retention.request(alice,id,false)).isInstanceOf(Rejected.class);
        var hold=new com.skillhunters.retention.RetentionService.Hold(java.time.OffsetDateTime.now().plusDays(2),"Motif synthétique",true);
        assertThatThrownBy(()->retention.hold(alice,id,null,hold)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("VERSION_REQUIRED"));
        retention.hold(alice,id,"\"0\"",hold);
        assertThatThrownBy(()->retention.hold(alice,id,"\"0\"",hold)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("RETENTION_HOLD_CHANGED"));
        assertThatThrownBy(()->retention.hold(alice,id,"\"1\"",new com.skillhunters.retention.RetentionService.Hold(java.time.OffsetDateTime.now().plusDays(366),"Motif",true))).isInstanceOf(Rejected.class);
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        mvc.perform(get(url).with(as("alice"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/pools/"+poolA+"/erasures").with(as("alice"))).andExpect(status().isNotFound());
    }
    @Autowired com.skillhunters.rights.CandidateRights rights;
    com.skillhunters.rights.CandidateRights.Intake rightsIntake(String kind){var now=java.time.OffsetDateTime.now();return new com.skillhunters.rights.CandidateRights.Intake(kind,now.minusDays(1),now.plusDays(6),"POLICY-SYNTHETIC-v1",UUID.randomUUID().toString(),"Contestation synthétique d’une expérience");}
    com.skillhunters.rights.CandidateRights.Case verifiedRequest(UUID candidate,String kind){var r=rights.create(alice,candidate,rightsIntake(kind));return rights.verify(alice,candidate,r.id(),"\"1\"",new com.skillhunters.rights.CandidateRights.Verification("CALLBACK","CHECK-SYNTHETIC-1",true));}
    @Test void verifiedRectificationPublishesCorrectionAndIndexAtomicallyThenCloses() {
        var c=create();complianceRole();reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        var r=verifiedRequest(c.id(),"RECTIFICATION");var items=new ArrayList<>(accepted(c.id()));
        int experience=0;var claims=reviews.get(alice,c.id()).source().proposal().claims();for(int n=0;n<claims.size();n++)if(claims.get(n).field()==StructuredExtractor.Field.EXPERIENCE)experience=n;
        var old=items.get(experience);items.set(experience,new Item(experience,old.value(),old.decision(),old.precision(),new Period("2024-01","2024-06",DatePrecision.MONTH,Kind.ACADEMIC),"Rectification fournie après vérification"));
        var result=rights.correct(alice,c.id(),r.id(),"\"2\"",new com.skillhunters.rights.CandidateRights.Correction(2,request(items),true));
        assertThat(reviews.publication(alice,c.id()).revisionId()).isEqualTo(result.revision().id());
        assertThat(db.queryForObject("SELECT period_end FROM candidate_assertion_index WHERE candidate_id=? AND field='EXPERIENCE'",String.class,c.id())).isEqualTo("2024-06");
        var closed=rights.close(alice,c.id(),r.id(),"\"3\"",new com.skillhunters.rights.CandidateRights.Closure("FULFILLED","Correction publiée et réponse consignée","REPLY-SYNTHETIC",true));
        assertThat(closed.state()).isEqualTo("CLOSED");
        assertThat(rights.history(alice,c.id(),r.id(),null).items()).extracting(e->e.action()).containsExactly("CLOSED","RECTIFIED","VERIFIED","RECEIVED");
        assertThat(rights.queue(alice,poolA,null,false).items()).isEmpty();verify(smtp,never()).send(any());
        assertThatThrownBy(()->rights.correct(alice,c.id(),r.id(),"\"4\"",new com.skillhunters.rights.CandidateRights.Correction(3,request(items),true))).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("RIGHTS_REQUEST_CLOSED"));
    }
    @Test void rightsDisclosureAndClosureRequireVerifiedIdentityAndCurrentPackage() {
        var c=create();complianceRole();var r=rights.create(alice,c.id(),rightsIntake("ACCESS"));
        assertThatThrownBy(()->rights.prepare(alice,c.id(),r.id(),"\"1\"")).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("IDENTITY_NOT_VERIFIED"));
        assertThatThrownBy(()->rights.close(alice,c.id(),r.id(),"\"1\"",new com.skillhunters.rights.CandidateRights.Closure("NOT_GRANTED","Motif","REPLY",true))).isInstanceOf(Rejected.class);
        rights.verify(alice,c.id(),r.id(),"\"1\"",new com.skillhunters.rights.CandidateRights.Verification("EXISTING_CHANNEL","CHECK-1",true));
        assertThatThrownBy(()->rights.prepare(alice,c.id(),r.id(),"\"2\"")).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("CONTACT_PERMISSION_REQUIRED"));
        contactRole();var bundle=rights.prepare(alice,c.id(),r.id(),"\"2\"");assertThat(bundle.candidate().id()).isEqualTo(c.id());assertThat(bundle.source()).isNotNull();
        reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);
        assertThatThrownBy(()->rights.close(alice,c.id(),r.id(),"\"3\"",new com.skillhunters.rights.CandidateRights.Closure("FULFILLED","Réponse","REPLY",true))).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("ACCESS_PACKAGE_CHANGED"));
        rights.prepare(alice,c.id(),r.id(),"\"3\"");
        assertThat(rights.close(alice,c.id(),r.id(),"\"4\"",new com.skillhunters.rights.CandidateRights.Closure("FULFILLED","Dossier relu puis transmis par le canal vérifié","REPLY-2",true)).state()).isEqualTo("CLOSED");
    }
    @Test void changedContactInvalidatesVerificationAndRequiresNewHumanCheck() {
        var c=create();complianceRole();contactRole();var r=verifiedRequest(c.id(),"ACCESS");
        db.update("UPDATE candidate_contact SET email='changed@example.com' WHERE candidate_id=?",c.id());
        assertThatThrownBy(()->rights.prepare(alice,c.id(),r.id(),"\"2\"")).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("IDENTITY_VERIFICATION_CHANGED"));
        rights.verify(alice,c.id(),r.id(),"\"2\"",new com.skillhunters.rights.CandidateRights.Verification("CALLBACK","CHECK-NEW",true));
        assertThat(rights.prepare(alice,c.id(),r.id(),"\"3\"").candidate().contact().email()).isEqualTo("changed@example.com");
    }
    @Test void appealNeedsHumanReviewAndTracesReasonBeforeClosing() {
        var c=create();complianceRole();var r=verifiedRequest(c.id(),"APPEAL");
        var closure=new com.skillhunters.rights.CandidateRights.Closure("NOT_GRANTED","Décision humaine expliquée et réponse envoyée","REPLY-1",true);
        assertThatThrownBy(()->rights.close(alice,c.id(),r.id(),"\"2\"",closure)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("APPEAL_REVIEW_REQUIRED"));
        rights.appeal(alice,c.id(),r.id(),"\"2\"",new com.skillhunters.rights.CandidateRights.Decision("Réexamen contradictoire synthétique","ASSESSMENT-1",true));
        assertThat(rights.close(alice,c.id(),r.id(),"\"3\"",closure).outcome()).isEqualTo("NOT_GRANTED");
        assertThat(rights.history(alice,c.id(),r.id(),null).items()).anySatisfy(e->{assertThat(e.action()).isEqualTo("APPEAL_REVIEWED");assertThat(e.reason()).contains("contradictoire");});verify(smtp,never()).send(any());
    }
    @Test void rightsRespectScopesCsrfOptimisticVersionsAndDuplicateEmail() throws Exception {
        var c=create();var input=rightsIntake("ACCESS");String root="/api/v1/candidates/"+c.id()+"/rights-requests";
        assertThatThrownBy(()->rights.create(alice,c.id(),input)).isInstanceOf(Rejected.class);assertThatThrownBy(()->rights.create(admin,c.id(),input)).isInstanceOf(Rejected.class);
        complianceRole();var r=rights.create(alice,c.id(),input);
        assertThatThrownBy(()->rights.create(alice,c.id(),input)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("RIGHTS_EMAIL_ALREADY_REGISTERED"));
        mvc.perform(post(root).with(as("alice")).contentType("application/json").content(json.writeValueAsString(input))).andExpect(status().isForbidden());
        assertThatThrownBy(()->rights.verify(alice,c.id(),r.id(),null,new com.skillhunters.rights.CandidateRights.Verification("CALLBACK","REF",true))).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("VERSION_REQUIRED"));
        assertThatThrownBy(()->rights.verify(alice,c.id(),r.id(),"\"0\"",new com.skillhunters.rights.CandidateRights.Verification("CALLBACK","REF",true))).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("RIGHTS_REQUEST_CHANGED"));
        db.update("DELETE FROM membership WHERE user_id=?",alice);
        mvc.perform(get(root).with(as("alice"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/pools/"+poolA+"/rights-requests").with(as("alice"))).andExpect(status().isNotFound());
    }
    @Test void rightsDeadlinesPaginationAndSourceErasureKeepOnlyMinimalAudit() {
        var c=create();complianceRole();var now=java.time.OffsetDateTime.now();
        var r=rights.create(alice,c.id(),new com.skillhunters.rights.CandidateRights.Intake("ACCESS",now.minusDays(4),now.minusDays(1),"POLICY-1","EMAIL-OVERDUE","Private synthetic request summary"));
        assertThat(r.overdue()).isTrue();assertThat(rights.queue(alice,poolA,null,true).items()).hasSize(1);
        rights.deadline(alice,c.id(),r.id(),"\"1\"",new com.skillhunters.rights.CandidateRights.Deadline(now.plusDays(2),"POLICY-2","Prolongation motivée selon décision synthétique"));
        assertThat(rights.queue(alice,poolA,null,true).items()).isEmpty();
        for(int n=0;n<26;n++)rights.create(alice,c.id(),rightsIntake("ACCESS"));
        var page=rights.list(alice,c.id(),null);assertThat(page.items()).hasSize(25);assertThat(rights.list(alice,c.id(),page.nextCursor()).items()).hasSize(2);
        retention.request(alice,id,true);retention.runOnce();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_rights_request",Integer.class)).isZero();assertThat(db.queryForObject("SELECT COUNT(*) FROM candidate_rights_event",Integer.class)).isZero();
        assertThat(db.queryForList("SELECT action FROM governance_audit WHERE object_id=?",String.class,r.id())).contains("RIGHTS_RECEIVED","RIGHTS_DEADLINE_CHANGED");
    }
    @Test void rejectedCorrectionRollsBackCaseAndPublishedIndex() {
        var c=create();complianceRole();var published=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);var r=verifiedRequest(c.id(),"RECTIFICATION");
        assertThatThrownBy(()->rights.correct(alice,c.id(),r.id(),"\"2\"",new com.skillhunters.rights.CandidateRights.Correction(1,request(accepted(c.id())),true))).isInstanceOf(Rejected.class);
        var reasonsOnly=accepted(c.id()).stream().map(i->new Item(i.sourceIndex(),i.value(),i.decision(),i.precision(),i.period(),"Nouveau motif sans correction des données")).toList();
        assertThatThrownBy(()->rights.correct(alice,c.id(),r.id(),"\"2\"",new com.skillhunters.rights.CandidateRights.Correction(2,request(reasonsOnly),true))).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("RIGHTS_CORRECTION_REQUIRED"));
        assertThat(reviews.publication(alice,c.id()).revisionId()).isEqualTo(published.id());assertThat(rights.list(alice,c.id(),null).items().getFirst().version()).isEqualTo(2);assertThat(rights.history(alice,c.id(),r.id(),null).items()).hasSize(2);
    }

    @Autowired com.skillhunters.exports.CsvExports csv;
    UUID csvActor() {
        UUID actor=UUID.randomUUID();
        db.update("INSERT INTO app_user(id,issuer,subject,display_name) VALUES (?,?,?,?)",actor,issuer,actor.toString(),"Export synthétique");
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",actor,poolA);
        db.update("INSERT INTO export_permission(user_id,pool_id) VALUES (?,?)",actor,poolA);
        return actor;
    }
    com.skillhunters.exports.CsvExports.Request csvRequest(UUID candidate,String... fields) {
        return new com.skillhunters.exports.CsvExports.Request(poolA,List.of(candidate),List.of(fields),com.skillhunters.exports.CsvExports.Purpose.RECRUITMENT_REVIEW,null);
    }
    @Test void csvRequiresPermissionAndCsrfAndReturnsOnlySelectedFieldsOnce() throws Exception {
        var c=create();var actor=csvActor();var input=csvRequest(c.id(),"candidateId","professionalTitle");var body=json.writeValueAsString(input);
        mvc.perform(post("/api/v1/csv-exports").with(as(actor.toString())).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/csv-exports").with(as("alice")).with(csrf()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        var response=mvc.perform(post("/api/v1/csv-exports").with(as(actor.toString())).with(csrf()).contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn();
        var receipt=json.readValue(response.getResponse().getContentAsString(),com.skillhunters.exports.CsvExports.Receipt.class);
        assertThat(receipt.expiresAt()).isBefore(java.time.OffsetDateTime.now().plusMinutes(6));
        mvc.perform(get(receipt.downloadUrl()).with(as("admin"))).andExpect(status().isNotFound());
        var file=mvc.perform(get(receipt.downloadUrl()).with(as(actor.toString()))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store")).andExpect(header().string("X-Content-Type-Options","nosniff"))
            .andExpect(header().string("Content-Disposition",org.hamcrest.Matchers.startsWith("attachment;"))).andReturn();
        String content=new String(file.getResponse().getContentAsByteArray(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content).startsWith("\ufeff\"candidateId\",\"professionalTitle\"\r\n").contains(c.id().toString()).doesNotContain("email","phone","displayName");
        mvc.perform(get(receipt.downloadUrl()).with(as(actor.toString()))).andExpect(status().isGone());
        var audit=db.queryForMap("SELECT purpose,csv_fields,csv_rows FROM governance_audit WHERE actor_id=? AND action='CSV_EXPORT_DOWNLOADED'",actor);
        assertThat(audit.get("purpose")).isEqualTo("RECRUITMENT_REVIEW");assertThat(audit.get("csv_fields")).isEqualTo("candidateId,professionalTitle");assertThat(audit.get("csv_rows")).isEqualTo(1);
    }
    @Test void csvRejectsForbiddenContactsAndRechecksRoleAtDownload() {
        var c=create();var actor=csvActor();
        assertThat(csv.options(actor,poolA).fields()).doesNotContain("email","phone");
        assertThatThrownBy(()->csv.create(actor,csvRequest(c.id(),"email"))).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("CSV_FIELD_FORBIDDEN"));
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITMENT_LEAD')",actor,poolA);
        var receipt=csv.create(actor,csvRequest(c.id(),"email","phone"));
        db.update("DELETE FROM membership WHERE user_id=? AND role='RECRUITMENT_LEAD'",actor);
        assertThatThrownBy(()->csv.download(actor,receipt.id())).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("CSV_FIELD_FORBIDDEN"));
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITMENT_LEAD')",actor,poolA);
        assertThat(new String(csv.download(actor,receipt.id()).content(),java.nio.charset.StandardCharsets.UTF_8)).startsWith("\ufeff\"email\",\"phone\"");
    }
    @Test void csvRevocationDisabledAccountsAndCrossPoolSelectionFailClosed() {
        var c=create();var actor=csvActor();var receipt=csv.create(actor,csvRequest(c.id(),"displayName"));
        db.update("DELETE FROM export_permission WHERE user_id=?",actor);
        assertThatThrownBy(()->csv.download(actor,receipt.id())).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("EXPORT_PERMISSION_REQUIRED"));
        db.update("INSERT INTO export_permission(user_id,pool_id) VALUES (?,?)",actor,poolA);
        db.update("UPDATE app_user SET enabled=false WHERE id=?",actor);
        assertThatThrownBy(()->csv.download(actor,receipt.id())).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("ACCOUNT_DISABLED"));
        db.update("UPDATE app_user SET enabled=true WHERE id=?",actor);
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')",actor,poolB);
        db.update("INSERT INTO export_permission(user_id,pool_id) VALUES (?,?)",actor,poolB);
        var invalid=new com.skillhunters.exports.CsvExports.Request(poolB,List.of(c.id()),List.of("displayName"),com.skillhunters.exports.CsvExports.Purpose.RECRUITMENT_REVIEW,null);
        assertThatThrownBy(()->csv.create(actor,invalid)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("CANDIDATE_NOT_FOUND"));
    }
    @Test void csvRejectsChangedExpiredAndErasedSelections() throws Exception {
        var c=create();var actor=csvActor();var receipt=csv.create(actor,csvRequest(c.id(),"displayName"));
        candidates.update(alice,c.id(),"\"1\"",json.readTree("{\"professionalTitle\":\"Nouveau titre\",\"reason\":\"Correction synthétique\"}"));
        assertThatThrownBy(()->csv.download(actor,receipt.id())).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("CSV_SELECTION_CHANGED"));
        var expired=csv.create(actor,csvRequest(c.id(),"displayName"));
        db.update("UPDATE csv_export_ticket SET expires_at=? WHERE id=?",java.time.OffsetDateTime.now().minusSeconds(1),expired.id());
        assertThatThrownBy(()->csv.download(actor,expired.id())).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("CSV_EXPORT_EXPIRED"));
        var removed=csv.create(actor,csvRequest(c.id(),"displayName"));
        db.update("DELETE FROM document_audit WHERE import_id=?",id);db.update("DELETE FROM document_import WHERE id=?",id);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM csv_export_member",Integer.class)).isZero();
        assertThatThrownBy(()->csv.download(actor,removed.id())).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("CSV_SELECTION_REMOVED"));
    }
    @Test void csvQuotasSurviveConsumptionAndCleanupAndRejectOversizedSelections() {
        var c=create();var actor=csvActor();
        for(int n=0;n<20;n++){var ticket=csv.create(actor,csvRequest(c.id(),"candidateId"));csv.download(actor,ticket.id());}
        csv.cleanup();assertThat(db.queryForObject("SELECT COUNT(*) FROM csv_export_ticket WHERE actor_id=?",Integer.class,actor)).isZero();
        assertThatThrownBy(()->csv.create(actor,csvRequest(c.id(),"candidateId"))).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("CSV_EXPORT_QUOTA"));
        var other=csvActor();
        db.update("INSERT INTO governance_audit(id,actor_id,action,purpose,csv_fields,csv_rows) VALUES (?,?,'CSV_EXPORT_CREATED','RECRUITMENT_REVIEW','candidateId',500)",UUID.randomUUID(),other);
        assertThatThrownBy(()->csv.create(other,csvRequest(c.id(),"candidateId"))).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("CSV_EXPORT_QUOTA"));
        for(var ids:List.of(List.of(c.id(),c.id()),java.util.stream.IntStream.range(0,51).mapToObj(n->UUID.randomUUID()).toList())) {
            var request=new com.skillhunters.exports.CsvExports.Request(poolA,ids,List.of("candidateId"),com.skillhunters.exports.CsvExports.Purpose.RECRUITMENT_REVIEW,null);
            assertThatThrownBy(()->csv.create(other,request)).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("INVALID_CSV_EXPORT"));
        }
    }
    @Test void csvScoreIncludesFrozenCriteriaDatesAndPolicyAndRequiresSnapshotOwner() {
        var c=create();var published=reviews.save(alice,c.id(),"\"1\"",request(accepted(c.id())),true);var actor=csvActor();
        var snapshot=scored.create(actor,scoredRequest(false,"Java"));
        var input=new com.skillhunters.exports.CsvExports.Request(poolA,List.of(c.id()),List.of("score"),com.skillhunters.exports.CsvExports.Purpose.INTERNAL_COMPARISON,snapshot.metadata().searchId());
        var other=csvActor();assertThatThrownBy(()->csv.create(other,input)).isInstanceOf(Rejected.class);
        var ticket=csv.create(actor,input);String content=new String(csv.download(actor,ticket.id()).content(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(content).contains("date_calcul","date_reference","criteres_et_contexte","regle_score","poids",snapshot.metadata().createdAt().toString(),snapshot.metadata().trace().referenceDate().toString(),"Java",published.id().toString(),"100.0");
        assertThatThrownBy(()->csv.create(actor,csvRequest(c.id(),"score"))).isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("INVALID_CSV_EXPORT"));
    }
    @Test void csvConcurrentDownloadsHaveExactlyOneWinner() throws Exception {
        var c=create();var actor=csvActor();var ticket=csv.create(actor,csvRequest(c.id(),"candidateId"));
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var start=new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> attempt=()->{start.await();try{csv.download(actor,ticket.id());return true;}catch(Rejected e){assertThat(e.code).isEqualTo("CSV_EXPORT_EXPIRED");return false;}};
            var a=executor.submit(attempt);var b=executor.submit(attempt);start.countDown();
            assertThat(List.of(a.get(10,java.util.concurrent.TimeUnit.SECONDS),b.get(10,java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(true,false);
        }
    }
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="SH35_FORMATS",matches=".+")
    void syntheticCounterfactualsAndFormatsKeepRetrievalAndScoresIdentical() throws Exception {ModelEvaluationBenchmark.run(this);}
}
