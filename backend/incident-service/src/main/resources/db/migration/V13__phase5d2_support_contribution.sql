CREATE TABLE support_contribution (
 id varchar(36) NOT NULL PRIMARY KEY,
 campaign_id varchar(36) NOT NULL,
 contributor_user_id varchar(36) NOT NULL,
 amount decimal(18,2) NOT NULL,
 status varchar(16) NOT NULL,
 idempotency_key varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 recorded_at timestamp(6) NOT NULL,
 voided_by_user_id varchar(36) NULL,
 void_reason varchar(1000) NULL,
 voided_at timestamp(6) NULL,
 CONSTRAINT fk_support_contribution_campaign FOREIGN KEY (campaign_id) REFERENCES support_campaign(id),
 CONSTRAINT chk_support_contribution_amount CHECK (amount > 0),
 CONSTRAINT chk_support_contribution_status CHECK (status IN ('RECORDED','VOIDED')),
 CONSTRAINT chk_support_contribution_void CHECK (
   (status = 'RECORDED' AND voided_by_user_id IS NULL AND void_reason IS NULL AND voided_at IS NULL)
   OR
   (status = 'VOIDED' AND voided_by_user_id IS NOT NULL AND void_reason IS NOT NULL AND CHAR_LENGTH(TRIM(void_reason)) > 0 AND voided_at IS NOT NULL)
 ),
 UNIQUE KEY uk_support_contribution_idempotency (campaign_id, contributor_user_id, idempotency_key),
 KEY ix_support_contribution_campaign_status_recorded (campaign_id, status, recorded_at, id),
 KEY ix_support_contribution_contributor_recorded (contributor_user_id, recorded_at, id)
);
