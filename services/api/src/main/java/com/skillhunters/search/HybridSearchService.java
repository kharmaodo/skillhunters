package com.skillhunters.search;

import com.skillhunters.documents.StructuredExtractor;
import com.skillhunters.shared.ApiProblem.Rejected;
import com.skillhunters.talent.ReviewService;
import com.skillhunters.taxonomy.TaxonomyService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class HybridSearchService {
    public static final String STRATEGY="scoped-live-union-v1";
    public record Request(StructuredSearchService.Query query,double minimumSimilarity) {}
    public record Signal(UUID candidateId,int criterionIndex,int sourceIndex,String matchedSkill,String channel,double similarity,List<StructuredExtractor.Evidence> citations) {}
    public record Retrieval(String model,String index,String strategy,double minimumSimilarity,List<Signal> signals) {}
    public record Result(UUID searchId,StructuredSearchService.Trace trace,List<StructuredSearchService.Hit> items,int scanned,String nextCursor,Retrieval retrieval) {}
    private record Label(UUID candidateId,int sourceIndex,String name,List<StructuredExtractor.Evidence> citations) {}
    private final StructuredSearchService structured;private final ReviewService reviews;private final TaxonomyService taxonomy;
    private final EmbeddingPort embeddings;private final TransactionTemplate tx;
    public HybridSearchService(StructuredSearchService structured,ReviewService reviews,TaxonomyService taxonomy,EmbeddingPort embeddings,TransactionTemplate tx) {
        this.structured=structured;this.reviews=reviews;this.taxonomy=taxonomy;this.embeddings=embeddings;this.tx=tx;
    }
    public Result search(UUID actor,Request request) {
        if(request==null||request.query()==null||!Double.isFinite(request.minimumSimilarity())||request.minimumSimilarity()<0.5||request.minimumSimilarity()>1)
            throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_HYBRID_SEARCH");
        var q=request.query();double threshold=request.minimumSimilarity();
        String inner=decode(q.cursor(),threshold);
        var base=new StructuredSearchService.Query(q.poolId(),q.criteria(),q.strict(),q.experienceKinds(),q.referenceDate(),q.dictionaryVersion(),inner,q.limit());
        return tx.execute(t->{
            // Acquires the actor lock and checks scope before any publication retrieval or model call.
            var page=structured.search(actor,base);
            var dictionary=taxonomy.dictionary(page.trace().dictionaryVersion());
            var terms=new LinkedHashSet<String>();var queryLabels=new ArrayList<String>();
            for(var c:page.trace().criteria()) {
                var match=TaxonomyService.normalize(dictionary,c.label());
                if(match.canonical()==null||FrenchSearchInterpreter.forbidden(match.canonical())) throw new Rejected(HttpStatus.BAD_REQUEST,"HYBRID_DICTIONARY_REQUIRED");
                queryLabels.add(match.canonical());terms.add(match.canonical());
            }
            var labels=new ArrayList<Label>();
            for(var hit:page.items()) {
                var publication=reviews.publication(actor,hit.candidateId());
                var state=reviews.get(actor,hit.candidateId());
                for(var item:publication.items()) if(item.field()==StructuredExtractor.Field.SKILL) {
                    var match=TaxonomyService.normalize(dictionary,item.value());
                    // No raw CV, identity, narrative, contact, declared level or unrecognized label enters the embedding path.
                    if(match.canonical()==null||FrenchSearchInterpreter.forbidden(match.canonical())) continue;
                    labels.add(new Label(hit.candidateId(),item.sourceIndex(),match.canonical(),state.source().proposal().claims().get(item.sourceIndex()).evidence()));
                    terms.add(match.canonical());
                }
            }
            if(terms.size()>128) throw new Rejected(HttpStatus.UNPROCESSABLE_ENTITY,"HYBRID_PAGE_TOO_DENSE");
            var vectors=new HashMap<String,List<Double>>();
            if(!labels.isEmpty()) {
                List<List<Double>> encoded;
                try {encoded=embeddings.encode(List.copyOf(terms));validateVectors(encoded,terms.size());}
                catch(Exception e) {throw new Rejected(HttpStatus.SERVICE_UNAVAILABLE,"EMBEDDINGS_UNAVAILABLE");}
                int i=0;for(String term:terms) vectors.put(term,encoded.get(i++));
            }
            var signals=new ArrayList<Signal>();var included=new ArrayList<StructuredSearchService.Hit>();
            for(var hit:page.items()) {
                int before=signals.size();
                for(int i=0;i<queryLabels.size();i++) {
                    String wanted=queryLabels.get(i);var criterion=hit.criteria().get(i);
                    for(var label:labels) if(label.candidateId().equals(hit.candidateId())) {
                        double cosine=cosine(vectors.get(wanted),vectors.get(label.name()));
                        boolean exact=criterion.evidence().stream().anyMatch(e->e.sourceIndex()==label.sourceIndex());
                        String channel=exact?"STRUCTURED":lexical(wanted,label.name())?"LEXICAL":cosine>=threshold?"VECTOR":null;
                        if(channel!=null) signals.add(new Signal(hit.candidateId(),i,label.sourceIndex(),label.name(),channel,cosine,label.citations()));
                    }
                }
                // Exact structured evidence is never dropped merely because a dictionary entry was removed/renamed.
                if(signals.size()>before||hit.criteria().stream().anyMatch(c->!c.evidence().isEmpty())) {
                    reviews.publication(actor,hit.candidateId()); // Live expiry/access recheck after inference, before return.
                    included.add(hit);
                }
            }
            return new Result(page.searchId(),page.trace(),List.copyOf(included),page.scanned(),encode(page.nextCursor(),threshold),
                new Retrieval(EmbeddingPort.MODEL,"published-skill-projection-v1/dictionary-"+dictionary.version(),STRATEGY,threshold,List.copyOf(signals)));
        });
    }
    public static boolean lexical(String a,String b) {
        var left=new HashSet<>(Arrays.asList(TaxonomyService.key(a).split("[^\\p{L}\\p{N}+#.]+")));
        left.remove("");return Arrays.stream(TaxonomyService.key(b).split("[^\\p{L}\\p{N}+#.]+")).anyMatch(left::contains);
    }
    public static void validateVectors(List<List<Double>> vectors,int size) {
        if(vectors==null||vectors.size()!=size) throw new IllegalArgumentException("VECTOR_COUNT");
        for(var vector:vectors) {
            if(vector==null||vector.size()!=EmbeddingPort.DIMENSIONS) throw new IllegalArgumentException("VECTOR_DIMENSION");
            double norm=0;
            for(Double value:vector) {if(value==null||!Double.isFinite(value)) throw new IllegalArgumentException("VECTOR_VALUE");norm+=value*value;}
            if(Math.abs(norm-1)>0.01) throw new IllegalArgumentException("VECTOR_NORM");
        }
    }
    public static double cosine(List<Double> a,List<Double> b) {
        double result=0;for(int i=0;i<a.size();i++) result+=a.get(i)*b.get(i);
        return Math.max(-1,Math.min(1,result));
    }
    private static String encode(String cursor,double threshold) {
        return cursor==null?null:Base64.getUrlEncoder().withoutPadding().encodeToString((EmbeddingPort.MODEL+"|"+threshold+"|"+cursor).getBytes(StandardCharsets.UTF_8));
    }
    private static String decode(String cursor,double threshold) {
        if(cursor==null) return null;
        try {
            if(cursor.length()>500) throw new IllegalArgumentException();
            var parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split("\\|",-1);
            if(parts.length!=3||!parts[0].equals(EmbeddingPort.MODEL)||!parts[1].equals(""+threshold)) throw new IllegalArgumentException();
            return parts[2];
        } catch(IllegalArgumentException e) {throw new Rejected(HttpStatus.BAD_REQUEST,"SEARCH_CURSOR_CHANGED");}
    }
}
