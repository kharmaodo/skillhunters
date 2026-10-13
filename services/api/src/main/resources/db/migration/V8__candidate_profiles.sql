CREATE TABLE candidate_profile (
    id UUID PRIMARY KEY,
    import_id UUID NOT NULL UNIQUE REFERENCES document_import(id) ON DELETE CASCADE,
    revision_id UUID NOT NULL REFERENCES extraction_revision(id) ON DELETE CASCADE,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version > 0),
    profile_json TEXT NOT NULL
);
CREATE TABLE candidate_contact (
    candidate_id UUID PRIMARY KEY REFERENCES candidate_profile(id) ON DELETE CASCADE,
    email VARCHAR(254),
    phone VARCHAR(40)
);
CREATE TABLE candidate_edit (
    id UUID PRIMARY KEY,
    candidate_id UUID NOT NULL REFERENCES candidate_profile(id) ON DELETE CASCADE,
    version BIGINT NOT NULL,
    actor_id UUID NOT NULL REFERENCES app_user(id),
    fields_json TEXT NOT NULL,
    provenance VARCHAR(600) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(candidate_id, version)
);
CREATE INDEX candidate_edit_page ON candidate_edit(candidate_id, version);
