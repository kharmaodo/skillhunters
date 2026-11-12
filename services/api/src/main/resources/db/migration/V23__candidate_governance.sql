CREATE TABLE candidate_governance (
 candidate_id UUID PRIMARY KEY REFERENCES candidate_profile(id) ON DELETE CASCADE,
 version BIGINT NOT NULL DEFAULT 0,
 policy_id UUID REFERENCES import_policy(id),
 source VARCHAR(200),
 purpose VARCHAR(200),
 basis_code VARCHAR(80),
 justification VARCHAR(1000),
 notice_version VARCHAR(80),
 notice_evidence VARCHAR(500),
 notified_at TIMESTAMP WITH TIME ZONE,
 opposed BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE candidate_governance_revision (
 candidate_id UUID NOT NULL REFERENCES candidate_profile(id) ON DELETE CASCADE,
 version BIGINT NOT NULL,
 actor_id UUID,
 payload_json TEXT NOT NULL,
 occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(candidate_id,version)
);
CREATE TABLE contact_opposition_token (
 token_hash VARCHAR(64) PRIMARY KEY,
 candidate_id UUID NOT NULL REFERENCES candidate_profile(id) ON DELETE CASCADE,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 used BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX opposition_token_candidate ON contact_opposition_token(candidate_id);
