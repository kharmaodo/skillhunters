package com.skillhunters.search;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.shared.ApiProblem.Rejected;
import com.skillhunters.taxonomy.TaxonomyService;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Versioned professional catalog index. Contains neither candidate IDs nor candidate text. */
@Service
public class PersistentSkillVectorIndex {
    public record Pair(String query,String label) {}
    public record Result(Map<Pair,Double> similarities,String storage,int reusedLabels,int indexedLabels,Set<Pair> lexicalMatches) {}
    private final JdbcClient db;private final ObjectMapper json;private final EmbeddingPort embeddings;private final boolean postgres;
    public PersistentSkillVectorIndex(JdbcClient db,ObjectMapper json,EmbeddingPort embeddings,DataSource source) throws java.sql.SQLException {
        this.db=db;this.json=json;this.embeddings=embeddings;
        try(var connection=source.getConnection()) {
            String product=connection.getMetaData().getDatabaseProductName();
            if(!product.equals("PostgreSQL")&&!product.equals("H2")) throw new IllegalStateException("UNSUPPORTED_VECTOR_DATABASE");
            postgres=product.equals("PostgreSQL");
        }
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Result similarities(TaxonomyService.Dictionary dictionary,List<String> queries,Set<String> labels) {
        if(queries.isEmpty()||queries.size()>10||labels.isEmpty()||labels.size()>128||!labels.containsAll(queries)) throw new IllegalArgumentException("VECTOR_INPUT_LIMIT");
        var skills=new HashMap<String,UUID>();dictionary.skills().forEach(skill->skills.put(skill.name(),skill.id()));
        for(String label:labels) if(!skills.containsKey(label)||FrenchSearchInterpreter.forbidden(label)) throw new Rejected(HttpStatus.BAD_REQUEST,"HYBRID_DICTIONARY_REQUIRED");
        // One immutable dictionary version is the lock key. Concurrent misses cannot overwrite an indexed vector.
        db.sql("SELECT version FROM skill_dictionary WHERE version=:v FOR UPDATE").param("v",dictionary.version()).query(Long.class).single();
        if(db.sql("SELECT COUNT(*) FROM skill_vector_generation WHERE dictionary_version=:v AND model=:model")
            .param("v",dictionary.version()).param("model",EmbeddingPort.MODEL).query(Integer.class).single()==0)
            db.sql("INSERT INTO skill_vector_generation(dictionary_version,model,dimensions) VALUES (:v,:model,384)").param("v",dictionary.version()).param("model",EmbeddingPort.MODEL).update();
        var existing=db.sql("SELECT canonical FROM skill_vector_entry WHERE dictionary_version=:v AND model=:model AND canonical IN (:labels)")
            .param("v",dictionary.version()).param("model",EmbeddingPort.MODEL).param("labels",labels).query(String.class).list();
        var missing=labels.stream().filter(label->!existing.contains(label)).sorted().toList();
        if(!missing.isEmpty()) {
            List<List<Double>> vectors;
            try {vectors=embeddings.encode(missing);HybridSearchService.validateVectors(vectors,missing.size());}
            catch(InterruptedException e) {Thread.currentThread().interrupt();throw new Rejected(HttpStatus.SERVICE_UNAVAILABLE,"EMBEDDINGS_UNAVAILABLE");}
            catch(Exception e) {throw new Rejected(HttpStatus.SERVICE_UNAVAILABLE,"EMBEDDINGS_UNAVAILABLE");}
            // Validate the whole batch before any insert. The caller's transaction rolls back on expiry or error.
            for(int i=0;i<missing.size();i++) {
                String label=missing.get(i),vector;
                try {vector=json.writeValueAsString(vectors.get(i));}catch(Exception e){throw new IllegalStateException("VECTOR_SERIALIZATION");}
                db.sql("INSERT INTO skill_vector_entry(dictionary_version,model,skill_id,canonical,embedding) VALUES (:v,:model,:skill,:label,"+(postgres?"CAST(:vector AS vector)":":vector")+")")
                    .param("v",dictionary.version()).param("model",EmbeddingPort.MODEL).param("skill",skills.get(label)).param("label",label).param("vector",vector).update();
            }
        }
        var similarities=new HashMap<Pair,Double>();var lexicalMatches=new HashSet<Pair>();
        if(postgres) {
            db.sql("""
                SELECT q.canonical AS query_label,e.canonical AS indexed_label,
                       1 - (q.embedding <=> e.embedding) AS similarity,
                       EXISTS (SELECT 1 FROM unnest(tsvector_to_array(q.lexical)) term
                               WHERE e.lexical @@ plainto_tsquery('simple',term)) AS lexical_match
                FROM skill_vector_entry q JOIN skill_vector_entry e
                  ON e.dictionary_version=q.dictionary_version AND e.model=q.model
                WHERE q.dictionary_version=:v AND q.model=:model
                  AND q.canonical IN (:queries) AND e.canonical IN (:labels)
                """).param("v",dictionary.version()).param("model",EmbeddingPort.MODEL).param("queries",queries).param("labels",labels)
                .query((row,n)->{var pair=new Pair(row.getString(1),row.getString(2));similarities.put(pair,clamp(row.getDouble(3)));
                    if(row.getBoolean(4)&&HybridSearchService.lexical(pair.query(),pair.label())) lexicalMatches.add(pair);return 0;}).list();
        } else {
            // Test adapter only. PostgreSQL always executes the native pgvector cosine operator above.
            var vectors=new HashMap<String,List<Double>>();
            db.sql("SELECT canonical,embedding FROM skill_vector_entry WHERE dictionary_version=:v AND model=:model AND canonical IN (:labels)")
                .param("v",dictionary.version()).param("model",EmbeddingPort.MODEL).param("labels",labels).query((row,n)->{
                    try {vectors.put(row.getString(1),json.readValue(row.getString(2),new TypeReference<List<Double>>(){}));}
                    catch(Exception e){throw new IllegalStateException("VECTOR_STORAGE");}return 0;
                }).list();
            for(String query:queries) for(String label:labels) {
                var a=vectors.get(query);var b=vectors.get(label);double dot=0,normA=0,normB=0;
                for(int i=0;i<a.size();i++) {dot+=a.get(i)*b.get(i);normA+=a.get(i)*a.get(i);normB+=b.get(i)*b.get(i);}
                var pair=new Pair(query,label);similarities.put(pair,clamp(dot/Math.sqrt(normA*normB)));
                if(HybridSearchService.lexical(query,label)) lexicalMatches.add(pair);
            }
        }
        if(similarities.size()!=new HashSet<>(queries).size()*labels.size()) throw new IllegalStateException("VECTOR_INDEX_INCOMPLETE");
        return new Result(Map.copyOf(similarities),postgres?"pgvector-exact":"h2-test-adapter",existing.size(),missing.size(),Set.copyOf(lexicalMatches));
    }
    private static double clamp(double value) {
        if(!Double.isFinite(value)) throw new IllegalStateException("VECTOR_SIMILARITY_INVALID");
        return Math.max(-1,Math.min(1,value));
    }
}
