package com.skillhunters.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {
    private final IdentityStore store;
    private final CurrentAccount accounts;
    public IdentityController(IdentityStore store, CurrentAccount accounts) { this.store = store; this.accounts = accounts; }
    public record Session(UUID userId, String displayName, List<String> roles, List<UUID> poolIds,
            List<IdentityStore.Membership> memberships, String csrfToken) {}
    public record MembershipInput(@NotNull UUID userId, @NotNull UUID poolId,
            @NotNull @Size(max=2) List<@NotNull @Pattern(regexp="RECRUITER|RECRUITMENT_LEAD") String> roles) {}

    @GetMapping("/session")
    Session session(Authentication auth, CsrfToken csrf) {
        var user = accounts.require(auth);
        return new Session(user.id(), user.displayName(), store.globalRoles(user.id()),
                store.pools(user.id()).stream().map(IdentityStore.Pool::id).toList(), store.memberships(user.id()), csrf.getToken());
    }

    @DeleteMapping("/session")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void logout(Authentication auth, HttpServletRequest request, HttpServletResponse response) {
        // Logout remains possible after an account is disabled or its grants are revoked.
        try { store.audit(accounts.require(auth).id(), "SESSION_LOGOUT"); }
        catch (com.skillhunters.shared.ApiProblem.Rejected ignored) { /* no identity disclosure */ }
        finally {
            new SecurityContextLogoutHandler().logout(request, response, auth);
            var cookie = new jakarta.servlet.http.Cookie("SH_SESSION", "");
            cookie.setPath("/"); cookie.setHttpOnly(true); cookie.setSecure(request.isSecure()); cookie.setMaxAge(0);
            cookie.setAttribute("SameSite", "Lax"); response.addCookie(cookie);
        }
    }

    @GetMapping("/pools")
    List<IdentityStore.Pool> pools(Authentication auth) { return store.pools(accounts.require(auth).id()); }

    @GetMapping("/pools/{id}")
    IdentityStore.Pool pool(Authentication auth, @PathVariable UUID id) { return store.pool(accounts.require(auth).id(), id); }

    @GetMapping("/admin/users")
    List<IdentityStore.Account> users(Authentication auth) { accounts.admin(auth); return store.accounts(); }

    @GetMapping("/admin/pools")
    List<IdentityStore.Pool> adminPools(Authentication auth) { accounts.admin(auth); return store.allPools(); }

    @GetMapping("/admin/users/{id}/memberships")
    List<IdentityStore.Membership> memberships(Authentication auth, @PathVariable UUID id) {
        accounts.admin(auth); return store.memberships(id);
    }

    @PutMapping("/admin/memberships")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    void memberships(Authentication auth, @Valid @RequestBody MembershipInput input) {
        var actor = accounts.admin(auth);
        store.replaceMemberships(actor.id(), input.userId(), input.poolId(), input.roles());
    }
}
