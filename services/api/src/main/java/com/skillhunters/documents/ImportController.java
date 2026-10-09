package com.skillhunters.documents;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.skillhunters.identity.CurrentAccount;
import com.skillhunters.identity.IdentityStore;
import com.skillhunters.shared.ApiProblem.Rejected;

@RestController
@RequestMapping("/api/v1")
public class ImportController {
    private final CurrentAccount accounts;
    private final IdentityStore identity;
    private final ImportService imports;
    private final ReceptionValidator validator;
    private final ImportCapacity capacity;
    public ImportController(CurrentAccount accounts, IdentityStore identity, ImportService imports, ReceptionValidator validator, ImportCapacity capacity) {
        this.capacity = capacity; this.accounts = accounts; this.identity = identity; this.imports = imports; this.validator = validator;
    }

    @PostMapping(value="/imports", consumes="multipart/form-data")
    public ResponseEntity<ImportService.Receipt> create(Authentication authentication,
            @RequestParam UUID poolId, @RequestPart @Valid ImportService.Basis basis,
            @RequestPart("files") List<MultipartFile> files, @RequestHeader("Idempotency-Key") String key) throws IOException {
        UUID actor = accounts.require(authentication).id();
        identity.pool(actor, poolId);
        if (files.size() != 1) throw new Rejected(HttpStatus.BAD_REQUEST, "SINGLE_FILE_REQUIRED");
        capacity.acquire();
        try (var file = validator.receive(files.getFirst())) {
            var receipt = imports.create(actor, poolId, key, basis, file);
            return ResponseEntity.accepted().location(URI.create("/api/v1/imports/" + receipt.id())).body(receipt);
        } finally { capacity.release(); }
    }

    @PutMapping(value="/imports/{id}/content", consumes="multipart/form-data")
    public ImportService.Receipt resume(Authentication auth,@PathVariable UUID id,@RequestPart("file") MultipartFile file) throws IOException {
        UUID actor=accounts.require(auth).id();imports.get(actor,id);capacity.acquire();
        try(var received=validator.receive(file)) { return imports.resume(actor,id,received); }
        finally { capacity.release(); }
    }

    @GetMapping("/imports/{id}")
    public ImportService.Receipt get(Authentication auth, @PathVariable UUID id) { return imports.get(accounts.require(auth).id(), id); }

    @GetMapping("/pools/{poolId}/imports")
    public ImportService.Page list(Authentication auth, @PathVariable UUID poolId, @RequestParam(required=false) String cursor) {
        return imports.list(accounts.require(auth).id(), poolId, cursor);
    }

    @GetMapping("/pools/{poolId}/import-policies")
    public List<ImportService.Policy> policies(Authentication auth, @PathVariable UUID poolId) {
        return imports.policies(accounts.require(auth).id(), poolId);
    }
}
