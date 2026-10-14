CREATE TABLE support_settlement (
 id varchar(36) NOT NULL PRIMARY KEY,
 campaign_id varchar(36) NOT NULL,
 total_contributed decimal(20,2) NOT NULL,
 total_expenses decimal(20,2) NOT NULL,
 net_balance decimal(20,2) NOT NULL,
 summary varchar(2000) NOT NULL,
 disposition_note varchar(2000) NOT NULL,
 settled_by_user_id varchar(36) NOT NULL,
 idempotency_key varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 request_hash char(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at timestamp(6) NOT NULL,
 CONSTRAINT fk_support_settlement_campaign FOREIGN KEY (campaign_id) REFERENCES support_campaign(id),
 CONSTRAINT chk_support_settlement_contributed CHECK (total_contributed >= 0),
 CONSTRAINT chk_support_settlement_expenses CHECK (total_expenses >= 0),
 CONSTRAINT chk_support_settlement_balance CHECK (net_balance = total_contributed - total_expenses),
 UNIQUE KEY uk_support_settlement_campaign (campaign_id)
);
