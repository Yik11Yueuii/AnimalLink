CREATE TABLE animal_case (
 id varchar(36) NOT NULL PRIMARY KEY, event_id varchar(36) NOT NULL, campus_id varchar(36) NOT NULL,
 animal_id varchar(36) NULL, owner_user_id varchar(36) NULL, status varchar(32) NOT NULL,
 claim_status varchar(32) NOT NULL, claimed_at timestamp(6) NULL, version int NOT NULL DEFAULT 0,
 created_at timestamp(6) NOT NULL, updated_at timestamp(6) NOT NULL,
 CONSTRAINT fk_case_event FOREIGN KEY (event_id) REFERENCES event(id), UNIQUE KEY uk_case_event(event_id),
 KEY ix_case_campus_status(campus_id,status,updated_at), KEY ix_case_owner_status(owner_user_id,status,updated_at), KEY ix_case_claim_created(claim_status,created_at),
 CONSTRAINT chk_case_status CHECK (status IN ('OPEN','ACTIVE','RESOLVED','CLOSED','CLOSED_UNRESOLVED','CANCELLED')),
 CONSTRAINT chk_case_claim_status CHECK (claim_status IN ('WAITING_CLAIM','CLAIMED')),
 CONSTRAINT chk_case_owner CHECK ((claim_status='WAITING_CLAIM' AND owner_user_id IS NULL) OR (claim_status='CLAIMED' AND owner_user_id IS NOT NULL))
);
