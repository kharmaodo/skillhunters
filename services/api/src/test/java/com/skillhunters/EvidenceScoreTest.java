package com.skillhunters;

import com.skillhunters.scoring.EvidenceScore;
import com.skillhunters.search.StructuredSearchService.*;
import com.skillhunters.search.StructuredMatcher.Status;
import com.skillhunters.talent.PeriodCalculator;
import com.skillhunters.documents.StructuredExtractor;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EvidenceScoreTest {
    CriterionResult criterion(Importance importance,boolean present,Integer target,Integer recency,Long months,Long age,boolean incomplete) {
        var normalized=new Normalized("Java","Java",null,importance,target,recency);
        var evidence=present?List.of(new Evidence(0,"Java","Java",1L,List.of(new StructuredExtractor.Evidence(0,"document",0,4,"Java")))):List.<Evidence>of();
        var state=present?Status.DOCUMENTED:Status.NOT_DOCUMENTED;
        return new CriterionResult(normalized,state,months==null?Status.UNKNOWN:Status.DOCUMENTED,age==null?Status.UNKNOWN:Status.DOCUMENTED,
            present&& (target==null||months!=null)&&(recency==null||age!=null)?Status.DOCUMENTED:Status.UNKNOWN,
            new PeriodCalculator.Result(months,months,age,age,incomplete,List.of(),List.of()),evidence,List.of());
    }
    @Test void independentlyCalculatedContributionsAndUnknownUpperBoundAreReproducible() {
        var input=List.of(criterion(Importance.REQUIRED,true,24,24,12L,36L,false),criterion(Importance.DESIRED,false,null,null,null,null,true));
        var score=EvidenceScore.calculate(input);
        assertThat(score.documented()).isEqualTo(62.5);assertThat(score.upper()).isEqualTo(81.25);
        assertThat(score.evidenceCoverage()).isEqualTo(81.25);assertThat(score.quality()).isEqualTo(81.25);
        assertThat(score.components()).extracting(EvidenceScore.Component::contribution).containsExactly(43.75,12.5,6.25,0.0);
        assertThat(EvidenceScore.calculate(input)).isEqualTo(score);
    }
    @Test void noDatesMeansUnknownNotZeroExperienceAndNoDeclaredLevelIsConsumed() {
        var score=EvidenceScore.calculate(List.of(criterion(Importance.REQUIRED,true,24,12,null,null,true)));
        assertThat(score.documented()).isCloseTo(100*35.0/65,within(0.000001));assertThat(score.upper()).isEqualTo(100);
        assertThat(score.obligationsToVerify()).isTrue();
        assertThat(score.components()).filteredOn(c->!c.dimension().equals("REQUIRED")).allSatisfy(c->{assertThat(c.certainty()).isEqualTo("UNKNOWN");assertThat(c.documentedValue()).isZero();assertThat(c.upperValue()).isEqualTo(1);});
    }
    @Test void weightsAreIdenticalForKnownAndUnknownCandidatesAndTotalsNeverExceed100() {
        var known=new ArrayList<CriterionResult>();var unknown=new ArrayList<CriterionResult>();
        for(int i=0;i<10;i++){var importance=i<7?Importance.REQUIRED:Importance.DESIRED;known.add(criterion(importance,true,24,12,24L,0L,false));unknown.add(criterion(importance,false,24,12,null,null,true));}
        var a=EvidenceScore.calculate(known);var b=EvidenceScore.calculate(unknown);
        assertThat(a.documented()).isEqualTo(100);assertThat(a.upper()).isEqualTo(100);assertThat(b.documented()).isZero();assertThat(b.upper()).isEqualTo(100);
        assertThat(a.components()).extracting(EvidenceScore.Component::normalizedWeight).containsExactlyElementsOf(b.components().stream().map(EvidenceScore.Component::normalizedWeight).toList());
        assertThat(a.components().stream().map(c->BigDecimal.valueOf(c.contribution())).reduce(BigDecimal.ZERO,BigDecimal::add).doubleValue()).isEqualTo(a.documented());
    }
    @Test void uncertainYearsAndIncompletePeriodsRetainBoundsAndQualityIsNotAMultiplier() {
        var c=criterion(Importance.REQUIRED,true,24,12,12L,36L,true);
        var score=EvidenceScore.calculate(List.of(c));
        assertThat(score.components().get(1).certainty()).isEqualTo("PARTIAL");assertThat(score.components().get(1).upperValue()).isEqualTo(1);
        assertThat(score.quality()).isLessThan(100);assertThat(score.documented()).isGreaterThan(score.quality());
        var exact=EvidenceScore.calculate(List.of(criterion(Importance.REQUIRED,true,null,null,null,null,true)));
        assertThat(exact.documented()).isEqualTo(100);assertThat(exact.components()).hasSize(1);
    }
}
