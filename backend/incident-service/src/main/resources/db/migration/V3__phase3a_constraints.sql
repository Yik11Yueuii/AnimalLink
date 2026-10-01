ALTER TABLE event ADD CONSTRAINT chk_event_status CHECK (status IN ('REPORTED','VERIFIED','ARCHIVED','REJECTED','DUPLICATE'));
ALTER TABLE event ADD CONSTRAINT fk_event_draft FOREIGN KEY (source_draft_id) REFERENCES event_draft(id);
ALTER TABLE evidence ADD CONSTRAINT fk_evidence_event FOREIGN KEY (event_id) REFERENCES event(id);
ALTER TABLE event_media ADD CONSTRAINT fk_event_media_event FOREIGN KEY (event_id) REFERENCES event(id), ADD CONSTRAINT uk_event_media_key UNIQUE (event_id, object_key);
ALTER TABLE evidence_media ADD CONSTRAINT fk_evidence_media_evidence FOREIGN KEY (evidence_id) REFERENCES evidence(id), ADD CONSTRAINT uk_evidence_media_key UNIQUE (evidence_id, object_key);
