package com.skillhunters.communication;

import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/candidates/{candidate}/contact-deliveries")
public class ContactDeliveryController {
    private final ContactDelivery service;private final CurrentAccount accounts;
    public ContactDeliveryController(ContactDelivery service,CurrentAccount accounts){this.service=service;this.accounts=accounts;}
    @PostMapping
    ResponseEntity<ContactDelivery.Receipt> approve(Authentication auth,@PathVariable UUID candidate,@RequestBody ContactDelivery.Approval input){return response(service.approve(accounts.require(auth).id(),candidate,input));}
    @GetMapping("/{id}")
    ResponseEntity<ContactDelivery.Receipt> get(Authentication auth,@PathVariable UUID candidate,@PathVariable UUID id){return response(service.get(accounts.require(auth).id(),candidate,id));}
    @PostMapping("/{id}/send")
    ResponseEntity<ContactDelivery.Receipt> send(Authentication auth,@PathVariable UUID candidate,@PathVariable UUID id){return response(service.send(accounts.require(auth).id(),candidate,id));}
    private static <T> ResponseEntity<T> response(T value){return ResponseEntity.ok().header("Cache-Control","no-store").body(value);}
}
