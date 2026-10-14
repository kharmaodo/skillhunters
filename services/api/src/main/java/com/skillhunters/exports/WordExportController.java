package com.skillhunters.exports;

import com.skillhunters.identity.CurrentAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class WordExportController {
    public static final String DOCX="application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private final WordExportService exports;private final ExportPermissions permissions;private final CurrentAccount accounts;
    public WordExportController(WordExportService exports,ExportPermissions permissions,CurrentAccount accounts) {this.exports=exports;this.permissions=permissions;this.accounts=accounts;}
    public record Request(@NotNull UUID revisionId) {}
    public record Grant(@NotNull UUID userId,@NotNull UUID poolId,@NotNull Boolean allowed) {}
    public record Permission(boolean allowed) {}
    @GetMapping("/candidates/{id}/export-options")
    WordExportService.Options options(Authentication auth,@PathVariable UUID id) {return exports.options(accounts.require(auth).id(),id);}
    @PostMapping("/candidates/{id}/exports")
    WordExportService.Receipt create(Authentication auth,@PathVariable UUID id,@Valid @RequestBody Request request) {return exports.create(accounts.require(auth).id(),id,request.revisionId());}
    @GetMapping("/exports/{id}/download")
    ResponseEntity<byte[]> download(Authentication auth,@PathVariable UUID id) {
        var file=exports.download(accounts.require(auth).id(),id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(DOCX)).contentLength(file.content().length)
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(file.filename()).build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"no-store").body(file.content());
    }
    @GetMapping("/admin/export-permissions")
    Permission permission(Authentication auth,@RequestParam UUID userId,@RequestParam UUID poolId) {accounts.admin(auth);return new Permission(permissions.allowed(userId,poolId));}
    @PutMapping("/admin/export-permissions")
    Permission permission(Authentication auth,@Valid @RequestBody Grant grant) {
        var actor=accounts.admin(auth);permissions.set(actor.id(),grant.userId(),grant.poolId(),grant.allowed());return new Permission(permissions.allowed(grant.userId(),grant.poolId()));
    }
}
