-- Immutable proposals and their exact analysis snapshot. Original deletion removes all derivatives.
CREATE TABLE extraction_revision (
    id UUID PRIMARY KEY,
    import_id UUID NOT NULL REFERENCES document_import(id) ON DELETE CASCADE,
    analysis_sha256 VARCHAR(64) NOT NULL,
    rules_version VARCHAR(80) NOT NULL,
    source_json TEXT NOT NULL,
    revision_json TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(import_id, analysis_sha256, rules_version)
);
