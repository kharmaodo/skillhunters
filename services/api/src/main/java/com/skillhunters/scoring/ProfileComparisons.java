package com.skillhunters.scoring;

import com.skillhunters.documents.StructuredExtractor;
import com.skillhunters.shared.ApiProblem.Rejected;
import com.skillhunters.talent.ReviewService;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Comparisons share the immutable criteria, reference date and scoring policy. */
@Service
public class ProfileComparisons {
    public record Request(List<UUID> candidateIds) {}
    public record Declaration(String value, ReviewService.Period period, List<StructuredExtractor.Evidence> citations) {}
    public record Profile(ScoredSearchService.Item snapshot, List<Declaration> declaredExperience) {}
    public record Result(UUID comparisonId, OffsetDateTime createdAt, ScoredSearchService.Metadata metadata, List<Profile> profiles) {}
    private final ScoredSearchService scores;
    private final ReviewService reviews;
    private final JdbcClient db;
    private final TransactionTemplate tx;
    public ProfileComparisons(ScoredSearchService scores, ReviewService reviews, JdbcClient db, TransactionTemplate tx) {
        this.scores=scores;this.reviews=reviews;this.db=db;this.tx=tx;
    }
    public Result compare(UUID actor, UUID search, Request request) {
        if(request==null||request.candidateIds()==null||request.candidateIds().size()<2||request.candidateIds().size()>4
                ||request.candidateIds().stream().anyMatch(Objects::isNull)||new HashSet<>(request.candidateIds()).size()!=request.candidateIds().size())
            throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_COMPARISON");
        return tx.execute(t->{
            // Actor lock is shared with revocation and held through response projection.
            var metadata=scores.access(actor,search);
            if(db.sql("SELECT COUNT(*) FROM profile_comparison WHERE search_id=:s").param("s",search).query(Integer.class).single()>=100)
                throw new Rejected(HttpStatus.TOO_MANY_REQUESTS,"COMPARISON_QUOTA");
            var profiles=new HashMap<UUID,Profile>();
            for(UUID candidate:request.candidateIds().stream().sorted().toList()) {
                var item=scores.replay(actor,search,candidate);
                if(!item.hit().criteria().stream().map(c->c.criterion()).toList().equals(metadata.trace().criteria()))
                    throw new Rejected(HttpStatus.CONFLICT,"SCORE_REPLAY_MISMATCH");
                var revision=reviews.revision(actor,candidate,item.hit().publicationId());
                var source=reviews.get(actor,candidate).source();
                if(!source.id().equals(revision.sourceRevisionId())||!source.id().equals(item.hit().sourceRevisionId()))
                    throw new Rejected(HttpStatus.CONFLICT,"SCORE_REPLAY_MISMATCH");
                var declarations=revision.items().stream()
                    .filter(i->i.decision()==ReviewService.Decision.VALIDATED&&source.proposal().claims().get(i.sourceIndex()).field()==StructuredExtractor.Field.EXPERIENCE)
                    .map(i->new Declaration(i.value(),i.period(),source.proposal().claims().get(i.sourceIndex()).evidence())).toList();
                profiles.put(candidate,new Profile(item,declarations));
            }
            // Reject the whole comparison if any source expired while assembling it.
            for(UUID candidate:request.candidateIds()) scores.replay(actor,search,candidate);
            UUID id=UUID.randomUUID();OffsetDateTime now=OffsetDateTime.now(ZoneOffset.UTC);
            db.sql("INSERT INTO profile_comparison(id,search_id,created_at) VALUES (:id,:s,:now)")
                .param("id",id).param("s",search).param("now",now).update();
            for(UUID candidate:request.candidateIds()) db.sql("INSERT INTO profile_comparison_member(comparison_id,search_id,candidate_id) VALUES (:id,:s,:c)")
                .param("id",id).param("s",search).param("c",candidate).update();
            return new Result(id,now,metadata,request.candidateIds().stream().map(profiles::get).toList());
        });
    }
}
