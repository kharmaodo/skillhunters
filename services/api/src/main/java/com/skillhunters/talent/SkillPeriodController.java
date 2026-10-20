package com.skillhunters.talent;

import com.skillhunters.identity.CurrentAccount;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/candidates/{id}/skill-periods")
public class SkillPeriodController {
    private final SkillPeriodService periods;private final CurrentAccount accounts;
    public SkillPeriodController(SkillPeriodService periods,CurrentAccount accounts) {this.periods=periods;this.accounts=accounts;}
    @GetMapping
    ResponseEntity<SkillPeriodService.Report> get(Authentication auth,@PathVariable UUID id,@RequestParam(required=false) LocalDate referenceDate) {
        return response(periods.get(accounts.require(auth).id(),id,referenceDate));
    }
    @PutMapping
    ResponseEntity<SkillPeriodService.Report> put(Authentication auth,@PathVariable UUID id,@RequestHeader(value="If-Match",required=false) String etag,@RequestBody SkillPeriodService.Request request) {
        return response(periods.save(accounts.require(auth).id(),id,etag,request));
    }
    @GetMapping("/history")
    List<SkillPeriodService.Snapshot> history(Authentication auth,@PathVariable UUID id,@RequestParam(defaultValue="0") int cursor) {
        return periods.history(accounts.require(auth).id(),id,cursor);
    }
    private ResponseEntity<SkillPeriodService.Report> response(SkillPeriodService.Report r) {return ResponseEntity.ok().eTag("\""+r.version()+"\"").header("Cache-Control","no-store").body(r);}
}
