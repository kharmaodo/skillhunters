CREATE TABLE document_analysis (
    import_id UUID PRIMARY KEY REFERENCES document_import(id) ON DELETE CASCADE,
    requested_by UUID NOT NULL REFERENCES app_user(id),
    state VARCHAR(24) NOT NULL CHECK (state IN ('PENDING','RUNNING','RETRY','EXTRACTED','OCR_REQUIRED','REJECTED','FAILED','CANCELLED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 3),
    lease_token UUID,
    lease_until TIMESTAMP WITH TIME ZONE,
    available_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    code VARCHAR(60),
    parser_version VARCHAR(200),
    av_version VARCHAR(200),
    result_json TEXT
);
CREATE INDEX document_analysis_pending ON document_analysis(state,available_at);
CREATE TABLE document_analysis_event (
    id UUID PRIMARY KEY,
    import_id UUID NOT NULL REFERENCES document_import(id) ON DELETE CASCADE,
    state VARCHAR(24) NOT NULL,
    code VARCHAR(60),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO document_analysis(import_id,requested_by,state)
SELECT id,actor_id,'PENDING' FROM document_import WHERE state='QUARANTINED' AND expires_at>CURRENT_TIMESTAMP;
