package com.skillhunters;

import com.skillhunters.search.*;
import com.skillhunters.taxonomy.TaxonomyService;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersistentSkillVectorIndexTest extends IdentityTestSupport {
    @Autowired PersistentSkillVectorIndex index;
    @Autowired TaxonomyService taxonomy;
    @Autowired TransactionTemplate tx;
    @MockitoBean EmbeddingPort embeddings;
    TaxonomyService.Dictionary dictionary;
    @BeforeEach void catalog() throws Exception {
        taxonomy.edit(admin,null,"\"0\"",new TaxonomyService.Edit("Java","Language",List.of("JDK"),"Synthetic"));
        dictionary=taxonomy.edit(admin,null,"\"1\"",new TaxonomyService.Edit("Kafka","Messaging",List.of(),"Synthetic"));
        when(embeddings.encode(any())).thenAnswer(call->{
            List<String> labels=call.getArgument(0);
            return labels.stream().map(PersistentSkillVectorIndexTest::vector).toList();
        });
    }
    static List<Double> vector(String label) {
        var values=new ArrayList<>(Collections.nCopies(384,0.0));
        values.set(0,label.equals("Kafka")?0.8:1.0);values.set(1,label.equals("Kafka")?0.6:0.0);return values;
    }
    PersistentSkillVectorIndex.Result retrieve(TaxonomyService.Dictionary d,Set<String> labels) {
        return tx.execute(t->index.similarities(d,List.of("Java"),labels));
    }
    @Test void indexIsReusedAndPostgresExecutesNativeCosine() throws Exception {
        var first=retrieve(dictionary,Set.of("Java","Kafka"));
        assertThat(first.indexedLabels()).isEqualTo(2);assertThat(first.reusedLabels()).isZero();
        assertThat(first.similarities().get(new PersistentSkillVectorIndex.Pair("Java","Kafka"))).isCloseTo(0.8,within(0.00001));
        String product=db.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c->c.getMetaData().getDatabaseProductName());
        assertThat(first.storage()).isEqualTo(product.equals("PostgreSQL")?"pgvector-exact":"h2-test-adapter");
        if(product.equals("PostgreSQL")) {
            assertThat(db.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'",String.class)).isEqualTo("0.8.7");
            assertThat(db.queryForObject("SELECT vector_dims(embedding) FROM skill_vector_entry LIMIT 1",Integer.class)).isEqualTo(384);
        }
        reset(embeddings);when(embeddings.encode(any())).thenThrow(new IllegalStateException("offline"));
        var cached=retrieve(dictionary,Set.of("Java","Kafka"));
        assertThat(cached.indexedLabels()).isZero();assertThat(cached.reusedLabels()).isEqualTo(2);
        assertThat(cached.similarities()).isEqualTo(first.similarities());verifyNoInteractions(embeddings);
    }
    @Test void dictionaryVersionsAndModelGenerationsNeverMixAndCascadeOnRemoval() throws Exception {
        retrieve(dictionary,Set.of("Java","Kafka"));
        var next=taxonomy.edit(admin,null,"\"2\"",new TaxonomyService.Edit("Spring Boot","Framework",List.of(),"Synthetic"));
        var result=retrieve(next,Set.of("Java","Spring Boot"));assertThat(result.indexedLabels()).isEqualTo(2);
        assertThat(retrieve(dictionary,Set.of("Java","Kafka")).indexedLabels()).isZero();
        db.update("INSERT INTO skill_vector_generation(dictionary_version,model,dimensions) VALUES (?, 'previous-model',384)",next.version());
        assertThat(retrieve(next,Set.of("Java","Spring Boot")).reusedLabels()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM skill_vector_entry",Integer.class)).isEqualTo(4);
        db.update("UPDATE skill_dictionary_head SET version=0 WHERE id=1");db.update("DELETE FROM skill_dictionary WHERE version=?",dictionary.version());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM skill_vector_entry",Integer.class)).isEqualTo(2);
        assertThatThrownBy(()->retrieve(next,Set.of("Java","person@example.com"))).isInstanceOf(Rejected.class);
    }
    @Test void failedBatchRollsBackWithoutPublishingPartialGeneration() throws Exception {
        doReturn(List.of(vector("Java"),List.of(Double.NaN))).when(embeddings).encode(any());
        assertThatThrownBy(()->retrieve(dictionary,Set.of("Java","Kafka"))).isInstanceOf(Rejected.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM skill_vector_entry",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM skill_vector_generation",Integer.class)).isZero();
        doReturn(List.of(vector("Java"),vector("Kafka"))).when(embeddings).encode(any());
        assertThat(retrieve(dictionary,Set.of("Java","Kafka")).indexedLabels()).isEqualTo(2);
    }
    @Test void concurrentMissesPublishOnlyOneVectorPerVersion() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        doAnswer(call->{entered.countDown();if(!release.await(5,TimeUnit.SECONDS)) throw new IllegalStateException("timeout");return List.of(vector("Java"));}).when(embeddings).encode(any());
        try(var executor=Executors.newFixedThreadPool(2)) {
            var first=executor.submit(()->retrieve(dictionary,Set.of("Java")));
            assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
            var second=executor.submit(()->retrieve(dictionary,Set.of("Java")));
            release.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS).indexedLabels()+second.get(10,TimeUnit.SECONDS).indexedLabels()).isEqualTo(1);
            verify(embeddings,times(1)).encode(List.of("Java"));
        } finally {release.countDown();}
        assertThat(db.queryForObject("SELECT COUNT(*) FROM skill_vector_entry",Integer.class)).isEqualTo(1);
    }
}
