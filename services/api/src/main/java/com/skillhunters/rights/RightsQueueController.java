package com.skillhunters.rights;
import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
public class RightsQueueController {
    private final CandidateRights service;private final CurrentAccount accounts;
    public RightsQueueController(CandidateRights service,CurrentAccount accounts){this.service=service;this.accounts=accounts;}
    @GetMapping("/api/v1/pools/{pool}/rights-requests") ResponseEntity<?> queue(Authentication a,@PathVariable UUID pool,@RequestParam(required=false) UUID cursor,@RequestParam(defaultValue="false") boolean overdue){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.queue(accounts.require(a).id(),pool,cursor,overdue));}
}
