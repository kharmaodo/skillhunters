package com.skillhunters.talent;
import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/candidates/{candidate}/lineage")
public class CandidateLineageController {
    private final CandidateLineage lineage;private final CurrentAccount accounts;
    public CandidateLineageController(CandidateLineage lineage,CurrentAccount accounts){this.lineage=lineage;this.accounts=accounts;}
    public record Confirmation(boolean confirmed) {}
    @GetMapping ResponseEntity<?> state(Authentication a,@PathVariable UUID candidate){return response(lineage.state(accounts.require(a).id(),candidate));}
    @GetMapping("/suggestions") ResponseEntity<?> suggestions(Authentication a,@PathVariable UUID candidate,@RequestParam(required=false) String cursor){return response(lineage.suggestions(accounts.require(a).id(),candidate,cursor));}
    @GetMapping("/history") ResponseEntity<?> history(Authentication a,@PathVariable UUID candidate,@RequestParam(required=false) Long cursor){return response(lineage.history(accounts.require(a).id(),candidate,cursor));}
    @PostMapping("/verify-email") ResponseEntity<?> verify(Authentication a,@PathVariable UUID candidate,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody Confirmation body){return response(lineage.verify(accounts.require(a).id(),candidate,etag,body.confirmed()));}
    @PostMapping("/merge") ResponseEntity<?> merge(Authentication a,@PathVariable UUID candidate,@RequestBody CandidateLineage.Merge body){return response(lineage.merge(accounts.require(a).id(),candidate,body));}
    @PostMapping("/active") ResponseEntity<?> active(Authentication a,@PathVariable UUID candidate,@RequestBody CandidateLineage.Activation body){return response(lineage.activate(accounts.require(a).id(),candidate,body));}
    private ResponseEntity<?> response(Object value){return ResponseEntity.ok().header("Cache-Control","no-store").body(value);}
}
