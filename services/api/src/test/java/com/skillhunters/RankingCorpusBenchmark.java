package com.skillhunters;

import com.skillhunters.documents.*;
import com.skillhunters.scoring.*;
import com.skillhunters.search.*;
import com.skillhunters.talent.ReviewService;
import com.skillhunters.taxonomy.TaxonomyService;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.mock.web.MockMultipartFile;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Normalized synthetic input; production extraction, publication, retrieval and ranking. */
final class RankingCorpusBenchmark {
    static void run(HumanReviewTest h) throws Exception {
        Path root=Path.of("../../tests/fixtures/ranking");
        var corpus=h.json.readTree(root.resolve("corpus.json").toFile());
        var judgments=h.json.readTree(root.resolve("judgments.json").toFile());
        assertThat(corpus.path("profiles").size()).isEqualTo(100);
        assertThat(corpus.path("queries").size()).isEqualTo(30);
        var judgedIds=new HashSet<String>();judgments.path("documents").forEach(d->judgedIds.add(d.path("id").asText()));
        long version=0;
        for(var skill:corpus.path("skills"))h.taxonomy.edit(h.admin,null,"\""+version+++"\"",new TaxonomyService.Edit(skill.asText(),"Synthetic",List.of(),"Ranking corpus"));
        UUID policy=h.db.queryForObject("SELECT id FROM import_policy",UUID.class);
        h.analysis.runOnce(); // Drain common seed; it is not published into the measured pool.
        var ids=new LinkedHashMap<UUID,String>();
        for(var profile:corpus.path("profiles")) {
            String key=profile.path("id").asText(),text=profile.path("text").asText();
            h.mvc.perform(multipart("/api/v1/imports").file(new MockMultipartFile("files",key+".md","text/markdown",text.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("basis","","application/json",("{\"source\":\"Synthetic ranking\",\"purpose\":\"Test\",\"basisCode\":\"TEST_ONLY\",\"retentionPolicyId\":\""+policy+"\"}").getBytes(StandardCharsets.UTF_8)))
                .param("poolId",h.poolA.toString()).header("Idempotency-Key","ranking-"+key).with(h.as("alice")).with(csrf())).andExpect(status().isAccepted());
            h.id=h.db.queryForObject("SELECT id FROM document_import WHERE idempotency_key=?",UUID.class,"ranking-"+key);
            doReturn(new DocumentAnalyzer.Result("EXTRACTED",null,List.of(new DocumentAnalyzer.Block("document",text)),List.of(),"normalized-fixture","ClamAV synthetic fixture transport"))
                .when(h.analyzer).analyze(any(),any(),any());
            var candidate=h.create();h.reviews.save(h.alice,candidate.id(),"\"1\"",h.request(h.accepted(candidate.id())),true);
            assertThat(ids.put(candidate.id(),key)).isNull();
        }
        assertThat(new HashSet<>(ids.values())).isEqualTo(judgedIds);
        var rankings=new LinkedHashMap<String,List<String>>();var traces=new ArrayList<Object>();
        for(var query:corpus.path("queries")) {
            var criteria=new ArrayList<StructuredSearchService.Criterion>();
            criteria.add(new StructuredSearchService.Criterion(query.path("required").asText(),StructuredSearchService.Importance.REQUIRED,null,null));
            if(!query.path("desired").isNull())criteria.add(new StructuredSearchService.Criterion(query.path("desired").asText(),StructuredSearchService.Importance.DESIRED,null,null));
            var q=new StructuredSearchService.Query(h.poolA,criteria,query.path("strict").asBoolean(),List.of(ReviewService.Kind.EMPLOYMENT),LocalDate.of(2026,11,1),null,null,7);
            var page=h.scored.create(h.alice,new ScoredSearchService.Request(q,false,0.65,0L));
            var metadata=page.metadata();var ordered=new ArrayList<String>();var seen=new HashSet<UUID>();int pages=0;
            while(true) {
                for(var item:page.items()) {
                    assertThat(seen.add(item.hit().candidateId())).isTrue();
                    assertThat(ids).containsKey(item.hit().candidateId());
                    assertThat(item.rank()).isEqualTo(ordered.size()+1);
                    ordered.add(ids.get(item.hit().candidateId()));
                }
                assertThat(++pages).isLessThan(20);
                if(page.nextCursor()==null)break;
                page=h.scored.page(h.alice,metadata.searchId(),page.nextCursor(),7);
            }
            assertThat(rankings.put(query.path("id").asText(),ordered)).isNull();
            traces.add(Map.of("queryId",query.path("id").asText(),"trace",metadata.trace(),"policy",metadata.policy(),"scanned",metadata.scanned(),"pages",pages));
            // Isolated test fixture advances this snapshot's expiry; keep production quota unchanged.
            h.db.update("UPDATE scored_search SET expires_at=CURRENT_TIMESTAMP-INTERVAL '1' DAY WHERE id=?",metadata.searchId());h.scored.cleanup();
        }
        verifyNoInteractions(h.embeddings); // This first qualification is explicitly structured ranking.
        var config=new LinkedHashMap<String,Object>();
        config.put("schema","ranking-configuration-v1");config.put("corpusSha256",hash(Files.readAllBytes(root.resolve("corpus.json"))));
        config.put("mode","STRUCTURED");config.put("scoringRule",EvidenceScore.RULE);config.put("extractorRules",StructuredExtractor.RULES);
        config.put("referenceDate","2026-11-01");config.put("traces",traces);
        config.put("database",h.db.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName()));
        config.put("java",System.getProperty("java.version"));
        config.put("limitations",List.of("Normalized Markdown; parser transport mocked", "Synthetic authored annotations, not independent business judgment", "No hybrid embeddings, multi-format quality or capacity qualification"));
        Path target=Path.of("target");Files.createDirectories(target);
        var writer=h.json.writerWithDefaultPrettyPrinter();Path configuration=target.resolve("ranking-configuration.json");writer.writeValue(configuration.toFile(),config);
        String commit=Objects.toString(System.getenv("GITHUB_SHA"),System.getenv("RANKING_COMMIT"));
        assertThat(commit).as("GITHUB_SHA or RANKING_COMMIT must identify measured revision").matches("[0-9a-f]{40}");
        writer.writeValue(target.resolve("ranking-results.json").toFile(),Map.of("schemaVersion",1,"commit",commit,
            "configurationSha256",hash(Files.readAllBytes(configuration)),"judgmentsSha256",hash(Files.readAllBytes(root.resolve("judgments.json"))),"rankings",rankings));
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
}
