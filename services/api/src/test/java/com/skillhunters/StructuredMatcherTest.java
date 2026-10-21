package com.skillhunters;

import com.skillhunters.search.StructuredMatcher;
import com.skillhunters.search.StructuredMatcher.Status;
import com.skillhunters.talent.PeriodCalculator;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StructuredMatcherTest {
    PeriodCalculator.Result coverage(Long min,Long max,Long recentMin,Long recentMax,boolean partial) {return new PeriodCalculator.Result(min,max,recentMin,recentMax,partial,List.of(),List.of());}
    @Test void missingDatesNeverPassDurationOrRecencyAsZero() {
        var r=coverage(null,null,null,null,true);
        assertThat(StructuredMatcher.duration(1,r)).isEqualTo(Status.UNKNOWN);
        assertThat(StructuredMatcher.recency(0,r)).isEqualTo(Status.UNKNOWN);
    }
    @Test void uncertaintyBoundsAreNotRoundedIntoProof() {
        var r=coverage(0L,12L,2L,13L,false);
        assertThat(StructuredMatcher.duration(6,r)).isEqualTo(Status.UNKNOWN);
        assertThat(StructuredMatcher.duration(13,r)).isEqualTo(Status.NOT_DOCUMENTED);
        assertThat(StructuredMatcher.recency(6,r)).isEqualTo(Status.UNKNOWN);
        assertThat(StructuredMatcher.recency(13,r)).isEqualTo(Status.DOCUMENTED);
    }
    @Test void partialCoverageCanProveMinimumButCannotDisproveUnobservedPeriods() {
        var r=coverage(12L,12L,25L,25L,true);
        assertThat(StructuredMatcher.duration(12,r)).isEqualTo(Status.DOCUMENTED);
        assertThat(StructuredMatcher.duration(24,r)).isEqualTo(Status.UNKNOWN);
        assertThat(StructuredMatcher.recency(24,r)).isEqualTo(Status.UNKNOWN);
    }
    @Test void optionalThresholdsAreNeutralAndCombinedEvidenceIsExplicit() {
        var r=coverage(null,null,null,null,true);
        assertThat(StructuredMatcher.duration(null,r)).isEqualTo(Status.DOCUMENTED);
        assertThat(StructuredMatcher.combine(List.of(Status.DOCUMENTED,Status.UNKNOWN))).isEqualTo(Status.UNKNOWN);
        assertThat(StructuredMatcher.combine(List.of(Status.NOT_DOCUMENTED,Status.UNKNOWN))).isEqualTo(Status.NOT_DOCUMENTED);
    }
}
