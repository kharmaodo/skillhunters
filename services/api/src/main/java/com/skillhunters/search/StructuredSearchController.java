package com.skillhunters.search;

import com.skillhunters.identity.CurrentAccount;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/search/structured")
public class StructuredSearchController {
    private final StructuredSearchService search;private final CurrentAccount accounts;
    public StructuredSearchController(StructuredSearchService search,CurrentAccount accounts) {this.search=search;this.accounts=accounts;}
    @PostMapping
    ResponseEntity<StructuredSearchService.Result> search(Authentication auth,@RequestBody StructuredSearchService.Query query) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(search.search(accounts.require(auth).id(),query));
    }
}
