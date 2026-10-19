CREATE TABLE candidate_period_links (
    candidate_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    version BIGINT NOT NULL,
    links_json TEXT NOT NULL,
    actor_id UUID NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(candidate_id,version),
    FOREIGN KEY(candidate_id,revision_id) REFERENCES candidate_revision(candidate_id,id) ON DELETE CASCADE
);
