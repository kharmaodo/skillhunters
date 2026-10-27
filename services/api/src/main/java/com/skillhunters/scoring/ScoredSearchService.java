package com.skillhunters.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.search.*;
import com.skillhunters.search.StructuredSearchService.*;
import com.skillhunters.shared.ApiProblem.Rejected;
import com.skillhunters.talent.CandidateService;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ScoredSearchService {
    public static final int MAX_SCANNED=1000;
    public record Request(Query query,boolean hybrid,double minimumSimilarity,Long configurationVersion) {
        public Request(Query query,boolean hybrid,double minimumSimilarity) {this(query,hybrid,minimumSimilarity,null);}
    }
    public record Metadata(UUID searchId,Trace trace,boolean hybrid,String model,String retrievalStrategy,double minimumSimilarity,
            String scoringRule,String weightsVersion,int halfLifeMonths,OffsetDateTime createdAt,OffsetDateTime expiresAt,int scanned,EvidenceScore.Policy policy) {}
    public record Item(int rank,Hit hit,EvidenceScore.Score score,List<HybridSearchService.Signal> signals,String fingerprint) {}
    public record Page(Metadata metadata,List<Item> items,Integer nextCursor) {}
    private record Payload(Trace trace,Hit hit,EvidenceScore.Score score,List<HybridSearchService.Signal> signals,String model,String strategy) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final ObjectMapper json;
    private final ScoringConfigurations configs;
    private final StructuredSearchService structured;private final HybridSearchService hybrid;private final CandidateService candidates;
    public ScoredSearchService(JdbcClient db,TransactionTemplate tx,ObjectMapper json,StructuredSearchService structured,HybridSearchService hybrid,CandidateService candidates,ScoringConfigurations configs) {
        this.configs=configs;this.db=db;this.tx=tx;this.json=json;this.structured=structured;this.hybrid=hybrid;this.candidates=candidates;
    }
    public Page create(UUID actor,Request request) {
        if(request==null||request.query()==null||request.query().cursor()!=null||!Double.isFinite(request.minimumSimilarity())||request.minimumSimilarity()<0.5||request.minimumSimilarity()>1)
            throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_SCORED_SEARCH");
        return tx.execute(t->{
            Query q=request.query();
            var policy=configs.policy(actor,q.poolId(),request.configurationVersion());
            String cursor=null;Trace trace=null;int scanned=0;
            var hits=new ArrayList<Hit>();var signals=new ArrayList<HybridSearchService.Signal>();
            do {
                var input=new Query(q.poolId(),q.criteria(),q.strict(),q.experienceKinds(),trace==null?q.referenceDate():trace.referenceDate(),trace==null?q.dictionaryVersion():Long.valueOf(trace.dictionaryVersion()),cursor,q.limit());
                if(request.hybrid()) {
                    var page=hybrid.search(actor,new HybridSearchService.Request(input,request.minimumSimilarity()));
                    trace=page.trace();hits.addAll(page.items());signals.addAll(page.retrieval().signals());scanned+=page.scanned();cursor=page.nextCursor();
                } else {
                    var page=structured.search(actor,input);trace=page.trace();hits.addAll(page.items());scanned+=page.scanned();cursor=page.nextCursor();
                }
                // Refuse the complete request, never advertise a partial page as a global ranking.
                if(scanned>MAX_SCANNED||scanned==MAX_SCANNED&&cursor!=null) throw new Rejected(HttpStatus.UNPROCESSABLE_ENTITY,"SCORING_CORPUS_TOO_LARGE");
            } while(cursor!=null);
            if(db.sql("SELECT COUNT(*) FROM scored_search WHERE actor_id=:actor AND expires_at>:now").param("actor",actor).param("now",OffsetDateTime.now(ZoneOffset.UTC)).query(Integer.class).single()>=20)
                throw new Rejected(HttpStatus.TOO_MANY_REQUESTS,"SCORING_SNAPSHOT_QUOTA");
            boolean active=trace.criteria().stream().anyMatch(c->(c.importance()==Importance.REQUIRED?policy.required():policy.desired())>0||c.minimumMonths()!=null&&policy.experience()>0||c.maximumRecencyMonths()!=null&&policy.recency()>0);
            if(!active) throw new Rejected(HttpStatus.UNPROCESSABLE_ENTITY,"SCORING_NO_ACTIVE_WEIGHT");
            var frozenTrace=trace;
            String model=request.hybrid()?EmbeddingPort.MODEL:null,strategy=request.hybrid()?HybridSearchService.STRATEGY:StructuredSearchService.RULE;
            var rows=new ArrayList<Item>();
            for(var hit:hits) {
                var score=EvidenceScore.calculate(hit.criteria(),policy);
                var matching=signals.stream().filter(s->s.candidateId().equals(hit.candidateId())).toList();
                rows.add(new Item(0,hit,score,matching,hash(new Payload(frozenTrace,hit,score,matching,model,strategy))));
            }
            rows.sort(Comparator.comparingDouble((Item i)->i.score().documented()).reversed().thenComparing(i->i.hit().candidateId().toString()));
            OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);UUID id=UUID.randomUUID();
            var meta=new Metadata(id,trace,request.hybrid(),model,strategy,request.minimumSimilarity(),EvidenceScore.RULE,policy.version(),EvidenceScore.HALF_LIFE_MONTHS,now,now.plusHours(24),scanned,policy);
            db.sql("INSERT INTO scored_search(id,actor_id,pool_id,created_at,expires_at,metadata_json) VALUES (:id,:actor,:pool,:now,:expires,:json)")
                .param("id",id).param("actor",actor).param("pool",q.poolId()).param("now",now).param("expires",meta.expiresAt()).param("json",write(meta)).update();
            int rank=0;
            for(var row:rows) {
                var item=new Item(++rank,row.hit(),row.score(),row.signals(),row.fingerprint());
                try {var c=candidates.get(actor,item.hit().candidateId());if(!c.poolId().equals(q.poolId())) continue;}
                catch(Rejected e) {if(e.status==HttpStatus.NOT_FOUND) continue;throw e;}
                db.sql("INSERT INTO scored_search_item(search_id,candidate_id,publication_id,rank_position,snapshot_json) VALUES (:search,:candidate,:publication,:rank,:json)")
                    .param("search",id).param("candidate",item.hit().candidateId()).param("publication",item.hit().publicationId()).param("rank",rank).param("json",write(item)).update();
            }
            db.sql("INSERT INTO identity_audit(id,actor_id,action,pool_id) VALUES (:id,:actor,'SCORED_SEARCH',:pool)")
                .param("id",id).param("actor",actor).param("pool",q.poolId()).update();
            return page(actor,id,0,q.limit());
        });
    }
    public Page page(UUID actor,UUID id,int cursor,int limit) {
        if(cursor<0||cursor>MAX_SCANNED||limit<1||limit>25) throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_SCORED_SEARCH");
        return tx.execute(t->{
            var meta=access(actor,id);
            var values=db.sql("""
                SELECT s.snapshot_json FROM scored_search_item s
                JOIN candidate_profile c ON c.id=s.candidate_id JOIN document_import d ON d.id=c.import_id
                WHERE s.search_id=:id AND s.rank_position>:cursor AND d.expires_at>:now AND d.pool_id=:pool
                ORDER BY s.rank_position LIMIT :limit
                """).param("id",id).param("cursor",cursor).param("now",OffsetDateTime.now(ZoneOffset.UTC)).param("pool",meta.trace().poolId()).param("limit",limit+1).query(String.class).list();
            var items=new ArrayList<Item>();
            for(var value:values) {
                var item=read(value,Item.class);
                try {var c=candidates.get(actor,item.hit().candidateId());if(c.poolId().equals(meta.trace().poolId())) items.add(item);}
                catch(Rejected e) {if(e.status!=HttpStatus.NOT_FOUND) throw e;}
            }
            boolean more=items.size()>limit;var visible=items.stream().limit(limit).toList();
            return new Page(meta,visible,more?visible.getLast().rank():null);
        });
    }
    public Item replay(UUID actor,UUID search,UUID candidate) {
        return tx.execute(t->{
            var meta=access(actor,search);var current=candidates.get(actor,candidate);
            if(!current.poolId().equals(meta.trace().poolId())) missing();
            var item=db.sql("SELECT snapshot_json FROM scored_search_item WHERE search_id=:search AND candidate_id=:candidate")
                .param("search",search).param("candidate",candidate).query(String.class).optional().map(v->read(v,Item.class)).orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"SCORE_NOT_FOUND"));
            var score=EvidenceScore.calculate(item.hit().criteria(),meta.policy()==null?EvidenceScore.defaults():meta.policy());
            if(!score.equals(item.score())||!hash(new Payload(meta.trace(),item.hit(),score,item.signals(),meta.model(),meta.retrievalStrategy())).equals(item.fingerprint()))
                throw new Rejected(HttpStatus.CONFLICT,"SCORE_REPLAY_MISMATCH");
            candidates.get(actor,candidate);
            return new Item(item.rank(),item.hit(),score,item.signals(),item.fingerprint());
        });
    }
    /** Re-evaluate with original criteria, date, dictionary and weights; never overwrite history. */
    public Page refresh(UUID actor,UUID search) {
        return tx.execute(t->{
            var m=access(actor,search);var trace=m.trace();
            var criteria=trace.criteria().stream().map(c->new Criterion(c.original(),c.importance(),c.minimumMonths(),c.maximumRecencyMonths())).toList();
            long version=m.policy()==null||EvidenceScore.WEIGHTS.equals(m.weightsVersion())?0:Long.parseLong(m.weightsVersion().substring(m.weightsVersion().lastIndexOf('/')+1).replace("scoring-",""));
            return create(actor,new Request(new Query(trace.poolId(),criteria,trace.strict(),trace.experienceKinds(),trace.referenceDate(),trace.dictionaryVersion(),null,25),m.hybrid(),m.minimumSimilarity(),version));
        });
    }
    private Metadata access(UUID actor,UUID id) {
        if(!db.sql("SELECT enabled FROM app_user WHERE id=:actor FOR UPDATE").param("actor",actor).query(Boolean.class).optional().orElse(false))
            throw new Rejected(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED");
        var meta=db.sql("""
            SELECT s.metadata_json FROM scored_search s WHERE s.id=:id AND s.actor_id=:actor AND s.expires_at>:now
            AND EXISTS (SELECT 1 FROM membership m WHERE m.user_id=:actor AND m.pool_id=s.pool_id)
            """).param("id",id).param("actor",actor).param("now",OffsetDateTime.now(ZoneOffset.UTC)).query(String.class).optional().map(v->read(v,Metadata.class))
            .orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"SCORE_NOT_FOUND"));
        if(!EvidenceScore.RULE.equals(meta.scoringRule())||!(meta.policy()==null?EvidenceScore.WEIGHTS:meta.policy().version()).equals(meta.weightsVersion())) throw new Rejected(HttpStatus.CONFLICT,"SCORE_RULE_UNAVAILABLE");
        return meta;
    }
    @Scheduled(initialDelayString="${app.scoring.cleanup-ms:3600000}",fixedDelayString="${app.scoring.cleanup-ms:3600000}")
    public void cleanup() {
        tx.executeWithoutResult(t->{
            db.sql("DELETE FROM scored_search WHERE expires_at<=:now").param("now",OffsetDateTime.now(ZoneOffset.UTC)).update();
            db.sql("DELETE FROM scored_search_item WHERE candidate_id IN (SELECT c.id FROM candidate_profile c JOIN document_import d ON d.id=c.import_id WHERE d.expires_at<=:now)")
                .param("now",OffsetDateTime.now(ZoneOffset.UTC)).update();
        });
    }
    private static void missing() {throw new Rejected(HttpStatus.NOT_FOUND,"SCORE_NOT_FOUND");}
    private String hash(Object value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException("SCORE_HASH");}}
    private String write(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("SCORE_SERIALIZATION");}}
    private <T>T read(String value,Class<T> type) {try{return json.readValue(value,type);}catch(Exception e){throw new IllegalStateException("SCORE_STORAGE");}}
}
