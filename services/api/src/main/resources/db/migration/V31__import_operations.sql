ALTER TABLE document_analysis ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE document_analysis ADD COLUMN manual_retries INTEGER NOT NULL DEFAULT 0;
ALTER TABLE document_analysis ADD COLUMN lease_recoveries INTEGER NOT NULL DEFAULT 0;
ALTER TABLE document_analysis_event ADD COLUMN duration_ms BIGINT;
UPDATE document_analysis SET manual_retries=LEAST(3,(SELECT COUNT(*) FROM document_analysis_event e WHERE e.import_id=document_analysis.import_id AND e.code='HUMAN_RETRY'));
CREATE INDEX analysis_event_window ON document_analysis_event(created_at,import_id);
