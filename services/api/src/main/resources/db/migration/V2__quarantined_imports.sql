CREATE TABLE import_policy (
    id UUID PRIMARY KEY,
    label VARCHAR(200) NOT NULL,
    purpose VARCHAR(200) NOT NULL,
    basis_code VARCHAR(80) NOT NULL,
    retention_days INTEGER NOT NULL CHECK (retention_days BETWEEN 1 AND 3650),
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE document_import (
    id UUID PRIMARY KEY,
    pool_id UUID NOT NULL REFERENCES talent_pool(id),
    actor_id UUID NOT NULL REFERENCES app_user(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    object_key VARCHAR(150) NOT NULL UNIQUE,
    file_name VARCHAR(200) NOT NULL,
    media_type VARCHAR(120) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size > 0 AND byte_size <= 15728640),
    sha256 VARCHAR(64) NOT NULL,
    source VARCHAR(200) NOT NULL,
    purpose VARCHAR(200) NOT NULL,
    basis_code VARCHAR(80) NOT NULL,
    notice_version VARCHAR(80),
    policy_id UUID NOT NULL REFERENCES import_policy(id),
    retention_days INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    state VARCHAR(30) NOT NULL CHECK (state IN ('RECEIVING','QUARANTINED')),
    validation_version VARCHAR(40) NOT NULL,
    UNIQUE(actor_id, pool_id, idempotency_key)
);
CREATE INDEX document_import_pool_date ON document_import(pool_id, created_at, id);
CREATE TABLE document_audit (
    id UUID PRIMARY KEY,
    import_id UUID NOT NULL REFERENCES document_import(id),
    actor_id UUID NOT NULL REFERENCES app_user(id),
    action VARCHAR(40) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
