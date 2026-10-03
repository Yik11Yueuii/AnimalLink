CREATE TABLE case_participant (
 id varchar(36) NOT NULL PRIMARY KEY,
 case_id varchar(36) NOT NULL,
 user_id varchar(36) NOT NULL,
 status varchar(32) NOT NULL,
 invited_by_user_id varchar(36) NOT NULL,
 invited_at timestamp(6) NOT NULL,
 joined_at timestamp(6) NULL,
 ended_at timestamp(6) NULL,
 version int NOT NULL DEFAULT 0,
 created_at timestamp(6) NOT NULL,
 updated_at timestamp(6) NOT NULL,
 CONSTRAINT fk_case_participant_case FOREIGN KEY (case_id) REFERENCES animal_case(id),
 CONSTRAINT uk_case_participant_user UNIQUE (case_id,user_id),
 KEY ix_case_participant_case_status_created(case_id,status,created_at),
 KEY ix_case_participant_user_status_updated(user_id,status,updated_at),
 CONSTRAINT chk_case_participant_status CHECK (status IN ('INVITED','ACTIVE','DECLINED','LEFT','REMOVED')),
 CONSTRAINT chk_case_participant_timestamps CHECK ((status='INVITED' AND joined_at IS NULL AND ended_at IS NULL) OR (status='ACTIVE' AND joined_at IS NOT NULL AND ended_at IS NULL) OR (status IN ('DECLINED','LEFT','REMOVED') AND ended_at IS NOT NULL))
);
