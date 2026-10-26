package com.skillhunters.scoring;

import com.skillhunters.identity.CurrentAccount;
import com.skillhunters.talent.CandidateService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/pools/{poolId}/scoring-configurations")
public class ScoringConfigurationController {
    private final ScoringConfigurations configs;private final CurrentAccount accounts;
    public ScoringConfigurationController(ScoringConfigurations configs,CurrentAccount accounts) {this.configs=configs;this.accounts=accounts;}
    @GetMapping
    ResponseEntity<ScoringConfigurations.State> state(Authentication auth,@PathVariable UUID poolId) {
        var state=configs.state(accounts.require(auth).id(),poolId);return ResponseEntity.ok().header("Cache-Control","no-store").eTag("\""+state.latest().version()+"\"").body(state);
    }
    @PostMapping
    ResponseEntity<ScoringConfigurations.Revision> save(Authentication auth,@PathVariable UUID poolId,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody ScoringConfigurations.Edit edit) {
        var r=configs.save(accounts.require(auth).id(),poolId,etag,edit);return ResponseEntity.status(201).header("Cache-Control","no-store").eTag("\""+r.version()+"\"").body(r);
    }
    @GetMapping("/history")
    CandidateService.Page<ScoringConfigurations.Revision> history(Authentication auth,@PathVariable UUID poolId,@RequestParam(defaultValue="0") int cursor) {return configs.history(accounts.require(auth).id(),poolId,cursor);}
    @GetMapping("/{version}")
    ScoringConfigurations.Revision revision(Authentication auth,@PathVariable UUID poolId,@PathVariable long version) {return configs.revision(accounts.require(auth).id(),poolId,version);}
}
