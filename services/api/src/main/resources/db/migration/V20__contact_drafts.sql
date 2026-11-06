CREATE TABLE contact_draft_revision (
 candidate_id UUID NOT NULL REFERENCES candidate_profile(id) ON DELETE CASCADE,
 actor_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 version BIGINT NOT NULL,
 publication_id UUID NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 payload_json TEXT NOT NULL,
 PRIMARY KEY(candidate_id,actor_id,version),
 FOREIGN KEY(candidate_id,publication_id) REFERENCES candidate_revision(candidate_id,id) ON DELETE CASCADE
);
