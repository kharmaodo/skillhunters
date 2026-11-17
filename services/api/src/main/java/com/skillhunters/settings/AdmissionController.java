package com.skillhunters.settings;
import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
public class AdmissionController {
    private final ImportSettings service;private final CurrentAccount accounts;
    public AdmissionController(ImportSettings service,CurrentAccount accounts){this.service=service;this.accounts=accounts;}
    @GetMapping("/api/v1/pools/{pool}/import-settings") ResponseEntity<?> get(Authentication a,@PathVariable UUID pool){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.forPool(accounts.require(a).id(),pool));}
}
