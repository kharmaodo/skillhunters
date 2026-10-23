package com.skillhunters;

import com.skillhunters.documents.*;
import com.skillhunters.search.*;
import com.skillhunters.talent.ReviewService.Kind;
import com.skillhunters.taxonomy.TaxonomyService;
import com.fasterxml.jackson.core.type.TypeReference;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.mock.web.MockMultipartFile;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real model vectors + real application services, migrations, scope and pagination. */
final class HybridCorpusBenchmark {
    static void run(HumanReviewTest h) throws Exception {
        Path corpusPath=Path.of("../../tests/fixtures/search/hybrid-corpus.json");
        var corpus=h.json.readTree(corpusPath.toFile());
        var encoded=h.json.readTree(Path.of(System.getenv("SH16_BENCHMARK_VECTORS")).toFile());
        assertThat(encoded.path("model").asText()).isEqualTo(EmbeddingPort.MODEL);
        assertThat(encoded.path("corpusSha256").asText()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(corpusPath))));
        Map<String,List<Double>> vectors=h.json.convertValue(encoded.path("vectors"),new TypeReference<>(){});
        doAnswer(call->{List<String> labels=call.getArgument(0);return labels.stream().map(vectors::get).toList();}).when(h.embeddings).encode(any());
        long version=0;
        for(var skill:corpus.path("skills")) {
            List<String> aliases=h.json.convertValue(skill.path("aliases"),new TypeReference<>(){});
            h.taxonomy.edit(h.admin,null,"\""+version+++"\"",new TaxonomyService.Edit(skill.path("name").asText(),"Synthetic benchmark",aliases,"Pre-annotated corpus"));
        }
        UUID policy=h.db.queryForObject("SELECT id FROM import_policy",UUID.class);
        var names=new HashMap<UUID,String>();int count=0;
        for(var profile:corpus.path("profiles")) {
            String name=profile.path("id").asText(),text=profile.path("text").asText();
            if(count++>0) {
                h.mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("files",name+".md","text/markdown",text.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .file(new MockMultipartFile("basis","","application/json",("{\"source\":\"Synthetic benchmark\",\"purpose\":\"Test\",\"basisCode\":\"TEST_ONLY\",\"retentionPolicyId\":\""+policy+"\"}").getBytes()))
                    .param("poolId",h.poolA.toString()).header("Idempotency-Key","benchmark-"+name).with(h.as("alice")).with(csrf())).andExpect(status().isAccepted());
                h.id=h.db.queryForObject("SELECT id FROM document_import WHERE idempotency_key=?",UUID.class,"benchmark-"+name);
            }
            doReturn(new DocumentAnalyzer.Result("EXTRACTED",null,List.of(new DocumentAnalyzer.Block("document",text)),List.of(),"sh08-test","ClamAV synthetic"))
                .when(h.analyzer).analyze(any(),any(),any());
            var candidate=h.create();h.reviews.save(h.alice,candidate.id(),"\"1\"",h.request(h.accepted(candidate.id())),true);
            names.put(candidate.id(),name);
        }
        var rows=new ArrayList<Map<String,Object>>();double sum=0,precisionSum=0;long start=System.nanoTime();
        for(var query:corpus.path("queries")) {
            Set<String> expected=h.json.convertValue(query.path("relevant"),new TypeReference<>(){});
            var found=new LinkedHashSet<String>();String cursor=null;int pages=0,scanned=0;long begin=System.nanoTime();
            do {
                var q=new StructuredSearchService.Query(h.poolA,List.of(new StructuredSearchService.Criterion(query.path("text").asText(),StructuredSearchService.Importance.REQUIRED,null,null)),
                    false,List.of(Kind.EMPLOYMENT,Kind.INTERNSHIP,Kind.FREELANCE),null,null,cursor,7);
                var result=h.hybrid.search(h.alice,new HybridSearchService.Request(q,corpus.path("threshold").asDouble()));
                result.items().forEach(hit->found.add(names.get(hit.candidateId())));
                cursor=result.nextCursor();scanned+=result.scanned();assertThat(++pages).isLessThan(20);
            } while(cursor!=null);
            assertThat(scanned).isEqualTo(names.size());
            long relevant=found.stream().filter(expected::contains).count();
            double recall=(double)relevant/expected.size(),precision=found.isEmpty()?0:(double)relevant/found.size();sum+=recall;precisionSum+=precision;
            var missing=new TreeSet<>(expected);missing.removeAll(found);
            rows.add(Map.of("query",query.path("id").asText(),"recall",recall,"precision",precision,"returned",found.size(),"missing",missing,"pages",pages,"elapsedMillis",(System.nanoTime()-begin)/1_000_000));
            assertThat(found).doesNotContain("excluded-name","excluded-hobby");
        }
        var report=new LinkedHashMap<String,Object>();
        report.put("corpus",corpus.path("version").asText());report.put("corpusSha256",encoded.path("corpusSha256").asText());
        report.put("synthetic",true);report.put("profiles",names.size());report.put("queries",rows.size());report.put("model",EmbeddingPort.MODEL);
        report.put("strategy",HybridSearchService.STRATEGY);report.put("projection",ProfessionalTextProjection.VERSION);
        report.put("database",h.db.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName()));
        report.put("threshold",corpus.path("threshold").asDouble());report.put("meanExhaustiveRecall",sum/rows.size());report.put("meanPrecision",precisionSum/rows.size());
        report.put("searchMillis",(System.nanoTime()-start)/1_000_000);report.put("embeddingRuntime",encoded.path("runtime"));report.put("results",rows);
        report.put("limits","Synthetic normalized Markdown only; parser mocked. Real ONNX vectors, production retrieval and pagination. No ranking: Recall@20/nDCG@10 and 10000-profile latency are not claimed; SH-18/19 and broader format qualification remain separate gates.");
        Files.createDirectories(Path.of("target"));h.json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/sh-16-corpus-report.json").toFile(),report);
        assertThat(rows).hasSizeGreaterThanOrEqualTo(30);assertThat(sum/rows.size()).isGreaterThanOrEqualTo(0.9);
    }
}
