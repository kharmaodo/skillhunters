package com.skillhunters.exports;
import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1")
public class CsvExportController {
    private final CsvExports service;private final CurrentAccount accounts;
    public CsvExportController(CsvExports service,CurrentAccount accounts){this.service=service;this.accounts=accounts;}
    @GetMapping("/pools/{pool}/csv-export-options") ResponseEntity<?> options(Authentication a,@PathVariable UUID pool){return ResponseEntity.ok().header("Cache-Control","no-store").body(service.options(accounts.require(a).id(),pool));}
    @PostMapping("/csv-exports") ResponseEntity<?> create(Authentication a,@RequestBody CsvExports.Request body){return ResponseEntity.status(201).header("Cache-Control","no-store").body(service.create(accounts.require(a).id(),body));}
    @GetMapping("/csv-exports/{id}/content") ResponseEntity<byte[]> download(Authentication a,@PathVariable UUID id){var file=service.download(accounts.require(a).id(),id);return ResponseEntity.ok().header("Cache-Control","no-store").header("Referrer-Policy","no-referrer").header("X-Content-Type-Options","nosniff").header("Content-Type","text/csv; charset=UTF-8").header("Content-Disposition","attachment; filename="+file.filename()).body(file.content());}
}
