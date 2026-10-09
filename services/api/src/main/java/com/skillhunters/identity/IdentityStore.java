package com.skillhunters.identity;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import com.skillhunters.shared.ApiProblem.Rejected;

@Repository
public class IdentityStore {
    public record Account(UUID id, String displayName, boolean enabled) {}
    public record Pool(UUID id, String name) {}
    public record Membership(UUID userId, UUID poolId, String role) {}
    private final JdbcClient db;
    public IdentityStore(JdbcClient db) { this.db = db; }

    public Account find(String issuer, String subject) {
        return db.sql("SELECT id, display_name, enabled FROM app_user WHERE issuer = :issuer AND subject = :subject")
                .param("issuer", issuer).param("subject", subject)
                .query((rs, i) -> new Account(rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getBoolean("enabled")))
                .optional().orElseThrow(() -> new Rejected(HttpStatus.FORBIDDEN, "ACCOUNT_NOT_PROVISIONED"));
    }

    public List<String> globalRoles(UUID user) {
        return db.sql("SELECT role FROM global_role WHERE user_id = :user ORDER BY role")
                .param("user", user).query(String.class).list();
    }

    public List<Pool> pools(UUID user) {
        return db.sql("SELECT DISTINCT p.id, p.name FROM talent_pool p JOIN membership m ON m.pool_id = p.id WHERE m.user_id = :user ORDER BY p.name, p.id")
                .param("user", user).query((rs, i) -> new Pool(rs.getObject("id", UUID.class), rs.getString("name"))).list();
    }

    public List<Membership> memberships(UUID user) {
        return db.sql("SELECT user_id, pool_id, role FROM membership WHERE user_id = :user ORDER BY pool_id, role")
                .param("user", user).query((rs, i) -> new Membership(user, rs.getObject("pool_id", UUID.class), rs.getString("role"))).list();
    }

    public Pool pool(UUID user, UUID pool) {
        return pools(user).stream().filter(p -> p.id().equals(pool)).findFirst()
                .orElseThrow(() -> new Rejected(HttpStatus.NOT_FOUND, "POOL_NOT_FOUND"));
    }

    public List<Account> accounts() {
        return db.sql("SELECT id, display_name, enabled FROM app_user ORDER BY display_name, id LIMIT 100")
                .query((rs, i) -> new Account(rs.getObject("id", UUID.class), rs.getString("display_name"), rs.getBoolean("enabled"))).list();
    }

    public List<Pool> allPools() {
        return db.sql("SELECT id, name FROM talent_pool ORDER BY name, id LIMIT 100")
                .query((rs, i) -> new Pool(rs.getObject("id", UUID.class), rs.getString("name"))).list();
    }

    @Transactional
    public void replaceMemberships(UUID actor, UUID user, UUID pool, List<String> roles) {
        // Lock the user so simultaneous replacements cannot interleave.
        var found = db.sql("SELECT id FROM app_user WHERE id = :id FOR UPDATE").param("id", user).query(UUID.class).optional();
        if (found.isEmpty() || db.sql("SELECT COUNT(*) FROM talent_pool WHERE id = :id").param("id", pool).query(Integer.class).single() != 1) {
            throw new Rejected(HttpStatus.NOT_FOUND, "MEMBERSHIP_TARGET_NOT_FOUND");
        }
        var before = memberships(user).stream().filter(m -> m.poolId().equals(pool)).map(Membership::role).sorted().toList();
        var after = roles.stream().distinct().sorted().toList();
        if (before.equals(after)) return;
        db.sql("DELETE FROM membership WHERE user_id = :user AND pool_id = :pool").param("user", user).param("pool", pool).update();
        for (var role : after) {
            db.sql("INSERT INTO membership(user_id,pool_id,role) VALUES (:user,:pool,:role)")
                    .param("user", user).param("pool", pool).param("role", role).update();
        }
        db.sql("INSERT INTO identity_audit(id,actor_id,action,target_id,pool_id,before_roles,after_roles) VALUES (:id,:actor,'MEMBERSHIP_REPLACED',:user,:pool,:before,:after)")
                .param("id", UUID.randomUUID()).param("actor", actor).param("user", user).param("pool", pool)
                .param("before", String.join(",", before)).param("after", String.join(",", after)).update();
    }

    public void audit(UUID user, String action) {
        db.sql("INSERT INTO identity_audit(id,actor_id,action) VALUES (:id,:user,:action)")
                .param("id", UUID.randomUUID()).param("user", user).param("action", action).update();
    }
}
