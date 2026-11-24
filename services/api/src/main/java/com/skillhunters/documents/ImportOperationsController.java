package com.skillhunters.documents;
import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/admin/pools/{pool}/import-operations")
public class ImportOperationsController {
    private final CurrentAccount accounts;private final ImportOperations service;
    public ImportOperationsController(CurrentAccount accounts,ImportOperations service){this.accounts=accounts;this.service=service;}
    @GetMapping ResponseEntity<?> metrics(Authentication a,@PathVariable UUID pool){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.metrics(accounts.require(a).id(),pool));}
    @GetMapping("/jobs") ResponseEntity<?> jobs(Authentication a,@PathVariable UUID pool,@RequestParam(defaultValue="FAILED") ImportOperations.Queue state,@RequestParam(required=false) UUID after){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.queue(accounts.require(a).id(),pool,state,after));}
    @PostMapping("/jobs/{id}/retry") ResponseEntity<?> retry(Authentication a,@PathVariable UUID pool,@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody ImportOperations.RetryRequest body){var result=service.retry(accounts.require(a).id(),pool,id,etag,body);return ResponseEntity.accepted().header("Cache-Control","no-store").eTag("\""+result.version()+"\"").body(result);}
}
