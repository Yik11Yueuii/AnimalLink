CREATE TABLE external_identity (
  id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  provider_tenant VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT '',
  provider_subject VARCHAR(191) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_external_identity_id_provider (id, provider),
  UNIQUE KEY uk_external_identity_subject (provider, provider_tenant, provider_subject),
  UNIQUE KEY uk_external_identity_user_provider (user_id, provider, provider_tenant),
  KEY idx_external_identity_user (user_id),
  CONSTRAINT fk_external_identity_user FOREIGN KEY (user_id) REFERENCES `user` (id),
  CONSTRAINT chk_external_identity_provider
    CHECK (provider IN ('ADMIN_PASSWORD', 'WECHAT_MINIPROGRAM')),
  CONSTRAINT chk_external_identity_subject CHECK (CHAR_LENGTH(provider_subject) BETWEEN 1 AND 191),
  CONSTRAINT chk_admin_password_identity_canonical CHECK (
    provider <> 'ADMIN_PASSWORD'
    OR (provider_tenant = ''
      AND REGEXP_LIKE(provider_subject, '^[a-z0-9][a-z0-9._-]{2,63}$', 'c'))
  )
) ENGINE=InnoDB;

CREATE TABLE admin_password_credential (
  external_identity_id CHAR(36) NOT NULL,
  identity_provider VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'ADMIN_PASSWORD',
  password_hash VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  password_algorithm VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  credential_version INT NOT NULL DEFAULT 1,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (external_identity_id),
  CONSTRAINT fk_admin_credential_identity
    FOREIGN KEY (external_identity_id, identity_provider) REFERENCES external_identity (id, provider),
  CONSTRAINT chk_admin_credential_provider CHECK (identity_provider = 'ADMIN_PASSWORD'),
  CONSTRAINT chk_admin_credential_algorithm CHECK (password_algorithm IN ('ARGON2ID_V1')),
  CONSTRAINT chk_admin_credential_version CHECK (credential_version >= 1),
  CONSTRAINT chk_admin_credential_hash CHECK (CHAR_LENGTH(password_hash) BETWEEN 20 AND 255)
) ENGINE=InnoDB;

CREATE TABLE admin_session (
  id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  client_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  token_hash BINARY(32) NOT NULL,
  credential_version INT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  last_seen_at DATETIME(6) NOT NULL,
  idle_expires_at DATETIME(6) NOT NULL,
  absolute_expires_at DATETIME(6) NOT NULL,
  revoked_at DATETIME(6) NULL,
  revoke_reason VARCHAR(64) NULL,
  version INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_admin_session_token_hash (token_hash),
  KEY idx_admin_session_user_state (user_id, revoked_at, absolute_expires_at),
  KEY idx_admin_session_expiry (idle_expires_at, absolute_expires_at),
  CONSTRAINT fk_admin_session_user FOREIGN KEY (user_id) REFERENCES `user` (id),
  CONSTRAINT chk_admin_session_client CHECK (client_type IN ('ADMIN_WEB')),
  CONSTRAINT chk_admin_session_credential_version CHECK (credential_version >= 1),
  CONSTRAINT chk_admin_session_expiry
    CHECK (created_at <= last_seen_at AND last_seen_at < idle_expires_at
      AND idle_expires_at <= absolute_expires_at),
  CONSTRAINT chk_admin_session_revocation
    CHECK ((revoked_at IS NULL AND revoke_reason IS NULL)
      OR (revoked_at IS NOT NULL AND revoke_reason IS NOT NULL)),
  CONSTRAINT chk_admin_session_version CHECK (version >= 0)
) ENGINE=InnoDB;
