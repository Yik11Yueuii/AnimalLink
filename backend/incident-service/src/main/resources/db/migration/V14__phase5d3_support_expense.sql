CREATE TABLE support_expense (
 id varchar(36) NOT NULL PRIMARY KEY,
 campaign_id varchar(36) NOT NULL,
 recorded_by_user_id varchar(36) NOT NULL,
 item varchar(200) NOT NULL,
 description varchar(2000) NOT NULL,
 amount decimal(18,2) NOT NULL,
 status varchar(16) NOT NULL,
 idempotency_key varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 occurred_at timestamp(6) NOT NULL,
 created_at timestamp(6) NOT NULL,
 reviewed_by_user_id varchar(36) NULL,
 review_reason varchar(1000) NULL,
 reviewed_at timestamp(6) NULL,
 voided_by_user_id varchar(36) NULL,
 void_reason varchar(1000) NULL,
 voided_at timestamp(6) NULL,
 replaces_expense_id varchar(36) NULL,
 CONSTRAINT fk_support_expense_campaign FOREIGN KEY (campaign_id) REFERENCES support_campaign(id),
 CONSTRAINT fk_support_expense_replacement FOREIGN KEY (replaces_expense_id) REFERENCES support_expense(id),
 CONSTRAINT chk_support_expense_amount CHECK (amount > 0),
 CONSTRAINT chk_support_expense_status CHECK (status IN ('SUBMITTED','PUBLISHED','REJECTED','VOIDED')),
 CONSTRAINT chk_support_expense_state CHECK (
   (status = 'SUBMITTED' AND reviewed_by_user_id IS NULL AND review_reason IS NULL AND reviewed_at IS NULL AND voided_by_user_id IS NULL AND void_reason IS NULL AND voided_at IS NULL)
   OR
   (status = 'PUBLISHED' AND reviewed_by_user_id IS NOT NULL AND reviewed_at IS NOT NULL AND voided_by_user_id IS NULL AND void_reason IS NULL AND voided_at IS NULL)
   OR
   (status = 'REJECTED' AND reviewed_by_user_id IS NOT NULL AND review_reason IS NOT NULL AND CHAR_LENGTH(TRIM(review_reason)) > 0 AND reviewed_at IS NOT NULL AND voided_by_user_id IS NULL AND void_reason IS NULL AND voided_at IS NULL)
   OR
   (status = 'VOIDED' AND reviewed_by_user_id IS NOT NULL AND reviewed_at IS NOT NULL AND voided_by_user_id IS NOT NULL AND void_reason IS NOT NULL AND CHAR_LENGTH(TRIM(void_reason)) > 0 AND voided_at IS NOT NULL)
 ),
 CONSTRAINT chk_support_expense_not_self_replacement CHECK (replaces_expense_id IS NULL OR replaces_expense_id <> id),
 UNIQUE KEY uk_support_expense_idempotency (campaign_id, recorded_by_user_id, idempotency_key),
 UNIQUE KEY uk_support_expense_replacement (replaces_expense_id),
 KEY ix_support_expense_campaign_status_occurred (campaign_id, status, occurred_at, id),
 KEY ix_support_expense_submitter_created (recorded_by_user_id, created_at, id),
 KEY ix_support_expense_replaces (replaces_expense_id)
);

CREATE TABLE support_expense_media (
 id varchar(36) NOT NULL PRIMARY KEY,
 expense_id varchar(36) NOT NULL,
 object_key varchar(512) NOT NULL,
 content_type varchar(128) NOT NULL,
 size_bytes bigint NOT NULL,
 sort_order int NOT NULL,
 created_at timestamp(6) NOT NULL,
 CONSTRAINT fk_support_expense_media_expense FOREIGN KEY (expense_id) REFERENCES support_expense(id),
 CONSTRAINT chk_support_expense_media_type CHECK (content_type IN ('image/jpeg','image/png','image/webp')),
 CONSTRAINT chk_support_expense_media_size CHECK (size_bytes > 0 AND size_bytes <= 10485760),
 CONSTRAINT chk_support_expense_media_order CHECK (sort_order >= 0 AND sort_order <= 5),
 UNIQUE KEY uk_support_expense_media_object (expense_id, object_key),
 UNIQUE KEY uk_support_expense_media_order (expense_id, sort_order),
 KEY ix_support_expense_media_ordered (expense_id, sort_order, id)
);
