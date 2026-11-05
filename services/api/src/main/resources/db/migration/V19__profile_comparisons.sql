-- Minimal audit: ownership and retention inherited from the private ranking.
CREATE TABLE profile_comparison (
 id UUID PRIMARY KEY,
 search_id UUID NOT NULL REFERENCES scored_search(id) ON DELETE CASCADE,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 UNIQUE(id,search_id)
);
CREATE INDEX profile_comparison_search ON profile_comparison(search_id);
CREATE TABLE profile_comparison_member (
 comparison_id UUID NOT NULL,
 search_id UUID NOT NULL,
 candidate_id UUID NOT NULL,
 PRIMARY KEY(comparison_id,candidate_id),
 FOREIGN KEY(comparison_id,search_id) REFERENCES profile_comparison(id,search_id) ON DELETE CASCADE,
 FOREIGN KEY(search_id,candidate_id) REFERENCES scored_search_item(search_id,candidate_id) ON DELETE CASCADE
);
