package com.skillhunters.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.documents.StructuredExtractor;
import com.skillhunters.shared.ApiProblem.Rejected;
import com.skillhunters.talent.*;
import com.skillhunters.talent.ReviewService.Kind;
import com.skillhunters.taxonomy.TaxonomyService;
import com.skillhunters.search.StructuredMatcher.Status;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class StructuredSearchService {
    public static final String RULE="structured-evidence-v1";
    public enum Importance { REQUIRED, DESIRED }
    public record Criterion(String skill,Importance importance,Integer minimumMonths,Integer maximumRecencyMonths) {}
    public record Query(UUID poolId,List<Criterion> criteria,boolean strict,List<Kind> experienceKinds,LocalDate referenceDate,Long dictionaryVersion,String cursor,int limit) {}
    public record Normalized(String original,String label,UUID skillId,Importance importance,Integer minimumMonths,Integer maximumRecencyMonths) {}
    public record Trace(UUID poolId,List<Normalized> criteria,boolean strict,List<Kind> experienceKinds,LocalDate referenceDate,long dictionaryVersion,String rule,String fingerprint) {}
    public record Evidence(int sourceIndex,String validatedValue,String original,Long dictionaryVersion,List<StructuredExtractor.Evidence> citations) {}
    public record CriterionResult(Normalized criterion,Status presence,Status duration,Status recency,Status status,PeriodCalculator.Result coverage,List<Evidence> evidence,List<SkillPeriodService.Link> periodLinks) {}
    public record Hit(UUID candidateId,String displayName,String professionalTitle,UUID publicationId,UUID sourceRevisionId,long candidateVersion,Long periodLinksVersion,List<CriterionResult> criteria) {}
    public record Result(UUID searchId,Trace trace,List<Hit> items,int scanned,String nextCursor) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final ObjectMapper json;
    private final CandidateService candidates;private final ReviewService reviews;private final SkillPeriodService periods;private final TaxonomyService taxonomy;
    public StructuredSearchService(JdbcClient db,TransactionTemplate tx,ObjectMapper json,CandidateService candidates,ReviewService reviews,SkillPeriodService periods,TaxonomyService taxonomy) {
        this.db=db;this.tx=tx;this.json=json;this.candidates=candidates;this.reviews=reviews;this.periods=periods;this.taxonomy=taxonomy;
    }
    public Result search(UUID actor,Query query) {
        validate(query);
        return tx.execute(t->{
            // Same lock as grant revocation: scope is checked before candidate retrieval.
            if(!db.sql("SELECT enabled FROM app_user WHERE id=:actor FOR UPDATE").param("actor",actor).query(Boolean.class).optional().orElse(false))
                throw new Rejected(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED");
            if(db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:actor AND pool_id=:pool").param("actor",actor).param("pool",query.poolId()).query(Integer.class).single()==0)
                throw new Rejected(HttpStatus.NOT_FOUND,"POOL_NOT_FOUND");
            var dictionary=taxonomy.dictionary(query.dictionaryVersion());
            var normalized=new ArrayList<Normalized>();var seen=new HashSet<String>();
            for(var c:query.criteria()) {
                var m=TaxonomyService.normalize(dictionary,c.skill());
                String label=m.canonical()==null?c.skill().strip():m.canonical();
                String key=m.skillId()==null?TaxonomyService.key(label):m.skillId().toString();
                if(!seen.add(key)) throw new Rejected(HttpStatus.BAD_REQUEST,"DUPLICATE_SEARCH_SKILL");
                normalized.add(new Normalized(c.skill().strip(),label,m.skillId(),c.importance(),c.minimumMonths(),c.maximumRecencyMonths()));
            }
            LocalDate date=query.referenceDate()==null?LocalDate.now(ZoneOffset.UTC):query.referenceDate();
            var kinds=query.experienceKinds().stream().sorted().toList();
            String fingerprint=hash(List.of(actor,query.poolId(),normalized,query.strict(),kinds,date,dictionary.version(),RULE,query.limit()));
            var trace=new Trace(query.poolId(),List.copyOf(normalized),query.strict(),kinds,date,dictionary.version(),RULE,fingerprint);
            UUID after=cursor(query.cursor(),fingerprint);
            // Bound each live page to 100 examined candidates. No hidden corpus truncation: continuation is explicit.
            var sql=db.sql("""
                SELECT c.id FROM candidate_profile c JOIN document_import d ON d.id=c.import_id
                WHERE d.pool_id=:pool AND d.expires_at>:now
                  AND EXISTS (SELECT 1 FROM membership m WHERE m.pool_id=d.pool_id AND m.user_id=:actor)
                  AND EXISTS (SELECT 1 FROM candidate_revision r WHERE r.candidate_id=c.id AND r.status='PUBLISHED')
                """+(after==null?"":" AND c.id>:after ")+" ORDER BY c.id LIMIT 101")
                .param("pool",query.poolId()).param("now",OffsetDateTime.now(ZoneOffset.UTC)).param("actor",actor);
            if(after!=null) sql=sql.param("after",after);
            var ids=sql.query(UUID.class).list();var hits=new ArrayList<Hit>();int scanned=0;UUID last=null;
            for(UUID id:ids.subList(0,Math.min(100,ids.size()))) {
                scanned++;last=id;
                try {
                    var state=reviews.get(actor,id);var pub=reviews.publication(actor,id);
                    if(pub.revisionId()==null) continue;
                    var revision=reviews.revision(actor,id,pub.revisionId());var report=periods.get(actor,id,date);
                    var results=new ArrayList<CriterionResult>();
                    for(var criterion:normalized) results.add(evaluate(criterion,trace,pub,revision,state,report));
                    boolean include=!query.strict()||results.stream().filter(r->r.criterion().importance()==Importance.REQUIRED).allMatch(r->r.status()==Status.DOCUMENTED);
                    if(include) {
                        var c=candidates.get(actor,id); // Recheck source expiry immediately before projecting the hit.
                        hits.add(new Hit(id,c.profile().displayName(),c.profile().professionalTitle(),pub.revisionId(),revision.sourceRevisionId(),c.version(),report.lastEdit()==null?null:report.lastEdit().version(),List.copyOf(results)));
                    }
                } catch(Rejected e) {if(e.status!=HttpStatus.NOT_FOUND) throw e;} // Concurrent expiry/deletion is never returned.
                if(hits.size()==query.limit()) break;
            }
            String next=scanned<ids.size()?encode(fingerprint,last):null;
            UUID searchId=UUID.randomUUID();
            db.sql("INSERT INTO identity_audit(id,actor_id,action,pool_id) VALUES (:id,:actor,'STRUCTURED_SEARCH',:pool)")
                .param("id",searchId).param("actor",actor).param("pool",query.poolId()).update();
            return new Result(searchId,trace,List.copyOf(hits),scanned,next);
        });
    }
    private CriterionResult evaluate(Normalized c,Trace trace,ReviewService.Publication pub,ReviewService.Revision revision,ReviewService.State state,SkillPeriodService.Report report) {
        var matches=pub.items().stream().filter(i->i.field()==StructuredExtractor.Field.SKILL).filter(i->{
            var m=i.taxonomy();
            if(c.skillId()!=null&&m!=null&&m.skillId()!=null) return c.skillId().equals(m.skillId());
            return TaxonomyService.key(c.original()).equals(TaxonomyService.key(i.value()))||TaxonomyService.key(c.label()).equals(TaxonomyService.key(i.value()));
        }).toList();
        boolean unknown=revision.items().stream().anyMatch(i->state.source().proposal().claims().get(i.sourceIndex()).field()==StructuredExtractor.Field.SKILL&&i.decision()==ReviewService.Decision.UNKNOWN);
        Status presence=matches.isEmpty()?(unknown?Status.UNKNOWN:Status.NOT_DOCUMENTED):Status.DOCUMENTED;
        var indices=matches.stream().map(ReviewService.Indexed::sourceIndex).toList();
        var links=report.links().stream().filter(l->indices.contains(l.skillIndex())).toList();
        var selected=links.stream().filter(l->trace.experienceKinds().contains(l.period().kind())).map(SkillPeriodService.Link::period).toList();
        var coverage=PeriodCalculator.calculate(selected,trace.referenceDate());
        // A linked but unclassified period cannot silently count as professional or be treated as absent.
        if(links.stream().anyMatch(l->l.period().kind()==Kind.UNKNOWN)) coverage=new PeriodCalculator.Result(coverage.minimumMonths(),coverage.maximumMonths(),coverage.recencyMinimumMonths(),coverage.recencyMaximumMonths(),true,coverage.certainUnion(),coverage.possibleUnion());
        Status duration=StructuredMatcher.duration(c.minimumMonths(),coverage),recency=StructuredMatcher.recency(c.maximumRecencyMonths(),coverage);
        var evidence=matches.stream().map(i->new Evidence(i.sourceIndex(),i.value(),state.source().proposal().claims().get(i.sourceIndex()).raw(),i.taxonomy()==null?null:i.taxonomy().dictionaryVersion(),state.source().proposal().claims().get(i.sourceIndex()).evidence())).toList();
        return new CriterionResult(c,presence,duration,recency,StructuredMatcher.combine(List.of(presence,duration,recency)),coverage,evidence,links);
    }
    private static void validate(Query q) {
        if(q==null||q.poolId()==null||q.criteria()==null||q.criteria().isEmpty()||q.criteria().size()>10||q.limit()<1||q.limit()>25||q.experienceKinds()==null||q.experienceKinds().isEmpty()||q.experienceKinds().size()>4||q.experienceKinds().stream().anyMatch(k->k==null||k==Kind.UNKNOWN)||new HashSet<>(q.experienceKinds()).size()!=q.experienceKinds().size()) invalid();
        if(q.referenceDate()!=null&&(q.referenceDate().isAfter(LocalDate.now(ZoneOffset.UTC))||q.referenceDate().isBefore(LocalDate.of(1900,1,1)))) invalid();
        if(q.dictionaryVersion()!=null&&q.dictionaryVersion()<0) invalid();
        for(var c:q.criteria()) if(c==null||c.importance()==null||c.skill()==null||c.skill().isBlank()||c.skill().length()>200||c.skill().contains("@")||c.skill().matches(".*[0-9]{5,}.*")||c.skill().codePoints().anyMatch(v->Character.isISOControl(v)||Character.getType(v)==Character.FORMAT)||c.minimumMonths()!=null&&(c.minimumMonths()<1||c.minimumMonths()>1200)||c.maximumRecencyMonths()!=null&&(c.maximumRecencyMonths()<0||c.maximumRecencyMonths()>1200)) invalid();
    }
    private String hash(Object value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException("SEARCH_FINGERPRINT");}}
    private static String encode(String fingerprint,UUID last) {return Base64.getUrlEncoder().withoutPadding().encodeToString((fingerprint+":"+last).getBytes(StandardCharsets.UTF_8));}
    private static UUID cursor(String cursor,String fingerprint) {
        if(cursor==null) return null;
        try {if(cursor.length()>150) invalid();var parts=new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8).split(":",-1);
            if(parts.length!=2||!parts[0].equals(fingerprint)) throw new Rejected(HttpStatus.BAD_REQUEST,"SEARCH_CURSOR_CHANGED");
            return UUID.fromString(parts[1]);
        } catch(IllegalArgumentException e) {throw new Rejected(HttpStatus.BAD_REQUEST,"SEARCH_CURSOR_CHANGED");}
    }
    private static void invalid() {throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_STRUCTURED_SEARCH");}
}
