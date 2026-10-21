package com.skillhunters.search;

import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/search/natural")
public class NaturalSearchController {
    private final NaturalSearchService service;private final CurrentAccount accounts;
    public NaturalSearchController(NaturalSearchService service,CurrentAccount accounts) {this.service=service;this.accounts=accounts;}
    @PostMapping("/proposals")
    ResponseEntity<NaturalSearchService.Proposal> propose(Authentication auth,@RequestBody NaturalSearchService.Request request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.propose(accounts.require(auth).id(),request));
    }
    @PostMapping("/proposals/{id}/confirm")
    ResponseEntity<StructuredSearchService.Result> confirm(Authentication auth,@PathVariable UUID id,@RequestBody NaturalSearchService.Confirmation request) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.confirm(accounts.require(auth).id(),id,request));
    }
}
