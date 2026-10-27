package com.skillhunters.scoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ScoreReviews {
    public enum Action { CONTEST, SELECT, WITHDRAW, CORRECTION }
    public record Edit(Action action,String reason,Integer criterionIndex) {}
    public record Event(int version,Action action,String reason,Integer criterionIndex,UUID actorId,OffsetDateTime createdAt,
        UUID replacementSearchId,UUID publicationId,EvidenceScore.Score score,String fingerprint) {}
    public record History(int version,List<Event> items,Integer nextCursor) {}
    private final JdbcClient db;private final TransactionTemplate tx;private final ObjectMapper json;private final ScoredSearchService scores;
    public ScoreReviews(JdbcClient db,TransactionTemplate tx,ObjectMapper json,ScoredSearchService scores) {this.db=db;this.tx=tx;this.json=json;this.scores=scores;}
    public History history(UUID actor,UUID search,UUID candidate,int cursor) {
        if(cursor<0||cursor>100) invalid();
        return tx.execute(t->{scores.replay(actor,search,candidate);int version=version(search,candidate);
            var rows=db.sql("SELECT event_json FROM score_review_event WHERE search_id=:s AND candidate_id=:c AND version>:v ORDER BY version LIMIT 26")
                .param("s",search).param("c",candidate).param("v",cursor).query(String.class).list();
            var items=rows.stream().limit(25).map(this::read).toList();
            return new History(version,items,rows.size()>25?items.getLast().version():null);
        });
    }
    public Event save(UUID actor,UUID search,UUID candidate,String etag,Edit edit) {
        return tx.execute(t->{
            // Replay locks the actor and verifies ownership, membership, retention and fingerprint.
            var before=scores.replay(actor,search,candidate);int version=version(search,candidate);
            if(etag==null) throw new Rejected(HttpStatus.PRECONDITION_REQUIRED,"VERSION_REQUIRED");
            if(!etag.equals("\""+version+"\"")) throw new Rejected(HttpStatus.PRECONDITION_FAILED,"SCORE_REVIEW_CHANGED");
            if(version>=100) throw new Rejected(HttpStatus.TOO_MANY_REQUESTS,"SCORE_REVIEW_QUOTA");
            if(edit==null||edit.action()==null||edit.reason()==null||edit.reason().isBlank()||edit.reason().length()>500||edit.reason().codePoints().anyMatch(c->Character.isISOControl(c)||Character.getType(c)==Character.FORMAT)) invalid();
            if(edit.action()==Action.CONTEST) {if(edit.criterionIndex()==null||edit.criterionIndex()<0||edit.criterionIndex()>=before.hit().criteria().size()) invalid();}
            else if(edit.criterionIndex()!=null) invalid();
            UUID replacement=null;var after=before;
            if(edit.action()==Action.CORRECTION) {
                if(db.sql("SELECT event_json FROM score_review_event WHERE search_id=:s AND candidate_id=:c").param("s",search).param("c",candidate).query(String.class).list().stream().map(this::read).noneMatch(e->e.action()==Action.CONTEST))
                    throw new Rejected(HttpStatus.CONFLICT,"SCORE_CONTEST_REQUIRED");
                var next=scores.refresh(actor,search);replacement=next.metadata().searchId();
                try {after=scores.replay(actor,replacement,candidate);}catch(Rejected e) {if(e.status==HttpStatus.NOT_FOUND) throw new Rejected(HttpStatus.CONFLICT,"SCORE_CORRECTION_NOT_RETRIEVED");throw e;}
                if(after.hit().publicationId().equals(before.hit().publicationId())&&Objects.equals(after.hit().periodLinksVersion(),before.hit().periodLinksVersion()))
                    throw new Rejected(HttpStatus.CONFLICT,"SCORE_CORRECTION_REQUIRED");
            }
            var event=new Event(version+1,edit.action(),edit.reason().strip(),edit.criterionIndex(),actor,OffsetDateTime.now(ZoneOffset.UTC),replacement,after.hit().publicationId(),after.score(),after.fingerprint());
            db.sql("INSERT INTO score_review_event(search_id,candidate_id,version,replacement_search_id,event_json) VALUES (:s,:c,:v,:r,:j)")
                .param("s",search).param("c",candidate).param("v",event.version()).param("r",replacement,java.sql.Types.OTHER).param("j",write(event)).update();
            return event;
        });
    }
    private int version(UUID s,UUID c) {return db.sql("SELECT COALESCE(MAX(version),0) FROM score_review_event WHERE search_id=:s AND candidate_id=:c").param("s",s).param("c",c).query(Integer.class).single();}
    private static void invalid(){throw new Rejected(HttpStatus.BAD_REQUEST,"INVALID_SCORE_REVIEW");}
    private Event read(String s){try{return json.readValue(s,Event.class);}catch(Exception e){throw new IllegalStateException("SCORE_REVIEW_STORAGE");}}
    private String write(Event e){try{return json.writeValueAsString(e);}catch(Exception x){throw new IllegalStateException("SCORE_REVIEW_SERIALIZATION");}}
}
