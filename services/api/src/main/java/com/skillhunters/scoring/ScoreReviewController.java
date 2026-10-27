package com.skillhunters.scoring;
import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/search/scored/{search}/candidates/{candidate}/reviews")
public class ScoreReviewController {
    private final ScoreReviews reviews;private final CurrentAccount accounts;
    public ScoreReviewController(ScoreReviews reviews,CurrentAccount accounts){this.reviews=reviews;this.accounts=accounts;}
    @GetMapping
    ResponseEntity<ScoreReviews.History> history(Authentication auth,@PathVariable UUID search,@PathVariable UUID candidate,@RequestParam(defaultValue="0") int cursor){
        var h=reviews.history(accounts.require(auth).id(),search,candidate,cursor);
        return ResponseEntity.ok().header("Cache-Control","no-store").eTag("\""+h.version()+"\"").body(h);
    }
    @PostMapping
    ResponseEntity<ScoreReviews.Event> save(Authentication auth,@PathVariable UUID search,@PathVariable UUID candidate,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody ScoreReviews.Edit edit){
        var e=reviews.save(accounts.require(auth).id(),search,candidate,etag,edit);
        return ResponseEntity.status(201).header("Cache-Control","no-store").eTag("\""+e.version()+"\"").body(e);
    }
}
