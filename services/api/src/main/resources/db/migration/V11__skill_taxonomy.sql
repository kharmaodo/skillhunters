CREATE TABLE skill_dictionary (
    version BIGINT PRIMARY KEY,
    entries_json TEXT NOT NULL,
    actor_id UUID,
    action VARCHAR(30) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO skill_dictionary(version,entries_json,action,reason) VALUES (0,'[]','INITIAL','Dictionnaire vide');
CREATE TABLE skill_dictionary_head (
    id INTEGER PRIMARY KEY CHECK (id=1),
    version BIGINT NOT NULL REFERENCES skill_dictionary(version)
);
INSERT INTO skill_dictionary_head(id,version) VALUES (1,0);
ALTER TABLE candidate_assertion_index ADD COLUMN taxonomy_json TEXT;
CREATE TABLE candidate_skill_normalization (
    candidate_id UUID NOT NULL,
    revision_id UUID NOT NULL,
    source_index INTEGER NOT NULL,
    normalization_json TEXT NOT NULL,
    PRIMARY KEY(candidate_id,revision_id,source_index),
    FOREIGN KEY(candidate_id,revision_id) REFERENCES candidate_revision(candidate_id,id) ON DELETE CASCADE
);
