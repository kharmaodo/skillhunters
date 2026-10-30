package com.skillhunters.talent;
import com.skillhunters.identity.CurrentAccount;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1")
public class CandidateTrackingController {
    private final CandidateTracking tracking;private final CurrentAccount accounts;
    public CandidateTrackingController(CandidateTracking tracking,CurrentAccount accounts){this.tracking=tracking;this.accounts=accounts;}
    @GetMapping("/candidates/{id}/tracking")
    ResponseEntity<CandidateTracking.State> get(Authentication auth,@PathVariable UUID id){return state(tracking.get(accounts.require(auth).id(),id));}
    @PutMapping("/candidates/{id}/tracking")
    ResponseEntity<CandidateTracking.State> save(Authentication auth,@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody CandidateTracking.Edit edit){return state(tracking.save(accounts.require(auth).id(),id,etag,edit));}
    @GetMapping("/candidates/{id}/tracking/history")
    ResponseEntity<CandidateService.Page<CandidateTracking.Event>> history(Authentication auth,@PathVariable UUID id,@RequestParam(defaultValue="0") int cursor){return ResponseEntity.ok().header("Cache-Control","no-store").body(tracking.history(accounts.require(auth).id(),id,cursor));}
    @GetMapping("/pools/{pool}/tracking")
    ResponseEntity<CandidateService.Page<CandidateTracking.Row>> list(Authentication auth,@PathVariable UUID pool,@RequestParam(required=false) CandidateTracking.Status status,@RequestParam(required=false) String list,@RequestParam(required=false) String tag,@RequestParam(defaultValue="0") int cursor){return ResponseEntity.ok().header("Cache-Control","no-store").body(tracking.list(accounts.require(auth).id(),pool,status,list,tag,cursor));}
    private ResponseEntity<CandidateTracking.State> state(CandidateTracking.State s){return ResponseEntity.ok().header("Cache-Control","no-store").eTag("\""+s.version()+"\"").body(s);}
}
