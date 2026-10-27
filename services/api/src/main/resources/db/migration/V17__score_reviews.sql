CREATE TABLE score_review_event (
 search_id UUID NOT NULL,
 candidate_id UUID NOT NULL,
 version INTEGER NOT NULL CHECK(version > 0),
 replacement_search_id UUID REFERENCES scored_search(id) ON DELETE CASCADE,
 event_json TEXT NOT NULL,
 PRIMARY KEY(search_id,candidate_id,version),
 FOREIGN KEY(search_id,candidate_id) REFERENCES scored_search_item(search_id,candidate_id) ON DELETE CASCADE
);
