package com.skillhunters.retention;
import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/pools/{pool}/erasures")
public class RetentionQueueController {
    private final RetentionService service;private final CurrentAccount accounts;
    public RetentionQueueController(RetentionService service,CurrentAccount accounts){this.service=service;this.accounts=accounts;}
    @GetMapping ResponseEntity<?> list(Authentication a,@PathVariable UUID pool,@RequestParam(required=false) UUID cursor){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.proofs(accounts.require(a).id(),pool,cursor));}
}
