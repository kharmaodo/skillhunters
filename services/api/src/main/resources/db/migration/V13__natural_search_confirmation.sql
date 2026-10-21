-- No original prose, contacts or candidate snapshots. Short-lived review trace only.
CREATE TABLE natural_search_proposal (
    id UUID PRIMARY KEY,
    actor_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    pool_id UUID NOT NULL REFERENCES talent_pool(id) ON DELETE CASCADE,
    interpretation_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    confirmed_at TIMESTAMP WITH TIME ZONE,
    confirmed_trace_json TEXT,
    search_id UUID
);
CREATE INDEX natural_search_expiry ON natural_search_proposal(expires_at);
CREATE INDEX natural_search_actor ON natural_search_proposal(actor_id);
