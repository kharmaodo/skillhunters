package com.skillhunters.communication;

import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/candidates/{candidate}/contact-history")
public class ContactHistoryController {
    private final ContactHistory history;private final CurrentAccount accounts;
    public ContactHistoryController(ContactHistory history,CurrentAccount accounts){this.history=history;this.accounts=accounts;}
    @GetMapping
    ResponseEntity<ContactHistory.Page> list(Authentication auth,@PathVariable UUID candidate,@RequestParam(required=false) String cursor,@RequestParam(required=false) String status){return response(history.list(accounts.require(auth).id(),candidate,cursor,status));}
    @GetMapping("/{id}")
    ResponseEntity<ContactHistory.Detail> detail(Authentication auth,@PathVariable UUID candidate,@PathVariable UUID id){return response(history.detail(accounts.require(auth).id(),candidate,id));}
    private static <T> ResponseEntity<T> response(T value){return ResponseEntity.ok().header("Cache-Control","no-store").body(value);}
}
