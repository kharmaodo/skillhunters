package com.skillhunters.search;

import com.skillhunters.identity.CurrentAccount;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/search/hybrid")
public class HybridSearchController {
    private final HybridSearchService search;private final CurrentAccount accounts;
    public HybridSearchController(HybridSearchService search,CurrentAccount accounts) {this.search=search;this.accounts=accounts;}
    @PostMapping
    ResponseEntity<HybridSearchService.Result> search(Authentication auth,@RequestBody HybridSearchService.Request query) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(search.search(accounts.require(auth).id(),query));
    }
}
