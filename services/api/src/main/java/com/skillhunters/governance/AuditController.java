package com.skillhunters.governance;

import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/audit-events")
public class AuditController {
    private final AuditTrail audit;private final CurrentAccount accounts;
    public AuditController(AuditTrail audit,CurrentAccount accounts){this.audit=audit;this.accounts=accounts;}
    @GetMapping
    ResponseEntity<AuditTrail.Page> list(Authentication auth,@RequestParam(required=false) UUID poolId,@RequestParam(required=false) Long before,@RequestParam(required=false) String action) {
        return ResponseEntity.ok().header("Cache-Control","no-store").body(audit.list(accounts.require(auth).id(),poolId,before,action));
    }
}
