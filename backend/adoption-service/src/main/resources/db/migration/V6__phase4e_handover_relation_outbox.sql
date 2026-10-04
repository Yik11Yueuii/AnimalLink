CREATE TABLE adoption_handover (
 id varchar(36) NOT NULL PRIMARY KEY, selection_id varchar(36) NOT NULL, status varchar(16) NOT NULL,
 scheduled_at timestamp(6) NOT NULL, initiated_by_user_id varchar(36) NOT NULL, initiated_at timestamp(6) NOT NULL,
 note varchar(500) NULL, completed_by_user_id varchar(36) NULL, completed_at timestamp(6) NULL,
 cancelled_by_user_id varchar(36) NULL, cancelled_at timestamp(6) NULL, cancel_reason varchar(500) NULL,
 created_at timestamp(6) NOT NULL, updated_at timestamp(6) NOT NULL,
 UNIQUE KEY uq_adoption_handover_selection (selection_id),
 CONSTRAINT fk_adoption_handover_selection FOREIGN KEY (selection_id) REFERENCES adoption_selection(id),
 CONSTRAINT chk_adoption_handover_status CHECK ((status='PENDING' AND completed_by_user_id IS NULL AND completed_at IS NULL AND cancelled_by_user_id IS NULL AND cancelled_at IS NULL AND cancel_reason IS NULL) OR (status='COMPLETED' AND completed_by_user_id IS NOT NULL AND completed_at IS NOT NULL AND cancelled_by_user_id IS NULL AND cancelled_at IS NULL AND cancel_reason IS NULL) OR (status='CANCELLED' AND completed_by_user_id IS NULL AND completed_at IS NULL AND cancelled_by_user_id IS NOT NULL AND cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL AND CHAR_LENGTH(TRIM(cancel_reason))>0))
);
CREATE TABLE adoption_relation (
 id varchar(36) NOT NULL PRIMARY KEY, animal_id varchar(36) NOT NULL, adopter_user_id varchar(36) NOT NULL,
 handover_id varchar(36) NOT NULL, status varchar(16) NOT NULL, activated_at timestamp(6) NOT NULL,
 ended_at timestamp(6) NULL, end_reason varchar(500) NULL, created_at timestamp(6) NOT NULL, updated_at timestamp(6) NOT NULL,
 active_animal_id varchar(36) GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN animal_id ELSE NULL END) STORED,
 UNIQUE KEY uq_adoption_relation_handover (handover_id), UNIQUE KEY uq_adoption_relation_active_animal (active_animal_id),
 CONSTRAINT fk_adoption_relation_handover FOREIGN KEY (handover_id) REFERENCES adoption_handover(id),
 CONSTRAINT chk_adoption_relation_status CHECK ((status='ACTIVE' AND ended_at IS NULL AND end_reason IS NULL) OR (status='ENDED' AND ended_at IS NOT NULL AND end_reason IS NOT NULL AND CHAR_LENGTH(TRIM(end_reason))>0))
);
CREATE TABLE outbox_event (
 id varchar(36) NOT NULL PRIMARY KEY, aggregate_type varchar(64) NOT NULL, aggregate_id varchar(36) NOT NULL,
 event_type varchar(64) NOT NULL, payload_json JSON NOT NULL, status varchar(16) NOT NULL, attempt_count int NOT NULL DEFAULT 0,
 available_at timestamp(6) NOT NULL, published_at timestamp(6) NULL, last_error varchar(1000) NULL,
 created_at timestamp(6) NOT NULL, updated_at timestamp(6) NOT NULL,
 UNIQUE KEY uk_outbox_event_type_aggregate (event_type,aggregate_id), KEY ix_outbox_status_available_created_id (status,available_at,created_at,id),
 CONSTRAINT chk_outbox_status CHECK (status IN ('PENDING','PUBLISHED')), CONSTRAINT chk_outbox_attempt_count CHECK (attempt_count>=0)
);
