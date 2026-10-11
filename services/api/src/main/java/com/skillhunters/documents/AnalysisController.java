package com.skillhunters.documents;

import java.util.UUID;
import com.skillhunters.identity.CurrentAccount;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/imports/{id}/analysis")
public class AnalysisController {
    private final AnalysisService analysis;
    private final CurrentAccount accounts;
    public AnalysisController(AnalysisService analysis,CurrentAccount accounts) { this.analysis=analysis; this.accounts=accounts; }
    @GetMapping
    public AnalysisService.Status get(Authentication auth,@PathVariable UUID id) { return analysis.get(accounts.require(auth).id(),id); }
    @PostMapping("/retry")
    public AnalysisService.Status retry(Authentication auth,@PathVariable UUID id) { return analysis.retry(accounts.require(auth).id(),id); }
}
