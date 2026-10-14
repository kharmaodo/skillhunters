package com.skillhunters.exports;

import com.skillhunters.shared.ApiProblem.Rejected;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExportPermissions {
    private final JdbcClient db;
    public ExportPermissions(JdbcClient db) {this.db=db;}
    public boolean allowed(UUID user,UUID pool) {
        return db.sql("SELECT COUNT(*) FROM export_permission e WHERE user_id=:user AND pool_id=:pool AND EXISTS (SELECT 1 FROM membership m WHERE m.user_id=e.user_id AND m.pool_id=e.pool_id)")
            .param("user",user).param("pool",pool).query(Integer.class).single()>0;
    }
    @Transactional
    public void set(UUID actor,UUID user,UUID pool,boolean allowed) {
        if(db.sql("SELECT id FROM app_user WHERE id=:user FOR UPDATE").param("user",user).query(UUID.class).optional().isEmpty())
            throw new Rejected(HttpStatus.NOT_FOUND,"MEMBERSHIP_TARGET_NOT_FOUND");
        if(allowed && db.sql("SELECT COUNT(*) FROM membership WHERE user_id=:user AND pool_id=:pool").param("user",user).param("pool",pool).query(Integer.class).single()==0)
            throw new Rejected(HttpStatus.CONFLICT,"EXPORT_MEMBERSHIP_REQUIRED");
        boolean before=allowed(user,pool);if(before==allowed) return;
        db.sql("DELETE FROM export_permission WHERE user_id=:user AND pool_id=:pool").param("user",user).param("pool",pool).update();
        if(allowed) db.sql("INSERT INTO export_permission(user_id,pool_id) VALUES (:user,:pool)").param("user",user).param("pool",pool).update();
        db.sql("INSERT INTO identity_audit(id,actor_id,action,target_id,pool_id,before_roles,after_roles) VALUES (:id,:actor,'EXPORT_PERMISSION_CHANGED',:user,:pool,:before,:after)")
            .param("id",UUID.randomUUID()).param("actor",actor).param("user",user).param("pool",pool).param("before",before?"EXPORT":"").param("after",allowed?"EXPORT":"").update();
    }
}
