CREATE TABLE wechat_user_session (
  id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  token_hash BINARY(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  last_seen_at DATETIME(6) NOT NULL,
  idle_expires_at DATETIME(6) NOT NULL,
  absolute_expires_at DATETIME(6) NOT NULL,
  revoked_at DATETIME(6) NULL,
  revoke_reason VARCHAR(64) NULL,
  version INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_wechat_user_session_token_hash (token_hash),
  KEY idx_wechat_user_session_user_state (user_id, revoked_at, absolute_expires_at),
  KEY idx_wechat_user_session_expiry (idle_expires_at, absolute_expires_at),
  CONSTRAINT fk_wechat_user_session_user FOREIGN KEY (user_id) REFERENCES `user` (id),
  CONSTRAINT chk_wechat_user_session_expiry CHECK (
    created_at <= last_seen_at AND last_seen_at < idle_expires_at
    AND idle_expires_at <= absolute_expires_at),
  CONSTRAINT chk_wechat_user_session_revocation CHECK (
    (revoked_at IS NULL AND revoke_reason IS NULL)
    OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)),
  CONSTRAINT chk_wechat_user_session_version CHECK (version >= 0)
) ENGINE=InnoDB;
