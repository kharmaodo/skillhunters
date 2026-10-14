CREATE TABLE candidate_revision (
    id UUID PRIMARY KEY,
    candidate_id UUID NOT NULL REFERENCES candidate_profile(id) ON DELETE CASCADE,
    version BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT','PUBLISHED')),
    revision_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(candidate_id,version),
    UNIQUE(candidate_id,id)
);
CREATE TABLE candidate_assertion_index (
    candidate_id UUID NOT NULL,
    source_index INTEGER NOT NULL,
    revision_id UUID NOT NULL,
    field VARCHAR(40) NOT NULL CHECK (field IN ('SKILL','DECLARED_LEVEL','EXPERIENCE')),
    normalized_value VARCHAR(200),
    period_start VARCHAR(10),
    period_end VARCHAR(10),
    period_precision VARCHAR(10),
    period_kind VARCHAR(20),
    PRIMARY KEY(candidate_id,source_index),
    FOREIGN KEY(candidate_id,revision_id) REFERENCES candidate_revision(candidate_id,id) ON DELETE CASCADE
);
CREATE INDEX candidate_assertion_lookup ON candidate_assertion_index(field,normalized_value,candidate_id);
CREATE INDEX candidate_period_lookup ON candidate_assertion_index(field,period_precision,period_kind,period_start,period_end);
