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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IdentityTestSupport {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    final UUID alice = UUID.fromString("10000000-0000-0000-0000-000000000001");
    final UUID admin = UUID.fromString("10000000-0000-0000-0000-000000000002");
    final UUID poolA = UUID.fromString("20000000-0000-0000-0000-000000000001");
    final UUID poolB = UUID.fromString("20000000-0000-0000-0000-000000000002");
    final String issuer = "http://localhost:8081/realms/skillhunters";

    @BeforeEach void seed() {
        for (var table : List.of("import_batch_event", "import_batch_item", "import_batch", "document_audit", "document_import", "import_policy", "identity_audit", "membership", "global_role", "talent_pool", "app_user")) db.update("DELETE FROM " + table);
        db.update("INSERT INTO app_user(id,issuer,subject,display_name) VALUES (?,?,?,?)", alice, issuer, "alice", "Alice Exemple");
        db.update("INSERT INTO app_user(id,issuer,subject,display_name) VALUES (?,?,?,?)", admin, issuer, "admin", "Admin Exemple");
        db.update("INSERT INTO talent_pool(id,name) VALUES (?,?)", poolA, "Vivier A");
        db.update("INSERT INTO talent_pool(id,name) VALUES (?,?)", poolB, "Vivier B");
        db.update("INSERT INTO membership(user_id,pool_id,role) VALUES (?,?,'RECRUITER')", alice, poolA);
        db.update("INSERT INTO global_role(user_id,role) VALUES (?,'ADMIN')", admin);
    }
    SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor as(String subject) {
        return oidcLogin().idToken(t -> t.subject(subject).issuer(issuer));
    }
    String replacement(String roles) {
        return "{\"userId\":\"" + alice + "\",\"poolId\":\"" + poolA + "\",\"roles\":" + roles + "}";
    }
}
