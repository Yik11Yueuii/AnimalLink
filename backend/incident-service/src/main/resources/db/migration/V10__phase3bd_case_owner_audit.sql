CREATE TABLE case_owner_audit_log (
 id varchar(36) NOT NULL PRIMARY KEY,
 case_id varchar(36) NOT NULL,
 previous_owner_user_id varchar(36) NOT NULL,
 new_owner_user_id varchar(36) NOT NULL,
 changed_by_user_id varchar(36) NOT NULL,
 change_type varchar(32) NOT NULL,
 reason varchar(1000) NOT NULL,
 created_at timestamp(6) NOT NULL,
 CONSTRAINT fk_case_owner_audit_case FOREIGN KEY (case_id) REFERENCES animal_case(id),
 KEY ix_case_owner_audit_case_created_id(case_id,created_at,id),
 CONSTRAINT chk_case_owner_audit_type CHECK (change_type IN ('OWNER_TRANSFER','GOVERNANCE_ADJUSTMENT'))
);
