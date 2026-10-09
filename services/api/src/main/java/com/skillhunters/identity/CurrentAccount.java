package com.skillhunters.identity;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;
import com.skillhunters.shared.ApiProblem.Rejected;

@Component
public class CurrentAccount {
    private final IdentityStore store;
    public CurrentAccount(IdentityStore store) { this.store = store; }
    public IdentityStore.Account require(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser user)) {
            throw new Rejected(HttpStatus.UNAUTHORIZED, "SESSION_REQUIRED");
        }
        var account = store.find(user.getIssuer().toString(), user.getSubject());
        if (!account.enabled()) throw new Rejected(HttpStatus.FORBIDDEN, "ACCOUNT_DISABLED");
        return account;
    }
    public IdentityStore.Account admin(Authentication authentication) {
        var account = require(authentication);
        if (!store.globalRoles(account.id()).contains("ADMIN")) {
            throw new Rejected(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED");
        }
        return account;
    }
}
