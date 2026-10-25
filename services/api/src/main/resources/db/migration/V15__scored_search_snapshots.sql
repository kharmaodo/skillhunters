CREATE TABLE scored_search (
 id UUID PRIMARY KEY,
 actor_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 pool_id UUID NOT NULL REFERENCES talent_pool(id) ON DELETE CASCADE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 metadata_json TEXT NOT NULL
);
CREATE TABLE scored_search_item (
 search_id UUID NOT NULL REFERENCES scored_search(id) ON DELETE CASCADE,
 candidate_id UUID NOT NULL REFERENCES candidate_profile(id) ON DELETE CASCADE,
 publication_id UUID NOT NULL,
 rank_position INTEGER NOT NULL,
 snapshot_json TEXT NOT NULL,
 PRIMARY KEY(search_id,candidate_id),
 UNIQUE(search_id,rank_position),
 FOREIGN KEY(candidate_id,publication_id) REFERENCES candidate_revision(candidate_id,id) ON DELETE CASCADE
);
CREATE INDEX scored_search_expiry ON scored_search(expires_at);
