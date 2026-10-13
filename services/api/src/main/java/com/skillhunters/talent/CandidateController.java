package com.skillhunters.talent;

import com.fasterxml.jackson.databind.JsonNode;
import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class CandidateController {
    private final CandidateService candidates;
    private final CurrentAccount accounts;
    public CandidateController(CandidateService candidates,CurrentAccount accounts) {this.candidates=candidates;this.accounts=accounts;}
    public record Create(UUID revisionId) {}
    @PostMapping("/imports/{id}/candidate")
    ResponseEntity<CandidateService.Candidate> create(Authentication auth,@PathVariable UUID id,@RequestBody Create request) {
        return response(candidates.create(accounts.require(auth).id(),id,request.revisionId()));
    }
    @GetMapping("/candidates/{id}")
    ResponseEntity<CandidateService.Candidate> get(Authentication auth,@PathVariable UUID id) {
        return response(candidates.get(accounts.require(auth).id(),id));
    }
    @PatchMapping("/candidates/{id}")
    ResponseEntity<CandidateService.Candidate> update(Authentication auth,@PathVariable UUID id,
            @RequestHeader(value="If-Match",required=false) String etag,@RequestBody JsonNode patch) {
        return response(candidates.update(accounts.require(auth).id(),id,etag,patch));
    }
    @GetMapping("/candidates")
    CandidateService.Page<CandidateService.Candidate> list(Authentication auth,@RequestParam UUID poolId,
            @RequestParam(defaultValue="0") int cursor,@RequestParam(defaultValue="25") int limit) {
        return candidates.list(accounts.require(auth).id(),poolId,cursor,limit);
    }
    @GetMapping("/candidates/{id}/history")
    CandidateService.Page<CandidateService.Edit> history(Authentication auth,@PathVariable UUID id,
            @RequestParam(defaultValue="0") int cursor,@RequestParam(defaultValue="25") int limit) {
        return candidates.history(accounts.require(auth).id(),id,cursor,limit);
    }
    private ResponseEntity<CandidateService.Candidate> response(CandidateService.Candidate c) {
        return ResponseEntity.ok().eTag("\""+c.version()+"\"").header("Cache-Control","no-store").body(c);
    }
}
