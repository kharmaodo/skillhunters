package com.skillhunters;

import java.util.UUID;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.*;

class IdentitySecurityTest extends IdentityTestSupport {
    @Test void anonymousApiIs401WithoutRedirect() throws Exception {
        mvc.perform(get("/api/v1/session")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_REQUIRED"));
    }
    @Test void onlyAssignedPoolsAreVisible() throws Exception {
        mvc.perform(get("/api/v1/pools").with(as("alice"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(poolA.toString()));
        mvc.perform(get("/api/v1/pools/"+poolB).with(as("alice"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/pools/"+UUID.randomUUID()).with(as("alice"))).andExpect(status().isNotFound());
    }
    @Test void administratorDoesNotImplicitlyReadPools() throws Exception {
        mvc.perform(get("/api/v1/pools").with(as("admin"))).andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get("/api/v1/pools/"+poolA).with(as("admin"))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/admin/pools").with(as("admin"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }
    @Test void csrfAndAdminAreBothRequired() throws Exception {
        mvc.perform(put("/api/v1/admin/memberships").with(as("admin")).contentType("application/json").content(replacement("[]")))
            .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/admin/memberships").with(as("alice")).with(csrf()).contentType("application/json").content(replacement("[]")))
            .andExpect(status().isForbidden());
    }
    @Test void revocationIsImmediateAndReplacementIsIdempotent() throws Exception {
        var actor = as("alice");
        mvc.perform(get("/api/v1/pools/"+poolA).with(actor)).andExpect(status().isOk());
        for (int i=0;i<2;i++) mvc.perform(put("/api/v1/admin/memberships").with(as("admin")).with(csrf())
            .contentType("application/json").content(replacement("[]"))).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/pools/"+poolA).with(actor)).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM identity_audit WHERE action='MEMBERSHIP_REPLACED'", Integer.class)).isEqualTo(1);
        var audit = db.queryForMap("SELECT before_roles,after_roles FROM identity_audit");
        assertThat(audit.values()).contains("RECRUITER", "");
    }
    @Test void grantingPoolRoleCannotGrantGlobalAdministrator() throws Exception {
        mvc.perform(put("/api/v1/admin/memberships").with(as("admin")).with(csrf()).contentType("application/json")
            .content(replacement("[\"ADMIN\"]"))).andExpect(status().isBadRequest());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM global_role WHERE user_id=?", Integer.class, alice)).isZero();
    }
    @Test void wrongIssuerAndUnprovisionedAndDisabledAreDenied() throws Exception {
        mvc.perform(get("/api/v1/session").with(oidcLogin().idToken(t -> t.subject("alice").issuer("https://other.example.com"))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/session").with(as("unknown"))).andExpect(status().isForbidden());
        db.update("UPDATE app_user SET enabled=FALSE WHERE id=?", alice);
        mvc.perform(get("/api/v1/pools").with(as("alice"))).andExpect(status().isForbidden());
    }
    @Test void sessionReturnsCsrfAndNeverOidcTokens() throws Exception {
        mvc.perform(get("/api/v1/session").with(as("alice"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.csrfToken").isNotEmpty()).andExpect(jsonPath("$.accessToken").doesNotExist())
            .andExpect(jsonPath("$.poolIds[0]").value(poolA.toString()));
    }
    @Test void invalidTargetsLeaveMembershipsIntact() throws Exception {
        mvc.perform(put("/api/v1/admin/memberships").with(as("admin")).with(csrf()).contentType("application/json")
            .content(replacement("[]").replace(poolA.toString(), UUID.randomUUID().toString()))).andExpect(status().isNotFound());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM membership", Integer.class)).isEqualTo(1);
    }
    @Test void logoutIsCsrfProtectedAndClearsSession() throws Exception {
        mvc.perform(delete("/api/v1/session").with(as("alice"))).andExpect(status().isForbidden());
        var session = new org.springframework.mock.web.MockHttpSession();
        mvc.perform(delete("/api/v1/session").session(session).with(as("alice")).with(csrf()))
            .andExpect(status().isNoContent()).andExpect(cookie().maxAge("SH_SESSION", 0));
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/session")).andExpect(status().isUnauthorized());
    }
    @Test void disabledAccountCanStillLogout() throws Exception {
        db.update("UPDATE app_user SET enabled=FALSE WHERE id=?", alice);
        mvc.perform(delete("/api/v1/session").with(as("alice")).with(csrf())).andExpect(status().isNoContent());
    }
}
