-- H2 test adapter only; production PostgreSQL uses native vector(384).

CREATE TABLE skill_vector_generation (
    dictionary_version BIGINT NOT NULL REFERENCES skill_dictionary(version) ON DELETE CASCADE,
    model VARCHAR(100) NOT NULL,
    dimensions INTEGER NOT NULL CHECK (dimensions=384),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(dictionary_version,model)
);
CREATE TABLE skill_vector_entry (
    dictionary_version BIGINT NOT NULL,
    model VARCHAR(100) NOT NULL,
    skill_id UUID NOT NULL,
    canonical VARCHAR(200) NOT NULL,
    embedding VARCHAR(16000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(dictionary_version,model,skill_id),
    UNIQUE(dictionary_version,model,canonical),
    FOREIGN KEY(dictionary_version,model) REFERENCES skill_vector_generation(dictionary_version,model) ON DELETE CASCADE
);
-- Exact cosine queries are intentional. No approximate HNSW/IVFFlat index before recall benchmark.
