CREATE TABLE candidate_tracking (
 actor_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
 candidate_id UUID NOT NULL REFERENCES candidate_profile(id) ON DELETE CASCADE,
 version INTEGER NOT NULL CHECK (version > 0),
 status VARCHAR(24) NOT NULL CHECK(status IN ('NEW','TO_REVIEW','SHORTLISTED','CONTACTED','INTERVIEW','CLOSED')),
 state_json TEXT NOT NULL,
 PRIMARY KEY(actor_id,candidate_id)
);
CREATE TABLE candidate_tracking_label (
 actor_id UUID NOT NULL,
 candidate_id UUID NOT NULL,
 kind VARCHAR(4) NOT NULL CHECK(kind IN ('LIST','TAG')),
 label VARCHAR(80) NOT NULL,
 PRIMARY KEY(actor_id,candidate_id,kind,label),
 FOREIGN KEY(actor_id,candidate_id) REFERENCES candidate_tracking(actor_id,candidate_id) ON DELETE CASCADE
);
CREATE INDEX candidate_tracking_filter ON candidate_tracking_label(actor_id,kind,label);
CREATE TABLE candidate_tracking_event (
 actor_id UUID NOT NULL,
 candidate_id UUID NOT NULL,
 version INTEGER NOT NULL,
 event_json TEXT NOT NULL,
 PRIMARY KEY(actor_id,candidate_id,version),
 FOREIGN KEY(actor_id,candidate_id) REFERENCES candidate_tracking(actor_id,candidate_id) ON DELETE CASCADE
);
