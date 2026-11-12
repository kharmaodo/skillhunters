package com.skillhunters.governance;

import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
public class GovernanceController {
    private final CandidateGovernance service;private final CurrentAccount accounts;
    public GovernanceController(CandidateGovernance service,CurrentAccount accounts){this.service=service;this.accounts=accounts;}
    @GetMapping("/api/v1/candidates/{id}/governance")
    ResponseEntity<CandidateGovernance.State> get(Authentication auth,@PathVariable UUID id){return response(service.get(accounts.require(auth).id(),id));}
    @PutMapping("/api/v1/candidates/{id}/governance")
    ResponseEntity<CandidateGovernance.State> save(Authentication auth,@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody CandidateGovernance.Edit input){return response(service.save(accounts.require(auth).id(),id,etag,input));}
    @GetMapping("/api/v1/candidates/{id}/governance/history")
    ResponseEntity<CandidateGovernance.History> history(Authentication auth,@PathVariable UUID id,@RequestParam(required=false) Long before){return response(service.history(accounts.require(auth).id(),id,before));}
    @PostMapping("/api/v1/candidates/{id}/governance/opposition")
    ResponseEntity<CandidateGovernance.State> oppose(Authentication auth,@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag){return response(service.oppose(accounts.require(auth).id(),id,etag));}
    @GetMapping("/api/v1/candidates/{id}/contact-eligibility")
    ResponseEntity<CandidateGovernance.Eligibility> eligibility(Authentication auth,@PathVariable UUID id){return response(service.eligibility(accounts.require(auth).id(),id));}
    @PostMapping("/api/v1/candidates/{id}/opposition-links")
    ResponseEntity<CandidateGovernance.Link> issue(Authentication auth,@PathVariable UUID id){return response(service.issue(accounts.require(auth).id(),id));}
    public record Redemption(String token) {}
    @PostMapping("/api/v1/public/contact-opposition")
    ResponseEntity<Void> redeem(@RequestBody Redemption input,jakarta.servlet.http.HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");response.setHeader("Referrer-Policy","no-referrer");
        service.redeem(input.token());return ResponseEntity.noContent().build();
    }
    private static <T> ResponseEntity<T> response(T value){return ResponseEntity.ok().header("Cache-Control","no-store").body(value);}
}
