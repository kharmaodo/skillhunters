CREATE TABLE scoring_configuration (
 pool_id UUID NOT NULL REFERENCES talent_pool(id) ON DELETE CASCADE,
 version BIGINT NOT NULL CHECK (version>0),
 revision_json TEXT NOT NULL,
 PRIMARY KEY(pool_id,version)
);
