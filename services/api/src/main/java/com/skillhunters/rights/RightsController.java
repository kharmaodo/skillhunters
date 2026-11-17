package com.skillhunters.rights;
import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1/candidates/{candidate}/rights-requests")
public class RightsController {
    private final CandidateRights service;private final CurrentAccount accounts;
    public RightsController(CandidateRights service,CurrentAccount accounts){this.service=service;this.accounts=accounts;}
    private UUID actor(Authentication a){return accounts.require(a).id();}
    private ResponseEntity<?> ok(Object result){return ResponseEntity.ok().header("Cache-Control","no-store").body(result);}
    @GetMapping ResponseEntity<?> list(Authentication a,@PathVariable UUID candidate,@RequestParam(required=false) UUID cursor){return ok(service.list(actor(a),candidate,cursor));}
    @PostMapping ResponseEntity<?> create(Authentication a,@PathVariable UUID candidate,@RequestBody CandidateRights.Intake body){return ResponseEntity.status(201).header("Cache-Control","no-store").body(service.create(actor(a),candidate,body));}
    @GetMapping("/{request}/events") ResponseEntity<?> history(Authentication a,@PathVariable UUID candidate,@PathVariable UUID request,@RequestParam(required=false) Long cursor){return ok(service.history(actor(a),candidate,request,cursor));}
    @PutMapping("/{request}/verification") ResponseEntity<?> verify(Authentication a,@PathVariable UUID candidate,@PathVariable UUID request,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody CandidateRights.Verification body){return ok(service.verify(actor(a),candidate,request,etag,body));}
    @PutMapping("/{request}/deadline") ResponseEntity<?> deadline(Authentication a,@PathVariable UUID candidate,@PathVariable UUID request,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody CandidateRights.Deadline body){return ok(service.deadline(actor(a),candidate,request,etag,body));}
    @PostMapping("/{request}/access-package") ResponseEntity<?> prepare(Authentication a,@PathVariable UUID candidate,@PathVariable UUID request,@RequestHeader(value="If-Match",required=false) String etag){return ok(service.prepare(actor(a),candidate,request,etag));}
    @PostMapping("/{request}/rectification") ResponseEntity<?> correct(Authentication a,@PathVariable UUID candidate,@PathVariable UUID request,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody CandidateRights.Correction body){return ok(service.correct(actor(a),candidate,request,etag,body));}
    @PostMapping("/{request}/appeal-review") ResponseEntity<?> appeal(Authentication a,@PathVariable UUID candidate,@PathVariable UUID request,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody CandidateRights.Decision body){return ok(service.appeal(actor(a),candidate,request,etag,body));}
    @PostMapping("/{request}/closure") ResponseEntity<?> close(Authentication a,@PathVariable UUID candidate,@PathVariable UUID request,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody CandidateRights.Closure body){return ok(service.close(actor(a),candidate,request,etag,body));}
}
