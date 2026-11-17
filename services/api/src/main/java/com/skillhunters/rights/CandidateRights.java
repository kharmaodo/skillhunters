package com.skillhunters.rights;

import com.skillhunters.governance.AuditTrail;
import com.skillhunters.talent.*;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Email intake is recorded by compliance; no automatic disclosure or outbound transport. */
@Service
public class CandidateRights {
    public record Intake(String kind,OffsetDateTime receivedAt,OffsetDateTime dueAt,String policyReference,String emailReference,String summary) {}
    public record Verification(String method,String reference,boolean confirmed) {}
    public record Decision(String reason,String reference,boolean confirmed) {}
    public record Closure(String outcome,String reason,String responseReference,boolean confirmed) {}
    public record Deadline(OffsetDateTime dueAt,String policyReference,String reason) {}
    public record Correction(long candidateVersion,ReviewService.Request review,boolean confirmed) {}
    public record Case(UUID id,UUID candidateId,long version,String kind,String state,OffsetDateTime receivedAt,OffsetDateTime dueAt,String policyReference,String emailReference,String summary,String verificationMethod,String verificationReference,OffsetDateTime verifiedAt,Long preparedVersion,UUID correctedRevision,boolean appealReviewed,String outcome,String responseReference,OffsetDateTime closedAt,boolean overdue) {}
    public record Event(long sequence,UUID actorId,String action,String reference,String reason,OffsetDateTime occurredAt) {}
    public record Page<T>(List<T> items,Long nextCursor) {}
    public record CorrectionResult(Case request,ReviewService.Revision revision) {}
    public record AccessBundle(Case request,CandidateService.Candidate candidate,com.skillhunters.documents.ExtractionService.Revision source,ReviewService.Revision published,List<String> scope) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final CandidateService candidates;private final CandidateIdentity identities;private final ReviewService reviews;private final AuditTrail audit;
    public CandidateRights(JdbcClient db,TransactionTemplate tx,CandidateService candidates,CandidateIdentity identities,ReviewService reviews,AuditTrail audit){this.db=db;this.tx=tx;this.candidates=candidates;this.identities=identities;this.reviews=reviews;this.audit=audit;}
    private void compliance(UUID actor){
        if(!db.sql("SELECT enabled FROM app_user WHERE id=:a FOR UPDATE").param("a",actor).query(Boolean.class).optional().orElse(false))fail(HttpStatus.FORBIDDEN,"ACCOUNT_DISABLED");
        if(db.sql("SELECT COUNT(*) FROM global_role WHERE user_id=:a AND role='COMPLIANCE'").param("a",actor).query(Integer.class).single()==0)fail(HttpStatus.FORBIDDEN,"COMPLIANCE_REQUIRED");
    }
    private CandidateService.Candidate access(UUID actor,UUID candidate){compliance(actor);return candidates.get(actor,candidate);}
    private void lock(UUID candidate){identities.lock(candidate);db.sql("SELECT id FROM candidate_profile WHERE id=:c FOR UPDATE").param("c",candidate).query(UUID.class).single();}
    private Case read(UUID candidate,UUID id){return db.sql("SELECT * FROM candidate_rights_request WHERE id=:id AND candidate_id=:c").param("id",id).param("c",candidate).query((r,n)->new Case(r.getObject("id",UUID.class),candidate,r.getLong("version"),r.getString("kind"),r.getString("state"),r.getObject("received_at",OffsetDateTime.class),r.getObject("due_at",OffsetDateTime.class),r.getString("policy_reference"),r.getString("email_reference"),r.getString("summary"),r.getString("verification_method"),r.getString("verification_reference"),r.getObject("verified_at",OffsetDateTime.class),r.getObject("prepared_version",Long.class),r.getObject("corrected_revision",UUID.class),r.getBoolean("appeal_reviewed"),r.getString("outcome"),r.getString("response_reference"),r.getObject("closed_at",OffsetDateTime.class),!r.getString("state").equals("CLOSED")&&r.getObject("due_at",OffsetDateTime.class).isBefore(now()))).optional().orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"RIGHTS_REQUEST_NOT_FOUND"));}
    private Case writable(UUID actor,UUID candidate,UUID id,String etag,boolean verified){access(actor,candidate);lock(candidate);var c=read(candidate,id);version(etag,c.version());if(c.state().equals("CLOSED"))fail(HttpStatus.CONFLICT,"RIGHTS_REQUEST_CLOSED");if(verified&&c.verifiedAt()==null)fail(HttpStatus.CONFLICT,"IDENTITY_NOT_VERIFIED");if(verified&&!binding(candidate).equals(storedBinding(id)))fail(HttpStatus.CONFLICT,"IDENTITY_VERIFICATION_CHANGED");return c;}
    private String binding(UUID candidate){
        var contact=db.sql("SELECT email,phone FROM candidate_contact WHERE candidate_id=:c").param("c",candidate).query((r,n)->List.of(Objects.toString(r.getString(1),""),Objects.toString(r.getString(2),""))).single();
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((identities.id(candidate)+"|"+contact).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException("RIGHTS_VERIFICATION_BINDING",e);}
    }
    private String storedBinding(UUID id){return db.sql("SELECT verification_binding FROM candidate_rights_request WHERE id=:id").param("id",id).query(String.class).optional().orElse("");}
    private void event(UUID actor,UUID candidate,UUID id,String action,String reference,String reason){
        db.sql("INSERT INTO candidate_rights_event(request_id,actor_id,action,reference,reason) VALUES (:id,:a,:action,:ref,:reason)").param("id",id).param("a",actor).param("action",action).param("ref",reference).param("reason",reason).update();
        var pool=db.sql("SELECT d.pool_id FROM candidate_profile c JOIN document_import d ON d.id=c.import_id WHERE c.id=:c").param("c",candidate).query(UUID.class).single();audit.record(actor,"RIGHTS_"+action,id,pool,null);
    }
    public Case create(UUID actor,UUID candidate,Intake in){return tx.execute(t->{access(actor,candidate);lock(candidate);
        if(in==null||!Set.of("ACCESS","RECTIFICATION","APPEAL").contains(Objects.toString(in.kind(),""))||in.receivedAt()==null||in.receivedAt().isAfter(now()))invalid();
        text(in.emailReference(),200);text(in.summary(),1000);deadline(in.receivedAt(),in.dueAt(),in.policyReference());
        var existing=db.sql("SELECT id FROM candidate_rights_request WHERE candidate_id=:c AND email_reference=:ref").param("c",candidate).param("ref",in.emailReference().strip()).query(UUID.class).optional();
        if(existing.isPresent())fail(HttpStatus.CONFLICT,"RIGHTS_EMAIL_ALREADY_REGISTERED");
        if(db.sql("SELECT COUNT(*) FROM candidate_rights_request WHERE candidate_id=:c").param("c",candidate).query(Integer.class).single()>=100)fail(HttpStatus.TOO_MANY_REQUESTS,"RIGHTS_REQUEST_LIMIT");
        var id=UUID.randomUUID();db.sql("INSERT INTO candidate_rights_request(id,candidate_id,kind,received_at,due_at,policy_reference,email_reference,summary,created_by) VALUES (:id,:c,:kind,:received,:due,:policy,:ref,:summary,:a)").param("id",id).param("c",candidate).param("kind",in.kind()).param("received",in.receivedAt()).param("due",in.dueAt()).param("policy",in.policyReference().strip()).param("ref",in.emailReference().strip()).param("summary",in.summary().strip()).param("a",actor).update();
        event(actor,candidate,id,"RECEIVED",in.emailReference(),null);return read(candidate,id);
    });}
    public record Cases(List<Case> items,UUID nextCursor) {}
    public Cases list(UUID actor,UUID candidate,UUID cursor){return tx.execute(t->{access(actor,candidate);audit.candidate(actor,candidate,"RIGHTS_LIST_READ");var q=db.sql("SELECT id FROM candidate_rights_request WHERE candidate_id=:c"+(cursor==null?"":" AND id>:cursor")+" ORDER BY id LIMIT 26").param("c",candidate);if(cursor!=null)q=q.param("cursor",cursor);var ids=q.query(UUID.class).list();var items=ids.stream().limit(25).map(id->read(candidate,id)).toList();return new Cases(items,ids.size()>25?items.getLast().id():null);});}
    public Cases queue(UUID actor,UUID pool,UUID cursor,boolean overdue){return tx.execute(t->{compliance(actor);
        if(db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:a AND pool_id=:p").param("a",actor).param("p",pool).query(Integer.class).single()==0)fail(HttpStatus.NOT_FOUND,"POOL_NOT_FOUND");
        var q=db.sql("SELECT r.id,r.candidate_id FROM candidate_rights_request r JOIN candidate_profile c ON c.id=r.candidate_id JOIN document_import d ON d.id=c.import_id WHERE d.pool_id=:p AND d.expires_at>:now AND r.state<>'CLOSED'"+(overdue?" AND r.due_at<:now":"")+(cursor==null?"":" AND r.id>:cursor")+" ORDER BY r.id LIMIT 26").param("p",pool).param("now",now());if(cursor!=null)q=q.param("cursor",cursor);
        var all=q.query((r,n)->read(r.getObject(2,UUID.class),r.getObject(1,UUID.class))).list();audit.record(actor,"RIGHTS_QUEUE_READ",null,pool,null);var items=all.stream().limit(25).toList();return new Cases(items,all.size()>25?items.getLast().id():null);
    });}
    public Page<Event> history(UUID actor,UUID candidate,UUID id,Long cursor){return tx.execute(t->{access(actor,candidate);read(candidate,id);if(cursor!=null&&cursor<1)invalid();var q=db.sql("SELECT * FROM candidate_rights_event WHERE request_id=:id"+(cursor==null?"":" AND sequence<:before")+" ORDER BY sequence DESC LIMIT 26").param("id",id);if(cursor!=null)q=q.param("before",cursor);var all=q.query((r,n)->new Event(r.getLong("sequence"),r.getObject("actor_id",UUID.class),r.getString("action"),r.getString("reference"),r.getString("reason"),r.getObject("occurred_at",OffsetDateTime.class))).list();audit.candidate(actor,candidate,"RIGHTS_HISTORY_READ");var items=all.stream().limit(25).toList();return new Page<>(items,all.size()>25?items.getLast().sequence():null);});}
    public Case verify(UUID actor,UUID candidate,UUID id,String etag,Verification in){return tx.execute(t->{var c=writable(actor,candidate,id,etag,false);
        if(in==null||!in.confirmed()||!Set.of("EXISTING_CHANNEL","CALLBACK","PROPORTIONATE_CHECK").contains(Objects.toString(in.method(),"")))invalid();text(in.reference(),200);
        if(c.verifiedAt()!=null&&binding(candidate).equals(storedBinding(id)))fail(HttpStatus.CONFLICT,"IDENTITY_ALREADY_VERIFIED");
        db.sql("UPDATE candidate_rights_request SET version=version+1,state='VERIFIED',verification_method=:method,verification_reference=:ref,verification_binding=:binding,prepared_version=NULL,verified_at=:now WHERE id=:id").param("method",in.method()).param("binding",binding(candidate)).param("ref",in.reference().strip()).param("now",now()).param("id",id).update();event(actor,candidate,id,"VERIFIED",in.reference(),in.method());return read(candidate,id);
    });}
    public Case deadline(UUID actor,UUID candidate,UUID id,String etag,Deadline in){return tx.execute(t->{var c=writable(actor,candidate,id,etag,false);if(in==null)invalid();deadline(c.receivedAt(),in.dueAt(),in.policyReference());text(in.reason(),1000);
        db.sql("UPDATE candidate_rights_request SET version=version+1,due_at=:due,policy_reference=:policy WHERE id=:id").param("due",in.dueAt()).param("policy",in.policyReference().strip()).param("id",id).update();event(actor,candidate,id,"DEADLINE_CHANGED",in.policyReference(),c.dueAt()+" → "+in.dueAt()+" : "+in.reason());return read(candidate,id);
    });}
    public AccessBundle prepare(UUID actor,UUID candidate,UUID id,String etag){return tx.execute(t->{var c=writable(actor,candidate,id,etag,true);if(!c.kind().equals("ACCESS"))invalid();var profile=candidates.get(actor,candidate);if(!profile.contactsVisible())fail(HttpStatus.FORBIDDEN,"CONTACT_PERMISSION_REQUIRED");var review=reviews.get(actor,candidate);
        db.sql("UPDATE candidate_rights_request SET version=version+1,state='IN_PROGRESS',prepared_version=:v WHERE id=:id").param("v",profile.version()).param("id",id).update();event(actor,candidate,id,"ACCESS_PREPARED",null,null);
        return new AccessBundle(read(candidate,id),profile,review.source(),review.publishedRevisionId()==null?null:reviews.revision(actor,candidate,review.publishedRevisionId()),List.of("Fiche et coordonnées de cette source","Propositions et preuves du document","Dernière révision humaine publiée","Autres sources, historiques et données de tiers à examiner séparément avant réponse"));
    });}
    public CorrectionResult correct(UUID actor,UUID candidate,UUID id,String etag,Correction in){return tx.execute(t->{var c=writable(actor,candidate,id,etag,true);if(!Set.of("RECTIFICATION","APPEAL").contains(c.kind())||in==null||!in.confirmed()||in.review()==null)invalid();
        var before=reviews.get(actor,candidate);if(before.publishedRevisionId()!=null&&in.review().items()!=null&&reviews.revision(actor,candidate,before.publishedRevisionId()).items().equals(in.review().items()))fail(HttpStatus.CONFLICT,"RIGHTS_CORRECTION_REQUIRED");
        var revision=reviews.save(actor,candidate,"\""+in.candidateVersion()+"\"",in.review(),true);
        db.sql("UPDATE candidate_rights_request SET version=version+1,state='IN_PROGRESS',corrected_revision=:revision WHERE id=:id").param("revision",revision.id()).param("id",id).update();event(actor,candidate,id,"RECTIFIED",revision.id().toString(),null);return new CorrectionResult(read(candidate,id),revision);
    });}
    public Case appeal(UUID actor,UUID candidate,UUID id,String etag,Decision in){return tx.execute(t->{var c=writable(actor,candidate,id,etag,true);if(!c.kind().equals("APPEAL")||in==null||!in.confirmed())invalid();text(in.reason(),1000);text(in.reference(),200);
        db.sql("UPDATE candidate_rights_request SET version=version+1,state='IN_PROGRESS',appeal_reviewed=TRUE WHERE id=:id").param("id",id).update();event(actor,candidate,id,"APPEAL_REVIEWED",in.reference(),in.reason());return read(candidate,id);
    });}
    public Case close(UUID actor,UUID candidate,UUID id,String etag,Closure in){return tx.execute(t->{var c=writable(actor,candidate,id,etag,true);if(in==null||!in.confirmed()||!Set.of("FULFILLED","NOT_GRANTED").contains(Objects.toString(in.outcome(),"")))invalid();text(in.reason(),1000);text(in.responseReference(),200);
        if(c.kind().equals("APPEAL")&&!c.appealReviewed())fail(HttpStatus.CONFLICT,"APPEAL_REVIEW_REQUIRED");
        if(in.outcome().equals("FULFILLED")){
            if(c.kind().equals("ACCESS")&&(c.preparedVersion()==null||c.preparedVersion()!=candidates.get(actor,candidate).version()))fail(HttpStatus.CONFLICT,"ACCESS_PACKAGE_CHANGED");
            if(c.kind().equals("RECTIFICATION")&&(c.correctedRevision()==null||!c.correctedRevision().equals(reviews.get(actor,candidate).publishedRevisionId())))fail(HttpStatus.CONFLICT,"RIGHTS_CORRECTION_REQUIRED");
        }
        db.sql("UPDATE candidate_rights_request SET version=version+1,state='CLOSED',outcome=:outcome,response_reference=:ref,closed_at=:now WHERE id=:id").param("outcome",in.outcome()).param("ref",in.responseReference().strip()).param("now",now()).param("id",id).update();event(actor,candidate,id,"CLOSED",in.responseReference(),in.outcome()+" : "+in.reason());return read(candidate,id);
    });}
    private static void deadline(OffsetDateTime received,OffsetDateTime due,String policy){text(policy,200);if(due==null||!due.isAfter(received)||due.isAfter(received.plusDays(365)))invalid();}
    private static void text(String s,int max){if(s==null||s.isBlank()||s.length()>max||s.codePoints().anyMatch(Character::isISOControl))invalid();}
    private static OffsetDateTime now(){return OffsetDateTime.now(ZoneOffset.UTC);}
    private static void version(String tag,long v){if(tag==null)fail(HttpStatus.PRECONDITION_REQUIRED,"VERSION_REQUIRED");if(!tag.equals("\""+v+"\""))fail(HttpStatus.PRECONDITION_FAILED,"RIGHTS_REQUEST_CHANGED");if(v>=500)fail(HttpStatus.TOO_MANY_REQUESTS,"RIGHTS_ACTION_LIMIT");}
    private static void invalid(){fail(HttpStatus.BAD_REQUEST,"INVALID_RIGHTS_REQUEST");}
    private static void fail(HttpStatus status,String code){throw new Rejected(status,code);}
}
