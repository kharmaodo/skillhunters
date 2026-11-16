package com.skillhunters.talent;

import com.skillhunters.shared.ApiProblem.Rejected;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

// Internal lock/guard, without dependency on CandidateService or communication.
@Service
public class CandidateIdentity {
    private final JdbcClient db;
    public CandidateIdentity(JdbcClient db){this.db=db;}
    public UUID id(UUID candidate){return db.sql("SELECT identity_id FROM candidate_identity_member WHERE candidate_id=:c").param("c",candidate).query(UUID.class).optional().orElseThrow(()->new Rejected(HttpStatus.NOT_FOUND,"CANDIDATE_NOT_FOUND"));}
    public void lock(UUID candidate){var before=id(candidate);lockIds(List.of(before));if(!before.equals(id(candidate)))throw new Rejected(HttpStatus.CONFLICT,"LINEAGE_CHANGED");}
    public void lockIds(Collection<UUID> ids){for(var id:new TreeSet<>(ids))db.sql("SELECT id FROM candidate_identity WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).single();}
    public void requireActive(UUID candidate){lock(candidate);if(!db.sql("SELECT active_version FROM candidate_profile WHERE id=:c").param("c",candidate).query(Boolean.class).single())throw new Rejected(HttpStatus.CONFLICT,"CANDIDATE_VERSION_INACTIVE");}
    public boolean opposed(UUID candidate){return db.sql("SELECT opposed FROM candidate_identity WHERE id=:id").param("id",id(candidate)).query(Boolean.class).single();}
    public void oppose(UUID candidate){db.sql("UPDATE candidate_identity SET opposed=TRUE WHERE id=:id").param("id",id(candidate)).update();}
}
