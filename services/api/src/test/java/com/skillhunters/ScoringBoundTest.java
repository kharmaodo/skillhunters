package com.skillhunters;

import com.skillhunters.scoring.ScoredSearchService;
import com.skillhunters.search.*;
import com.skillhunters.search.StructuredSearchService.*;
import com.skillhunters.talent.CandidateService;
import com.skillhunters.talent.ReviewService.Kind;
import com.skillhunters.shared.ApiProblem.Rejected;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ScoringBoundTest {
    @Test void globalRankingRejectsOverflowBeforeWritingAnyPartialSnapshot() {
        var db=mock(JdbcClient.class);var tx=mock(TransactionTemplate.class);var search=mock(StructuredSearchService.class);
        when(tx.execute(any())).thenAnswer(i->((TransactionCallback<?>)i.getArgument(0)).doInTransaction(null));
        when(search.search(any(),any())).thenReturn(new Result(UUID.randomUUID(),null,List.of(),100,"more"));
        var service=new ScoredSearchService(db,tx,new ObjectMapper(),search,mock(HybridSearchService.class),mock(CandidateService.class));
        var q=new Query(UUID.randomUUID(),List.of(new Criterion("Java",Importance.REQUIRED,null,null)),false,List.of(Kind.EMPLOYMENT),null,null,null,25);
        assertThatThrownBy(()->service.create(UUID.randomUUID(),new ScoredSearchService.Request(q,false,0.65)))
            .isInstanceOfSatisfying(Rejected.class,e->assertThat(e.code).isEqualTo("SCORING_CORPUS_TOO_LARGE"));
        verify(search,times(10)).search(any(),any());verifyNoInteractions(db);
    }
}
