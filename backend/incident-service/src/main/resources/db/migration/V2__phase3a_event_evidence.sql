CREATE TABLE event_draft (
  id varchar(36) NOT NULL PRIMARY KEY, user_id varchar(36) NOT NULL, campus_id varchar(36) NOT NULL, animal_id varchar(36) NULL,
  description varchar(2000) NOT NULL, abnormality_summary varchar(1000) NULL, occurred_at timestamp(6) NOT NULL, public_location_description varchar(500) NOT NULL,
  exact_location_description varchar(1000) NULL, latitude decimal(10,7) NULL, longitude decimal(10,7) NULL,
  source_ai_task_id varchar(36) NULL, source_matching_record_id varchar(36) NULL, source_post_id varchar(36) NULL,
  finalized_at timestamp(6) NULL, final_event_id varchar(36) NULL, created_at timestamp(6) NOT NULL, updated_at timestamp(6) NOT NULL,
  KEY ix_event_draft_owner (user_id, campus_id, created_at DESC)
);
CREATE TABLE event (
  id varchar(36) NOT NULL PRIMARY KEY, source_draft_id varchar(36) NOT NULL, reporter_user_id varchar(36) NOT NULL, campus_id varchar(36) NOT NULL,
  animal_id varchar(36) NULL, description varchar(2000) NOT NULL, abnormality_summary varchar(1000) NULL, occurred_at timestamp(6) NOT NULL,
  reported_at timestamp(6) NOT NULL, public_location_description varchar(500) NOT NULL, exact_location_description varchar(1000) NULL,
  latitude decimal(10,7) NULL, longitude decimal(10,7) NULL, status varchar(24) NOT NULL, duplicate_of_event_id varchar(36) NULL,
  created_at timestamp(6) NOT NULL, updated_at timestamp(6) NOT NULL, UNIQUE KEY uk_event_source_draft (source_draft_id),
  KEY ix_event_list (campus_id, status, reported_at DESC, id DESC), KEY ix_event_animal (campus_id, animal_id, reported_at DESC)
);
CREATE TABLE evidence (id varchar(36) NOT NULL PRIMARY KEY, event_id varchar(36) NOT NULL, submitter_user_id varchar(36) NOT NULL, description varchar(2000) NOT NULL, occurred_at timestamp(6) NOT NULL, public_location_description varchar(500) NULL, exact_location_description varchar(1000) NULL, latitude decimal(10,7) NULL, longitude decimal(10,7) NULL, created_at timestamp(6) NOT NULL, KEY ix_evidence_event (event_id, created_at ASC, id ASC));
CREATE TABLE event_media (id varchar(36) NOT NULL PRIMARY KEY, event_id varchar(36) NOT NULL, object_key varchar(512) NOT NULL, content_type varchar(128) NOT NULL, size_bytes bigint NOT NULL, sort_order int NOT NULL, created_at timestamp(6) NOT NULL, KEY ix_event_media_event (event_id, sort_order, id));
CREATE TABLE evidence_media (id varchar(36) NOT NULL PRIMARY KEY, evidence_id varchar(36) NOT NULL, object_key varchar(512) NOT NULL, content_type varchar(128) NOT NULL, size_bytes bigint NOT NULL, sort_order int NOT NULL, created_at timestamp(6) NOT NULL, KEY ix_evidence_media_evidence (evidence_id, sort_order, id));
