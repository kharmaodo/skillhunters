package com.skillhunters.settings;
import com.skillhunters.identity.CurrentAccount;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/admin/import-settings")
public class SettingsController {
    private final ImportSettings service;private final CurrentAccount accounts;
    public SettingsController(ImportSettings service,CurrentAccount accounts){this.service=service;this.accounts=accounts;}
    @GetMapping ResponseEntity<?> get(Authentication a){var v=service.get(accounts.require(a).id());return ResponseEntity.ok().eTag("\""+v.version()+"\"").header("Cache-Control","no-store").body(v);}
    @PutMapping ResponseEntity<?> publish(Authentication a,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody ImportSettings.Edit body){var v=service.publish(accounts.require(a).id(),etag,body);return ResponseEntity.ok().eTag("\""+v.version()+"\"").header("Cache-Control","no-store").body(v);}
    @GetMapping("/history") ResponseEntity<?> history(Authentication a,@RequestParam(required=false) Long cursor){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.history(accounts.require(a).id(),cursor));}
    @GetMapping("/template") ResponseEntity<byte[]> template(Authentication a){return ResponseEntity.ok().header("Cache-Control","no-store").header("Content-Disposition","attachment; filename=skillhunters-cv-template-v1.docx").header("X-Content-Type-Options","nosniff").header("Content-Type","application/vnd.openxmlformats-officedocument.wordprocessingml.document").body(service.template(accounts.require(a).id()));}
}
