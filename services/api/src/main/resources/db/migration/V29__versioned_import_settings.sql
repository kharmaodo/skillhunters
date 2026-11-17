CREATE TABLE import_settings_head (id INTEGER PRIMARY KEY, version BIGINT NOT NULL);
INSERT INTO import_settings_head(id,version) VALUES (1,0);
CREATE TABLE import_settings_version (
 version BIGINT PRIMARY KEY,
 max_file_bytes BIGINT NOT NULL,
 max_batch_files INTEGER NOT NULL,
 max_batch_bytes BIGINT NOT NULL,
 max_files_per_user BIGINT NOT NULL,
 max_bytes_per_user BIGINT NOT NULL,
 template_version VARCHAR(80) NOT NULL,
 template_sha256 VARCHAR(64) NOT NULL,
 actor_id UUID,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
 reason VARCHAR(500) NOT NULL
);
ALTER TABLE document_import ADD COLUMN settings_version BIGINT REFERENCES import_settings_version(version);
ALTER TABLE import_batch ADD COLUMN settings_version BIGINT REFERENCES import_settings_version(version);
