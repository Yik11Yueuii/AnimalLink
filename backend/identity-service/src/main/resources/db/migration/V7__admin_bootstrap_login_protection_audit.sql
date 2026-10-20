CREATE TABLE admin_bootstrap_control (
  singleton_id TINYINT NOT NULL,
  status VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  completed_user_id CHAR(36) NULL,
  completed_at DATETIME(6) NULL,
  version INT NOT NULL DEFAULT 0,
  PRIMARY KEY (singleton_id),
  CONSTRAINT fk_admin_bootstrap_user FOREIGN KEY (completed_user_id) REFERENCES `user` (id),
  CONSTRAINT chk_admin_bootstrap_singleton CHECK (singleton_id = 1),
  CONSTRAINT chk_admin_bootstrap_state CHECK (
    (status = 'OPEN' AND completed_user_id IS NULL AND completed_at IS NULL)
    OR (status = 'COMPLETED' AND completed_user_id IS NOT NULL AND completed_at IS NOT NULL)
  ),
  CONSTRAINT chk_admin_bootstrap_version CHECK (version >= 0)
) ENGINE=InnoDB;

INSERT INTO admin_bootstrap_control (singleton_id, status) VALUES (1, 'OPEN');

CREATE TABLE admin_login_attempt_state (
  scope_type VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  scope_key BINARY(32) NOT NULL,
  normalized_username VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  failure_count INT NOT NULL,
  in_flight_count INT NOT NULL,
  window_started_at DATETIME(6) NOT NULL,
  blocked_until DATETIME(6) NULL,
  last_source_fingerprint BINARY(32) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  version INT NOT NULL DEFAULT 0,
  PRIMARY KEY (scope_type, scope_key),
  KEY idx_admin_login_attempt_expiry (blocked_until, updated_at),
  CONSTRAINT chk_admin_login_failure_count CHECK (failure_count >= 0),
  CONSTRAINT chk_admin_login_in_flight_count CHECK (in_flight_count >= 0),
  CONSTRAINT chk_admin_login_attempt_scope CHECK (scope_type IN ('USERNAME', 'SOURCE')),
  CONSTRAINT chk_admin_login_attempt_version CHECK (version >= 0)
) ENGINE=InnoDB;

CREATE TABLE authentication_audit_event (
  id CHAR(36) NOT NULL,
  event_type VARCHAR(48) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  outcome VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  subject_user_id CHAR(36) NULL,
  normalized_username VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  actor_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  actor_reference VARCHAR(128) NULL,
  source_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
  reason_code VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  session_id CHAR(36) NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  KEY idx_auth_audit_created (created_at, id),
  KEY idx_auth_audit_subject (subject_user_id, created_at, id),
  KEY idx_auth_audit_username (normalized_username, created_at, id),
  KEY idx_auth_audit_event (event_type, outcome, created_at),
  CONSTRAINT chk_auth_audit_event CHECK (event_type IN (
    'ADMIN_INITIALIZATION_SUCCESS', 'ADMIN_INITIALIZATION_DENIED',
    'ADMIN_LOGIN_SUCCESS', 'ADMIN_LOGIN_FAILURE', 'ADMIN_LOGIN_RATE_LIMITED',
    'SESSION_REVOKED', 'ALL_SESSIONS_REVOKED', 'CREDENTIAL_UPGRADED'
  )),
  CONSTRAINT chk_auth_audit_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED')),
  CONSTRAINT chk_auth_audit_actor CHECK (actor_type IN (
    'LOCAL_OPERATOR', 'ADMIN_CREDENTIAL', 'ADMIN_SESSION', 'SYSTEM'
  )),
  CONSTRAINT chk_auth_audit_reason CHECK (CHAR_LENGTH(reason_code) BETWEEN 1 AND 64),
  CONSTRAINT chk_auth_audit_source CHECK (
    source_fingerprint IS NULL OR source_fingerprint REGEXP '^[0-9a-f]{64}$'
  )
) ENGINE=InnoDB;
