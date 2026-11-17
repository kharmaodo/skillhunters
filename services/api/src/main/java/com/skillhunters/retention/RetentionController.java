package com.skillhunters.retention;
import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/imports/{source}/retention")
public class RetentionController {
    private final RetentionService service;private final CurrentAccount accounts;
    public RetentionController(RetentionService service,CurrentAccount accounts){this.service=service;this.accounts=accounts;}
    public record Erase(boolean confirmed) {}
    @GetMapping ResponseEntity<?> get(Authentication a,@PathVariable UUID source){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.get(accounts.require(a).id(),source));}
    @PutMapping("/hold") ResponseEntity<?> hold(Authentication a,@PathVariable UUID source,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody RetentionService.Hold body){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.hold(accounts.require(a).id(),source,etag,body));}
    @PostMapping("/erase") ResponseEntity<?> erase(Authentication a,@PathVariable UUID source,@RequestBody Erase body){return ResponseEntity.accepted().header("Cache-Control","no-store").body(service.request(accounts.require(a).id(),source,body.confirmed()));}
}
