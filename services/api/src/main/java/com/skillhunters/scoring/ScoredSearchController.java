package com.skillhunters.scoring;

import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/search/scored")
public class ScoredSearchController {
    private final ScoredSearchService service;private final CurrentAccount accounts;
    public ScoredSearchController(ScoredSearchService service,CurrentAccount accounts) {this.service=service;this.accounts=accounts;}
    @PostMapping
    ResponseEntity<ScoredSearchService.Page> create(Authentication auth,@RequestBody ScoredSearchService.Request request) {
        return ResponseEntity.status(201).header("Cache-Control","no-store").body(service.create(accounts.require(auth).id(),request));
    }
    @GetMapping("/{id}")
    ResponseEntity<ScoredSearchService.Page> page(Authentication auth,@PathVariable UUID id,@RequestParam(defaultValue="0") int cursor,@RequestParam(defaultValue="20") int limit) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.page(accounts.require(auth).id(),id,cursor,limit));
    }
    @PostMapping("/{id}/candidates/{candidateId}/recalculate")
    ResponseEntity<ScoredSearchService.Item> replay(Authentication auth,@PathVariable UUID id,@PathVariable UUID candidateId) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(service.replay(accounts.require(auth).id(),id,candidateId));
    }
}
