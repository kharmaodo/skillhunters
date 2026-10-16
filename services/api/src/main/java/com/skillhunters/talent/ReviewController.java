package com.skillhunters.talent;

import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/candidates/{id}")
public class ReviewController {
    private final ReviewService reviews;
    private final CurrentAccount accounts;
    public ReviewController(ReviewService reviews,CurrentAccount accounts) {this.reviews=reviews;this.accounts=accounts;}
    @GetMapping("/review")
    ResponseEntity<ReviewService.State> get(Authentication auth,@PathVariable UUID id) {
        var state=reviews.get(accounts.require(auth).id(),id);
        return ResponseEntity.ok().eTag("\""+state.version()+"\"").header("Cache-Control","no-store").body(state);
    }
    @PostMapping("/revisions")
    ResponseEntity<ReviewService.Revision> draft(Authentication auth,@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody ReviewService.Request request) {
        return response(reviews.save(accounts.require(auth).id(),id,etag,request,false));
    }
    @PostMapping("/validate")
    ResponseEntity<ReviewService.Revision> publish(Authentication auth,@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody ReviewService.Request request) {
        return response(reviews.save(accounts.require(auth).id(),id,etag,request,true));
    }
    @GetMapping("/revisions")
    CandidateService.Page<ReviewService.Summary> history(Authentication auth,@PathVariable UUID id,@RequestParam(defaultValue="0") int cursor,@RequestParam(defaultValue="25") int limit) {
        return reviews.history(accounts.require(auth).id(),id,cursor,limit);
    }
    @GetMapping("/revisions/{revisionId}")
    ReviewService.Revision revision(Authentication auth,@PathVariable UUID id,@PathVariable UUID revisionId) {
        return reviews.revision(accounts.require(auth).id(),id,revisionId);
    }
    @GetMapping("/publication")
    ReviewService.Publication publication(Authentication auth,@PathVariable UUID id) {
        return reviews.publication(accounts.require(auth).id(),id);
    }
    @GetMapping("/revisions/{revisionId}/skills")
    java.util.List<com.skillhunters.taxonomy.TaxonomyService.Match> skills(Authentication auth,@PathVariable UUID id,@PathVariable UUID revisionId) {
        return reviews.normalizations(accounts.require(auth).id(),id,revisionId);
    }
    private ResponseEntity<ReviewService.Revision> response(ReviewService.Revision r) {
        return ResponseEntity.ok().eTag("\""+r.version()+"\"").header("Cache-Control","no-store").body(r);
    }
}
