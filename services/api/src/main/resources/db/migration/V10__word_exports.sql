CREATE TABLE export_permission (
    user_id UUID NOT NULL REFERENCES app_user(id),
    pool_id UUID NOT NULL REFERENCES talent_pool(id),
    PRIMARY KEY(user_id,pool_id)
);
CREATE TABLE candidate_export (
    id UUID PRIMARY KEY,
    candidate_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    template_version VARCHAR(80) NOT NULL,
    template_sha256 VARCHAR(64) NOT NULL,
    renderer_version VARCHAR(80) NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    content BYTEA NOT NULL,
    created_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(candidate_id,revision_id) REFERENCES candidate_revision(candidate_id,id) ON DELETE CASCADE,
    UNIQUE(candidate_id,revision_id,template_version,template_sha256,renderer_version)
);
CREATE TABLE candidate_export_event (
    id UUID PRIMARY KEY,
    export_id UUID NOT NULL REFERENCES candidate_export(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL REFERENCES app_user(id),
    action VARCHAR(40) NOT NULL CHECK (action IN ('GENERATED','DOWNLOAD_PREPARED')),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
