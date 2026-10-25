package com.skillhunters.scoring;

import com.skillhunters.search.StructuredSearchService.*;
import com.skillhunters.search.StructuredMatcher.Status;
import java.math.*;
import java.util.*;

/** Pure, allowlisted calculation. Neither identity, narrative nor retrieval similarity is an input. */
public final class EvidenceScore {
    public static final String RULE="documented-skills-v1";
    public static final String WEIGHTS="skills-35-15-20-10-v1";
    public static final int HALF_LIFE_MONTHS=36;
    public record Component(int criterionIndex,String dimension,int baseWeight,double normalizedWeight,
            double documentedValue,double upperValue,double contribution,double upperContribution,
            String certainty,List<Integer> sourceIndices) {}
    public record Score(String rule,String weightsVersion,int halfLifeMonths,double documented,double upper,
            double evidenceCoverage,double quality,boolean obligationsToVerify,List<Component> components) {}
    private record Part(int index,String dimension,int weight,int count,double low,double high,boolean known,boolean quality,List<Integer> sources) {}
    private EvidenceScore() {}
    public static Score calculate(List<CriterionResult> criteria) {
        if(criteria==null||criteria.isEmpty()) throw new IllegalArgumentException("EMPTY_SCORE");
        int required=(int)criteria.stream().filter(c->c.criterion().importance()==Importance.REQUIRED).count();
        int desired=criteria.size()-required;
        int durations=(int)criteria.stream().filter(c->c.criterion().minimumMonths()!=null).count();
        int recencies=(int)criteria.stream().filter(c->c.criterion().maximumRecencyMonths()!=null).count();
        int denominator=(required>0?35:0)+(desired>0?15:0)+(durations>0?20:0)+(recencies>0?10:0);
        var parts=new ArrayList<Part>();boolean obligations=false;
        for(int i=0;i<criteria.size();i++) {
            var c=criteria.get(i);var q=c.criterion();var coverage=c.coverage();
            boolean present=c.presence()==Status.DOCUMENTED&&!c.evidence().isEmpty();
            boolean proof=present&&c.evidence().stream().allMatch(e->!e.citations().isEmpty());
            var sources=c.evidence().stream().map(Evidence::sourceIndex).distinct().sorted().toList();
            boolean must=q.importance()==Importance.REQUIRED;
            if(must&&c.status()!=Status.DOCUMENTED) obligations=true;
            // "Not documented" is not proof of absence: the possible contribution remains visible.
            parts.add(new Part(i,must?"REQUIRED":"DESIRED",must?35:15,must?required:desired,present?1:0,1,present,proof,sources));
            if(q.minimumMonths()!=null) {
                boolean dated=present&&coverage.minimumMonths()!=null;
                double low=dated?Math.min(1,coverage.minimumMonths()/(double)q.minimumMonths()):0;
                double high=dated&&!coverage.incomplete()&&coverage.maximumMonths()!=null?Math.min(1,coverage.maximumMonths()/(double)q.minimumMonths()):1;
                boolean known=dated&&!coverage.incomplete()&&Objects.equals(coverage.minimumMonths(),coverage.maximumMonths());
                parts.add(new Part(i,"EXPERIENCE",20,durations,low,Math.max(low,high),known,proof&&known,sources));
            }
            if(q.maximumRecencyMonths()!=null) {
                boolean dated=present&&coverage.recencyMaximumMonths()!=null;
                double low=dated?StrictMath.pow(2,-coverage.recencyMaximumMonths()/(double)HALF_LIFE_MONTHS):0;
                double high=dated&&!coverage.incomplete()&&coverage.recencyMinimumMonths()!=null?StrictMath.pow(2,-coverage.recencyMinimumMonths()/(double)HALF_LIFE_MONTHS):1;
                boolean known=dated&&!coverage.incomplete()&&Objects.equals(coverage.recencyMinimumMonths(),coverage.recencyMaximumMonths());
                parts.add(new Part(i,"RECENCY",10,recencies,low,Math.max(low,high),known,proof&&known,sources));
            }
        }
        var components=new ArrayList<Component>();double known=0,quality=0;
        BigDecimal total=BigDecimal.ZERO,upper=BigDecimal.ZERO;
        BigDecimal allocated=BigDecimal.ZERO;
        for(int index=0;index<parts.size();index++) {
            var p=parts.get(index);
            double budget=index==parts.size()-1?BigDecimal.valueOf(100).subtract(allocated).doubleValue():round(100*p.weight()/(double)(denominator*p.count()));
            allocated=allocated.add(BigDecimal.valueOf(budget));
            double w=budget/100;
            double contribution=round(budget*p.low()),possible=round(budget*p.high());
            total=total.add(BigDecimal.valueOf(contribution));upper=upper.add(BigDecimal.valueOf(possible));
            if(p.known()) known+=w;if(p.quality()) quality+=w;
            components.add(new Component(p.index(),p.dimension(),p.weight(),w,p.low(),p.high(),contribution,possible,p.known()?"KNOWN":p.low()>0?"PARTIAL":"UNKNOWN",p.sources()));
        }
        return new Score(RULE,WEIGHTS,HALF_LIFE_MONTHS,total.doubleValue(),upper.doubleValue(),round(100*known),round(100*quality),obligations,List.copyOf(components));
    }
    private static double round(double value) {return BigDecimal.valueOf(value).setScale(6,RoundingMode.HALF_UP).doubleValue();}
}
