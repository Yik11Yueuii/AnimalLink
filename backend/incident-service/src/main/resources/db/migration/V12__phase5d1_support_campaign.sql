CREATE TABLE support_campaign (
 id varchar(36) NOT NULL PRIMARY KEY,
 case_id varchar(36) NOT NULL,
 status varchar(24) NOT NULL,
 target_amount decimal(18,2) NOT NULL,
 currency char(3) NOT NULL DEFAULT 'CNY',
 purpose varchar(1000) NOT NULL,
 application_note varchar(2000) NOT NULL,
 case_evidence_id varchar(36) NULL,
 requested_by_user_id varchar(36) NOT NULL,
 reviewed_by_user_id varchar(36) NULL,
 review_reason varchar(1000) NULL,
 published_at timestamp(6) NULL,
 completed_at timestamp(6) NULL,
 version int NOT NULL DEFAULT 0,
 created_at timestamp(6) NOT NULL,
 updated_at timestamp(6) NOT NULL,
 ongoing_case_id varchar(36) GENERATED ALWAYS AS (
   CASE WHEN status IN ('DRAFT','PENDING_REVIEW','APPROVED','ACTIVE','COMPLETED') THEN case_id ELSE NULL END
 ) STORED,
 CONSTRAINT fk_support_campaign_case FOREIGN KEY (case_id) REFERENCES animal_case(id),
 CONSTRAINT fk_support_campaign_evidence FOREIGN KEY (case_evidence_id) REFERENCES evidence(id),
 CONSTRAINT chk_support_campaign_status CHECK (status IN ('DRAFT','PENDING_REVIEW','APPROVED','ACTIVE','COMPLETED','SETTLED','REJECTED','CANCELLED')),
 CONSTRAINT chk_support_campaign_amount CHECK (target_amount > 0),
 CONSTRAINT chk_support_campaign_currency CHECK (currency = 'CNY'),
 UNIQUE KEY uk_support_campaign_ongoing_case (ongoing_case_id),
 KEY ix_support_campaign_case_created (case_id, created_at DESC, id DESC),
 KEY ix_support_campaign_status_created (status, created_at DESC, id DESC),
 KEY ix_support_campaign_requester_case (requested_by_user_id, case_id, created_at DESC)
);

CREATE TABLE support_audit_log (
 id varchar(36) NOT NULL PRIMARY KEY,
 campaign_id varchar(36) NOT NULL,
 resource_type varchar(24) NOT NULL,
 resource_id varchar(36) NOT NULL,
 action varchar(32) NOT NULL,
 from_state varchar(24) NULL,
 to_state varchar(24) NULL,
 actor_user_id varchar(36) NOT NULL,
 reason varchar(1000) NULL,
 created_at timestamp(6) NOT NULL,
 CONSTRAINT fk_support_audit_campaign FOREIGN KEY (campaign_id) REFERENCES support_campaign(id),
 KEY ix_support_audit_campaign_created (campaign_id, created_at, id)
);
