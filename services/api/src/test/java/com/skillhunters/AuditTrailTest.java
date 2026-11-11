package com.skillhunters;

import com.skillhunters.governance.AuditTrail;
import com.skillhunters.shared.ApiProblem.Rejected;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AuditTrailTest extends IdentityTestSupport {
    @Autowired AuditTrail audit;
    @Test void separatePermissionAndSelfAuditingWithStablePagination() throws Exception {
        String action="TEST_AUDIT";
        long first=db.queryForObject("SELECT COALESCE(MAX(sequence),0) FROM governance_audit",Long.class);
        for(int i=0;i<55;i++)audit.record(alice,action,UUID.randomUUID(),poolA,"Recrutement synthétique");
        mvc.perform(get("/api/v1/audit-events").with(as("admin"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit-events").with(as("alice"))).andExpect(status().isForbidden());
        db.update("INSERT INTO global_role(user_id,role) VALUES (?,'COMPLIANCE')",admin);
        mvc.perform(get("/api/v1/audit-events").param("before","0").with(as("admin"))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/audit-events").param("action","invalid action").with(as("admin"))).andExpect(status().isBadRequest());
        int prior=db.queryForObject("SELECT COUNT(*) FROM governance_audit WHERE action='AUDIT_READ'",Integer.class);
        var page=audit.list(admin,poolA,null,action);assertThat(page.items()).hasSize(50);assertThat(page.nextCursor()).isNotNull();
        assertThat(page.items()).allMatch(e->e.actorId().equals(alice)&&e.poolId().equals(poolA)&&e.sequence()>first);
        var older=audit.list(admin,poolA,page.nextCursor(),action);assertThat(older.items().stream().filter(e->e.sequence()>first)).hasSize(5);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM governance_audit WHERE action='AUDIT_READ'",Integer.class)).isEqualTo(prior+2);
        mvc.perform(get("/api/v1/audit-events").with(as("admin"))).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        assertThat(audit.list(admin,poolB,null,action).items()).isEmpty();
        db.update("DELETE FROM global_role WHERE user_id=? AND role='COMPLIANCE'",admin);
        assertThatThrownBy(()->audit.list(admin,null,null,null)).isInstanceOf(Rejected.class);
    }
    @Test void unauthenticatedLoginFailureHasNoInventedActor() {
        audit.record(null,"SESSION_LOGIN_FAILED",null,null,null);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM governance_audit WHERE action='SESSION_LOGIN_FAILED' AND actor_id IS NULL",Integer.class)).isPositive();
    }
    @Test void postgresqlRejectsUpdateDeleteAndTruncate() throws Exception {
        // Production guard is PostgreSQL-specific; H2 validates the portable service behavior.
        try(var connection=db.getDataSource().getConnection()){if(!connection.getMetaData().getDatabaseProductName().equals("PostgreSQL"))return;}
        audit.record(alice,"TEST_IMMUTABLE",UUID.randomUUID(),poolA,null);
        assertThatThrownBy(()->db.update("UPDATE governance_audit SET action='ALTERED' WHERE action='TEST_IMMUTABLE'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("DELETE FROM governance_audit WHERE action='TEST_IMMUTABLE'")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.execute("TRUNCATE TABLE governance_audit")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}
