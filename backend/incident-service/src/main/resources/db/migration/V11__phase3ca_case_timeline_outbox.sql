CREATE TABLE outbox_event (
 id varchar(36) NOT NULL PRIMARY KEY,
 aggregate_type varchar(64) NOT NULL,
 aggregate_id varchar(36) NOT NULL,
 event_type varchar(64) NOT NULL,
 payload_json JSON NOT NULL,
 status varchar(16) NOT NULL,
 attempt_count int NOT NULL DEFAULT 0,
 available_at timestamp(6) NOT NULL,
 published_at timestamp(6) NULL,
 last_error varchar(1000) NULL,
 created_at timestamp(6) NOT NULL,
 updated_at timestamp(6) NOT NULL,
 UNIQUE KEY uk_outbox_event_type_aggregate (event_type, aggregate_id),
 KEY ix_outbox_status_available_created_id (status, available_at, created_at, id),
 CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','PUBLISHED')),
 CONSTRAINT chk_outbox_attempt_count CHECK (attempt_count >= 0)
);
