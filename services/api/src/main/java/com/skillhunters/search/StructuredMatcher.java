package com.skillhunters.search;

import com.skillhunters.talent.PeriodCalculator;
import java.util.List;

/** Evidence states concern the requested criterion, never a candidate's aptitude. */
public final class StructuredMatcher {
    public enum Status { DOCUMENTED, NOT_DOCUMENTED, UNKNOWN }
    private StructuredMatcher() {}
    public static Status duration(Integer minimum,PeriodCalculator.Result coverage) {
        if(minimum==null) return Status.DOCUMENTED;
        if(coverage.minimumMonths()==null) return Status.UNKNOWN;
        if(coverage.minimumMonths()>=minimum) return Status.DOCUMENTED;
        if(!coverage.incomplete()&&coverage.maximumMonths()<minimum) return Status.NOT_DOCUMENTED;
        return Status.UNKNOWN;
    }
    public static Status recency(Integer maximum,PeriodCalculator.Result coverage) {
        if(maximum==null) return Status.DOCUMENTED;
        if(coverage.recencyMaximumMonths()==null) return Status.UNKNOWN;
        if(coverage.recencyMaximumMonths()<=maximum) return Status.DOCUMENTED;
        if(!coverage.incomplete()&&coverage.recencyMinimumMonths()>maximum) return Status.NOT_DOCUMENTED;
        return Status.UNKNOWN;
    }
    public static Status combine(List<Status> statuses) {
        if(statuses.contains(Status.NOT_DOCUMENTED)) return Status.NOT_DOCUMENTED;
        if(statuses.contains(Status.UNKNOWN)) return Status.UNKNOWN;
        return Status.DOCUMENTED;
    }
}
