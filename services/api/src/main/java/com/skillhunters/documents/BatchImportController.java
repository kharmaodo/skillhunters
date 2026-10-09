package com.skillhunters.documents;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.skillhunters.identity.CurrentAccount;

@RestController
@RequestMapping("/api/v1")
public class BatchImportController {
    private final CurrentAccount accounts;
    private final BatchImportService batches;
    private final ImportCapacity capacity;
    public BatchImportController(CurrentAccount accounts,BatchImportService batches,ImportCapacity capacity) {
        this.accounts=accounts;this.batches=batches;this.capacity=capacity;
    }
    @PostMapping("/import-batches")
    public ResponseEntity<BatchImportService.Batch> create(Authentication auth,@RequestHeader("Idempotency-Key") String key,
            @RequestBody @Valid BatchImportService.Request request) {
        var batch=batches.create(accounts.require(auth).id(),key,request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/import-batches/"+batch.id())).body(batch);
    }
    @GetMapping("/import-batches/{id}")
    public BatchImportService.Batch get(Authentication auth,@PathVariable UUID id) { return batches.get(accounts.require(auth).id(),id); }
    @GetMapping("/pools/{poolId}/import-batches")
    public BatchImportService.Page list(Authentication auth,@PathVariable UUID poolId,@RequestParam(required=false) String cursor) {
        return batches.list(accounts.require(auth).id(),poolId,cursor);
    }
    @PutMapping(value="/import-batches/{batchId}/items/{itemId}/content",consumes="multipart/form-data")
    public BatchImportService.Batch upload(Authentication auth,@PathVariable UUID batchId,@PathVariable UUID itemId,
            @RequestPart("file") MultipartFile file) throws IOException {
        var actor=accounts.require(auth).id();capacity.acquire();
        try { return batches.upload(actor,batchId,itemId,file); }
        finally { capacity.release(); }
    }
}
