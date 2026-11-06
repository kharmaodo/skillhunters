package com.skillhunters.communication;

import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/candidates/{id}/contact-draft")
public class ContactDraftController {
    private final ContactDrafts service;private final CurrentAccount accounts;
    public ContactDraftController(ContactDrafts service,CurrentAccount accounts){this.service=service;this.accounts=accounts;}
    @GetMapping("/context")
    ResponseEntity<ContactDrafts.Context> context(Authentication auth,@PathVariable UUID id){return response(service.context(accounts.require(auth).id(),id));}
    @PostMapping("/preview")
    ResponseEntity<ContactDrafts.Draft> preview(Authentication auth,@PathVariable UUID id,@RequestBody ContactDrafts.Input input){return response(service.preview(accounts.require(auth).id(),id,input));}
    @PutMapping
    ResponseEntity<ContactDrafts.Draft> save(Authentication auth,@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody ContactDrafts.Input input){
        var draft=service.save(accounts.require(auth).id(),id,etag,input);
        return ResponseEntity.ok().header("Cache-Control","no-store").eTag("\""+draft.version()+"\"").body(draft);
    }
    @GetMapping
    ResponseEntity<ContactDrafts.History> history(Authentication auth,@PathVariable UUID id,@RequestParam(defaultValue="0") int cursor){return response(service.history(accounts.require(auth).id(),id,cursor));}
    private <T> ResponseEntity<T> response(T value){return ResponseEntity.ok().header("Cache-Control","no-store").body(value);}
}
