package com.skillhunters.taxonomy;

import com.skillhunters.identity.CurrentAccount;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/taxonomy")
public class TaxonomyController {
    private final TaxonomyService service;
    private final CurrentAccount accounts;
    public TaxonomyController(TaxonomyService service,CurrentAccount accounts) {this.service=service;this.accounts=accounts;}
    @GetMapping
    ResponseEntity<TaxonomyService.Dictionary> get(Authentication auth,@RequestParam(required=false) Long version) {
        accounts.require(auth);return response(service.dictionary(version));
    }
    @GetMapping("/history")
    List<TaxonomyService.Event> history(Authentication auth,@RequestParam(defaultValue="0") int cursor) {
        accounts.admin(auth);return service.history(cursor);
    }
    @PostMapping("/skills")
    ResponseEntity<TaxonomyService.Dictionary> create(Authentication auth,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody TaxonomyService.Edit request) {
        return response(service.edit(accounts.admin(auth).id(),null,etag,request));
    }
    @PutMapping("/skills/{id}")
    ResponseEntity<TaxonomyService.Dictionary> edit(Authentication auth,@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody TaxonomyService.Edit request) {
        return response(service.edit(accounts.admin(auth).id(),id,etag,request));
    }
    @PostMapping("/skills/{id}/merge")
    ResponseEntity<TaxonomyService.Dictionary> merge(Authentication auth,@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody TaxonomyService.Merge request) {
        return response(service.merge(accounts.admin(auth).id(),id,etag,request));
    }
    private ResponseEntity<TaxonomyService.Dictionary> response(TaxonomyService.Dictionary d) {
        return ResponseEntity.ok().eTag("\""+d.version()+"\"").header("Cache-Control","no-store").body(d);
    }
}
