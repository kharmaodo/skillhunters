package com.skillhunters.documents;

import java.util.UUID;
import com.skillhunters.identity.CurrentAccount;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/imports/{id}/extraction")
public class ExtractionController {
    private final ExtractionService extraction;
    private final CurrentAccount accounts;
    public ExtractionController(ExtractionService extraction, CurrentAccount accounts) {
        this.extraction = extraction; this.accounts = accounts;
    }
    @GetMapping
    public ExtractionService.Revision get(Authentication auth, @PathVariable UUID id) {
        return extraction.get(accounts.require(auth).id(), id);
    }
    @PostMapping
    public ExtractionService.Revision prepare(Authentication auth, @PathVariable UUID id) {
        return extraction.prepare(accounts.require(auth).id(), id);
    }
}
