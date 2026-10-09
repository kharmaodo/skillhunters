CREATE TABLE import_batch (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES app_user(id),
    pool_id UUID NOT NULL REFERENCES talent_pool(id),
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    source VARCHAR(200) NOT NULL,
    purpose VARCHAR(200) NOT NULL,
    basis_code VARCHAR(80) NOT NULL,
    notice_version VARCHAR(80),
    policy_id UUID NOT NULL REFERENCES import_policy(id),
    policy_label VARCHAR(200) NOT NULL,
    retention_days INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE(actor_id,pool_id,idempotency_key)
);
CREATE INDEX import_batch_pool_date ON import_batch(pool_id,created_at,id);
CREATE TABLE import_batch_item (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES import_batch(id),
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 99),
    file_name VARCHAR(200) NOT NULL,
    byte_size BIGINT NOT NULL CHECK (byte_size BETWEEN 0 AND 314572800),
    sha256 VARCHAR(64) NOT NULL,
    state VARCHAR(30) NOT NULL CHECK (state IN ('WAITING_UPLOAD','UPLOADING','QUARANTINED','REJECTED_FORMAT','RETRYABLE_FAILURE','FAILED')),
    error_code VARCHAR(80),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 5),
    lease_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    UNIQUE(batch_id,ordinal)
);
CREATE INDEX import_batch_item_batch ON import_batch_item(batch_id);
CREATE TABLE import_batch_event (
    id UUID PRIMARY KEY,
    batch_id UUID NOT NULL REFERENCES import_batch(id),
    item_id UUID REFERENCES import_batch_item(id),
    actor_id UUID NOT NULL REFERENCES app_user(id),
    action VARCHAR(40) NOT NULL,
    error_code VARCHAR(80),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
