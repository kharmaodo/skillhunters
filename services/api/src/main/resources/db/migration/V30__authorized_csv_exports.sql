CREATE TABLE csv_export_ticket (
 id UUID PRIMARY KEY,
 actor_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 pool_id UUID NOT NULL REFERENCES talent_pool(id) ON DELETE CASCADE,
 search_id UUID REFERENCES scored_search(id) ON DELETE CASCADE,
 fields VARCHAR(500) NOT NULL,
 purpose VARCHAR(80) NOT NULL,
 row_count INTEGER NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 consumed_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE csv_export_member (
 export_id UUID NOT NULL REFERENCES csv_export_ticket(id) ON DELETE CASCADE,
 candidate_id UUID NOT NULL REFERENCES candidate_profile(id) ON DELETE CASCADE,
 candidate_version BIGINT NOT NULL,
 ordinal INTEGER NOT NULL,
 PRIMARY KEY(export_id,candidate_id),
 UNIQUE(export_id,ordinal)
);
ALTER TABLE governance_audit ADD COLUMN csv_fields VARCHAR(500);
ALTER TABLE governance_audit ADD COLUMN csv_rows INTEGER;
CREATE INDEX csv_export_expiry ON csv_export_ticket(expires_at);
CREATE INDEX csv_export_quota ON governance_audit(actor_id,action,occurred_at);
