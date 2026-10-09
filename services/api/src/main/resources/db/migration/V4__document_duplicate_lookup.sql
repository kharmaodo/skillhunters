CREATE INDEX document_import_duplicate_idx ON document_import(pool_id, sha256, byte_size, created_at DESC, id DESC);
