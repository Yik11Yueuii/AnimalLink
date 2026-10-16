CREATE TABLE user_governance_audit (
  id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  action VARCHAR(16) NOT NULL,
  from_status VARCHAR(16) NOT NULL,
  to_status VARCHAR(16) NOT NULL,
  actor_user_id CHAR(36) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_user_governance_history (user_id, created_at DESC, id DESC),
  CONSTRAINT fk_user_governance_target
    FOREIGN KEY (user_id) REFERENCES `user` (id),
  CONSTRAINT fk_user_governance_actor
    FOREIGN KEY (actor_user_id) REFERENCES `user` (id),
  CONSTRAINT chk_user_governance_action
    CHECK (action IN ('RESTRICT', 'UNRESTRICT')),
  CONSTRAINT chk_user_governance_transition
    CHECK ((action = 'RESTRICT' AND from_status = 'ACTIVE' AND to_status = 'SUSPENDED')
        OR (action = 'UNRESTRICT' AND from_status = 'SUSPENDED' AND to_status = 'ACTIVE')),
  CONSTRAINT chk_user_governance_reason
    CHECK (CHAR_LENGTH(TRIM(reason)) BETWEEN 1 AND 1000)
) ENGINE=InnoDB;
