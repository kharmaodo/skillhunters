package com.skillhunters;

import com.fasterxml.jackson.core.type.TypeReference;
import com.skillhunters.documents.*;
import com.skillhunters.scoring.*;
import com.skillhunters.search.*;
import com.skillhunters.talent.ReviewService;
import com.skillhunters.taxonomy.TaxonomyService;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Offline qualification: real parser outputs, real pinned vectors and production services. */
final class ModelEvaluationBenchmark {
    static void run(HumanReviewTest h) throws Exception {
        try { evaluate(h); }
        catch(Exception | AssertionError failure) {
            Files.createDirectories(Path.of("target"));
            h.json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/sh-35-evaluation.json").toFile(),
                Map.of("schema","sh35-evaluation-v1","technicalDecision","FAIL","productionDecision","NOT_APPROVED_BY_THIS_REPORT",
                    "failureType",failure.getClass().getSimpleName(),"deviation",failure instanceof com.skillhunters.shared.ApiProblem.Rejected rejected?rejected.code:Objects.toString(failure.getMessage(),"Unspecified failure")));
            throw failure;
        }
    }
    private static void evaluate(HumanReviewTest h) throws Exception {
        var corpusPath=Path.of("../../tests/fixtures/evaluation/corpus.json");
        var corpus=h.json.readTree(corpusPath.toFile());
        var formats=h.json.readTree(Path.of(System.getenv("SH35_FORMATS")).toFile());
        assertThat(formats.path("corpusSha256").asText()).isEqualTo(hash(Files.readAllBytes(corpusPath)));
        assertThat(formats.path("synthetic").asBoolean()).isTrue();
        var encoded=h.json.readTree(Path.of(System.getenv("SH16_BENCHMARK_VECTORS")).toFile());
        assertThat(encoded.path("model").asText()).isEqualTo(EmbeddingPort.MODEL);
        assertThat(encoded.path("corpusSha256").asText()).isEqualTo(hash(Files.readAllBytes(Path.of("../../tests/fixtures/search/hybrid-corpus.json"))));
        Map<String,List<Double>> vectors=h.json.convertValue(encoded.path("vectors"),new TypeReference<>(){});
        var encodedLabels=new TreeSet<String>();
        doAnswer(call->{List<String> labels=call.getArgument(0);assertThat(labels).allMatch(Set.of("Java","Python")::contains);encodedLabels.addAll(labels);return labels.stream().map(vectors::get).toList();}).when(h.embeddings).encode(any());
        h.taxonomy.edit(h.admin,null,"\"0\"",new TaxonomyService.Edit("Java","Synthetic",List.of(),"SH35"));
        h.taxonomy.edit(h.admin,null,"\"1\"",new TaxonomyService.Edit("Python","Synthetic",List.of(),"SH35"));
        UUID policy=h.db.queryForObject("SELECT id FROM import_policy",UUID.class);
        var ids=new LinkedHashMap<String,UUID>();var extractionRows=new ArrayList<Map<String,Object>>();
        h.analysis.runOnce(); // Drain the common test seed before processing the measured corpus.
        for(var row:formats.path("cases")) {
            String key=row.path("id").asText();assertThat(ids).doesNotContainKey(key);
            List<DocumentAnalyzer.Block> blocks=h.json.convertValue(row.path("blocks"),new TypeReference<>(){});
            // Transport is a fixture adapter; blocks are actual parser outputs, never rewritten.
            String body="Synthetic SH35 fixture "+key;
            h.mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("files","synthetic.md","text/markdown",body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("basis","","application/json",("{\"source\":\"SH35 synthetic\",\"purpose\":\"Test\",\"basisCode\":\"TEST_ONLY\",\"retentionPolicyId\":\""+policy+"\"}").getBytes()))
                .param("poolId",h.poolA.toString()).header("Idempotency-Key","sh35-"+key.replace('/','-')).with(h.as("alice")).with(csrf())).andExpect(status().isAccepted());
            h.id=h.db.queryForObject("SELECT id FROM document_import WHERE idempotency_key=?",UUID.class,"sh35-"+key.replace('/','-'));
            doReturn(new DocumentAnalyzer.Result("EXTRACTED",null,blocks,List.of(),"sh35-measured-parser","ClamAV synthetic transport",h.json.convertValue(row.path("ocr"),new TypeReference<List<DocumentAnalyzer.OcrPage>>() {}))).when(h.analyzer).analyze(any(),any(),any());
            var candidate=h.create();var proposal=h.reviews.get(h.alice,candidate.id()).source().proposal();
            var skills=proposal.claims().stream().filter(c->c.field()==StructuredExtractor.Field.SKILL&&c.status()!=StructuredExtractor.State.UNKNOWN).map(StructuredExtractor.Claim::normalized).toList();
            var levels=proposal.claims().stream().filter(c->c.field()==StructuredExtractor.Field.DECLARED_LEVEL&&c.status()!=StructuredExtractor.State.UNKNOWN).map(StructuredExtractor.Claim::normalized).toList();
            assertThat(skills).as(key+" skill").containsExactly(corpus.path("expectedSkill").asText());
            assertThat(levels).as(key+" level").containsExactly(corpus.path("expectedDeclaredLevel").asText());
            var items=new ArrayList<ReviewService.Item>();
            for(int i=0;i<proposal.claims().size();i++) {
                var c=proposal.claims().get(i);boolean unknown=c.status()==StructuredExtractor.State.UNKNOWN;
                items.add(new ReviewService.Item(i,unknown?null:c.normalized(),unknown?ReviewService.Decision.UNKNOWN:ReviewService.Decision.VALIDATED,
                    unknown?ReviewService.Precision.UNKNOWN:c.field()==StructuredExtractor.Field.DECLARED_LEVEL?ReviewService.Precision.DECLARED_LEVEL:ReviewService.Precision.TEXT,null,unknown?null:"Synthetic annotation; absent dates remain unknown"));
            }
            h.reviews.save(h.alice,candidate.id(),"\"1\"",h.request(items),true);ids.put(key,candidate.id());
            extractionRows.add(Map.of("case",key,"format",row.path("format").asText(),"skillExact",true,"declaredLevelExact",true,"parserMillis",row.path("elapsedMillis").asDouble(),"documentSha256",row.path("documentSha256").asText()));
        }
        var expectedCases=new TreeSet<String>();for(var f:corpus.path("formats"))for(var v:corpus.path("variants"))expectedCases.add(f.asText()+"/"+v.path("id").asText());
        assertThat(ids.keySet()).containsExactlyInAnyOrderElementsOf(expectedCases);assertThat(ids).hasSize(16);
        h.db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITMENT_LEAD')",h.alice,h.poolA);h.scoringConfigs.save(h.alice,h.poolA,"\"0\"",new ScoringConfigurations.Edit("SH35 alternative",new ScoringConfigurations.Weights(2,1,3,4),"Synthetic comparison"));
        var runs=new ArrayList<Map<String,Object>>();
        for(long config:List.of(0L,1L))for(boolean hybrid:List.of(false,true))for(String scenario:List.of("positive","negative","unknown-duration")) {
            boolean positive=!scenario.equals("negative");
            var query=new StructuredSearchService.Query(h.poolA,List.of(new StructuredSearchService.Criterion(positive?"Java":"Python",StructuredSearchService.Importance.REQUIRED,scenario.equals("unknown-duration")?12:null,null)),
                false,List.of(ReviewService.Kind.EMPLOYMENT),LocalDate.of(2026,11,1),null,null,25);
            var result=h.scored.create(h.alice,new ScoredSearchService.Request(query,hybrid,0.65,config));
            Map<UUID,EvidenceScore.Score> scores=new HashMap<>();result.items().forEach(i->scores.put(i.hit().candidateId(),i.score()));
            assertThat(result.nextCursor()).isNull();
            // Compare presence AND all numeric/unknown score components, not UUID tie order.
            var baseline=scores.get(ids.get("markdown/baseline"));
            for(var entry:ids.entrySet())assertThat(scores.get(entry.getValue())).as(scenario+"/"+entry.getKey()).isEqualTo(baseline);
            if(positive)assertThat(scores.keySet()).containsAll(ids.values());
            if(scenario.equals("positive"))assertThat(baseline.documented()).isEqualTo(100);
            if(scenario.equals("unknown-duration"))assertThat(baseline.obligationsToVerify()).isTrue();
            runs.add(Map.of("scenario",scenario,"hybrid",hybrid,"configuration",config,"returned",scores.size(),"pairedRetrievalDifferences",0,"maxScoreDelta",0,"weights",result.metadata().policy()));
        }
        assertThat(encodedLabels).isNotEmpty();
        var report=new LinkedHashMap<String,Object>();report.put("schema","sh35-evaluation-v1");report.put("synthetic",true);
        report.put("corpusSha256",formats.path("corpusSha256").asText());report.put("model",EmbeddingPort.MODEL);report.put("modelManifestSha256",hash(Files.readAllBytes(Path.of("../../services/embedding-worker/manifest.json"))));
        report.put("commit",Objects.toString(System.getenv("GITHUB_SHA"),"LOCAL_UNPUBLISHED"));report.put("scoringRule",EvidenceScore.RULE);report.put("extractorRules",StructuredExtractor.RULES);report.put("prompt","NONE_DETERMINISTIC_RULES");report.put("projection",ProfessionalTextProjection.VERSION);
        report.put("database",h.db.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName()));
        report.put("parserRuntime",formats.path("runtime"));report.put("embeddingRuntime",encoded.path("runtime"));report.put("java",System.getProperty("java.version"));
        report.put("cases",extractionRows);report.put("referenceSearches",runs);report.put("encodedLabels",encodedLabels);
        report.put("technicalDecision","PASS_BOUNDED_SYNTHETIC_SUITE");report.put("productionDecision","NOT_APPROVED_BY_THIS_REPORT");
        report.put("limits",List.of("16 synthetic simple-layout documents; no population-level fairness claim", "Legacy DOC and complex/noisy layouts not paired here", "Parser and application are joined through a fixture transport; AV/OIDC covered separately", "UUID tie order is not an identity feature; equal score does not imply equal rank", "Independent authorized corpus, operational jurisdiction and 10000-profile capacity remain unqualified"));
        Files.createDirectories(Path.of("target"));h.json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/sh-35-evaluation.json").toFile(),report);
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
