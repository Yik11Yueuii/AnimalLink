CREATE TABLE event_draft_media (
  id varchar(36) NOT NULL PRIMARY KEY, event_draft_id varchar(36) NOT NULL, temporary_object_key varchar(512) NOT NULL,
  content_type varchar(128) NOT NULL, size_bytes bigint NOT NULL, sort_order int NOT NULL, created_at timestamp(6) NOT NULL,
  CONSTRAINT fk_draft_media_draft FOREIGN KEY (event_draft_id) REFERENCES event_draft(id),
  CONSTRAINT uk_draft_media_key UNIQUE (event_draft_id, temporary_object_key),
  KEY ix_draft_media_order (event_draft_id, sort_order, id)
);
