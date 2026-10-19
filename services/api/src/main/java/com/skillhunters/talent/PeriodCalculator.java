package com.skillhunters.talent;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.skillhunters.talent.ReviewService.*;

/** Calendar coverage, never full-time-equivalent work or a declared skill level. */
public final class PeriodCalculator {
    public static final String RULE = "calendar-union-v1";
    public record Span(LocalDate start, LocalDate endExclusive) {}
    public record Bounds(Span certain, Span possible, LocalDate earliestEnd, LocalDate latestEnd) {}
    public record Result(Long minimumMonths, Long maximumMonths, Long recencyMinimumMonths,
                         Long recencyMaximumMonths, boolean incomplete, List<Span> certainUnion, List<Span> possibleUnion) {}
    private PeriodCalculator() {}
    public static Bounds bounds(Period p) {
        if(p==null||p.precision()==DatePrecision.UNKNOWN||p.start()==null||p.end()==null) return null;
        return switch(p.precision()) {
            case MONTH -> {var start=YearMonth.parse(p.start()).atDay(1);var end=YearMonth.parse(p.end()).atEndOfMonth();
                yield new Bounds(new Span(start,end.plusDays(1)),new Span(start,end.plusDays(1)),end,end);}
            case DAY -> {var start=LocalDate.parse(p.start());var end=LocalDate.parse(p.end());
                yield new Bounds(new Span(start,end.plusDays(1)),new Span(start,end.plusDays(1)),end,end);}
            case YEAR -> {int start=Integer.parseInt(p.start()),end=Integer.parseInt(p.end());
                yield new Bounds(new Span(LocalDate.of(start,12,31),LocalDate.of(end,1,1).plusDays(1)),
                    new Span(LocalDate.of(start,1,1),LocalDate.of(end,12,31).plusDays(1)),LocalDate.of(end,1,1),LocalDate.of(end,12,31));}
            case UNKNOWN -> null;
        };
    }
    public static Result calculate(List<Period> periods,LocalDate reference) {
        var certain=new ArrayList<Span>();var possible=new ArrayList<Span>();
        LocalDate earliestLast=null,latestLast=null;boolean incomplete=false;int known=0;
        for(var p:periods) {
            var b=bounds(p);if(b==null) {incomplete=true;continue;}
            if(b.possible().start().isAfter(reference)) {incomplete=true;continue;}
            known++;
            LocalDate cutoff=reference.plusDays(1);
            certain.add(new Span(b.certain().start(),min(b.certain().endExclusive(),cutoff)));
            possible.add(new Span(b.possible().start(),min(b.possible().endExclusive(),cutoff)));
            // Future or ongoing assertions require human revalidation; cap exposure at the reference date.
            if(b.latestEnd().isAfter(reference)) incomplete=true;
            earliestLast=max(earliestLast,min(b.earliestEnd(),reference));latestLast=max(latestLast,min(b.latestEnd(),reference));
        }
        var lower=union(certain);var upper=union(possible);
        return new Result(known==0?null:months(lower),known==0?null:months(upper),
            latestLast==null?null:ChronoUnit.MONTHS.between(YearMonth.from(latestLast),YearMonth.from(reference)),
            earliestLast==null?null:ChronoUnit.MONTHS.between(YearMonth.from(earliestLast),YearMonth.from(reference)),
            incomplete||periods.isEmpty(),lower,upper);
    }
    public static List<Span> union(List<Span> spans) {
        var sorted=spans.stream().filter(s->s.start().isBefore(s.endExclusive())).sorted(Comparator.comparing(Span::start)).toList();
        var out=new ArrayList<Span>();
        for(var s:sorted) {
            if(out.isEmpty()||s.start().isAfter(out.getLast().endExclusive())) out.add(s);
            else {var last=out.removeLast();out.add(new Span(last.start(),max(last.endExclusive(),s.endExclusive())));}
        }
        return List.copyOf(out);
    }
    private static long months(List<Span> spans) {
        long months=0;
        for(var s:spans) {
            var first=YearMonth.from(s.start());if(s.start().getDayOfMonth()!=1) first=first.plusMonths(1);
            var end=YearMonth.from(s.endExclusive());
            months+=Math.max(0,ChronoUnit.MONTHS.between(first,end));
        }
        return months;
    }
    private static LocalDate min(LocalDate a,LocalDate b) {return a.isBefore(b)?a:b;}
    private static LocalDate max(LocalDate a,LocalDate b) {return a==null||a.isBefore(b)?b:a;}
}
