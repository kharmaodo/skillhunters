package com.skillhunters;

import com.skillhunters.talent.PeriodCalculator;
import com.skillhunters.talent.ReviewService.*;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PeriodCalculatorTest {
    final LocalDate reference=LocalDate.of(2026,10,16);
    Period month(String start,String end){return new Period(start,end,DatePrecision.MONTH,Kind.EMPLOYMENT);}
    @Test void overlappingMissionsCountNineMonthsNotTwelveAndDuplicatesDoNotChangeUnion() {
        var a=month("2024-01","2024-06");var b=month("2024-04","2024-09");
        var r=PeriodCalculator.calculate(List.of(b,a,a),reference);
        assertThat(r.minimumMonths()).isEqualTo(9);assertThat(r.maximumMonths()).isEqualTo(9);
        assertThat(r.certainUnion()).hasSize(1);assertThat(r.recencyMinimumMonths()).isEqualTo(25);
    }
    @Test void inclusiveFebruaryToAugustMeansSevenCalendarMonths() {
        assertThat(PeriodCalculator.calculate(List.of(month("2018-02","2018-08")),reference).minimumMonths()).isEqualTo(7);
    }
    @Test void unknownIsNotZeroAndMixedCoverageStaysIncomplete() {
        var missing=new Period(null,null,DatePrecision.UNKNOWN,Kind.EMPLOYMENT);
        assertThat(PeriodCalculator.calculate(List.of(missing),reference).minimumMonths()).isNull();
        var partial=PeriodCalculator.calculate(List.of(missing,month("2024-01","2024-02")),reference);
        assertThat(partial.minimumMonths()).isEqualTo(2);assertThat(partial.incomplete()).isTrue();
        assertThat(PeriodCalculator.calculate(List.of(),reference).recencyMinimumMonths()).isNull();
    }
    @Test void yearPrecisionReturnsBoundsRatherThanInventingMonths() {
        var r=PeriodCalculator.calculate(List.of(new Period("2024","2024",DatePrecision.YEAR,Kind.FREELANCE)),reference);
        assertThat(r.minimumMonths()).isZero();assertThat(r.maximumMonths()).isEqualTo(12);
        assertThat(r.recencyMinimumMonths()).isEqualTo(22);assertThat(r.recencyMaximumMonths()).isEqualTo(33);
    }
    @Test void dailyFragmentsAreUnitedBeforeCompleteCalendarMonthsAreCounted() {
        var r=PeriodCalculator.calculate(List.of(new Period("2024-02-01","2024-02-14",DatePrecision.DAY,Kind.EMPLOYMENT),
            new Period("2024-02-15","2024-02-29",DatePrecision.DAY,Kind.EMPLOYMENT)),reference);
        assertThat(r.minimumMonths()).isEqualTo(1);
        assertThat(PeriodCalculator.calculate(List.of(new Period("2024-02-02","2024-02-29",DatePrecision.DAY,Kind.EMPLOYMENT)),reference).minimumMonths()).isZero();
    }
    @Test void futureExposureIsExcludedAndActiveMonthIsNotCountedBeforeItsEnd() {
        var r=PeriodCalculator.calculate(List.of(month("2026-01","2026-12")),reference);
        assertThat(r.minimumMonths()).isEqualTo(9);assertThat(r.incomplete()).isTrue();
        assertThat(PeriodCalculator.calculate(List.of(month("2027-01","2027-12")),reference).minimumMonths()).isNull();
    }
}
