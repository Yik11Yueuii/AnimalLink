CREATE TABLE `user` (
  id CHAR(36) NOT NULL,
  display_name VARCHAR(80) NOT NULL,
  account_status VARCHAR(32) NOT NULL,
  system_role VARCHAR(32) NOT NULL DEFAULT 'USER',
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  CONSTRAINT chk_user_account_status CHECK (account_status IN ('ACTIVE', 'SUSPENDED', 'DISABLED')),
  CONSTRAINT chk_user_system_role CHECK (system_role IN ('USER', 'GOVERNANCE_ADMIN'))
) ENGINE=InnoDB;

CREATE TABLE campus (
  id CHAR(36) NOT NULL,
  name VARCHAR(120) NOT NULL,
  short_name VARCHAR(60) NULL,
  city VARCHAR(80) NOT NULL,
  region VARCHAR(80) NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_campus_name_city (name, city),
  KEY idx_campus_status_name (status, name),
  CONSTRAINT chk_campus_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
) ENGINE=InnoDB;

CREATE TABLE campus_verification (
  id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  campus_id CHAR(36) NOT NULL,
  requested_membership_type VARCHAR(32) NOT NULL,
  applicant_name VARCHAR(80) NOT NULL,
  affiliation_note VARCHAR(255) NULL,
  expected_graduation_date DATE NULL,
  material_media_id CHAR(36) NULL,
  status VARCHAR(32) NOT NULL,
  review_reason VARCHAR(500) NULL,
  reviewed_by CHAR(36) NULL,
  reviewed_at DATETIME(6) NULL,
  version INT NOT NULL DEFAULT 0,
  pending_key VARCHAR(73) GENERATED ALWAYS AS (
    CASE WHEN status = 'PENDING_REVIEW' THEN CONCAT(user_id, ':', campus_id) ELSE NULL END
  ) STORED,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_verification_pending (pending_key),
  KEY idx_verification_user_created (user_id, created_at),
  KEY idx_verification_status_created (status, created_at),
  KEY idx_verification_campus_status (campus_id, status),
  CONSTRAINT fk_verification_user FOREIGN KEY (user_id) REFERENCES `user` (id),
  CONSTRAINT fk_verification_campus FOREIGN KEY (campus_id) REFERENCES campus (id),
  CONSTRAINT fk_verification_reviewer FOREIGN KEY (reviewed_by) REFERENCES `user` (id),
  CONSTRAINT chk_verification_membership_type CHECK (requested_membership_type IN ('STUDENT', 'ALUMNI')),
  CONSTRAINT chk_verification_status CHECK (status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED'))
) ENGINE=InnoDB;

CREATE TABLE campus_membership (
  id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  campus_id CHAR(36) NOT NULL,
  membership_type VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  expected_graduation_date DATE NULL,
  approved_at DATETIME(6) NOT NULL,
  last_verification_id CHAR(36) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_membership_user_campus (user_id, campus_id),
  UNIQUE KEY uk_membership_verification (last_verification_id),
  KEY idx_membership_user_status (user_id, status),
  KEY idx_membership_campus_status (campus_id, status),
  CONSTRAINT fk_membership_user FOREIGN KEY (user_id) REFERENCES `user` (id),
  CONSTRAINT fk_membership_campus FOREIGN KEY (campus_id) REFERENCES campus (id),
  CONSTRAINT fk_membership_verification FOREIGN KEY (last_verification_id) REFERENCES campus_verification (id),
  CONSTRAINT chk_membership_type CHECK (membership_type IN ('STUDENT', 'ALUMNI')),
  CONSTRAINT chk_membership_status CHECK (status IN ('ACTIVE', 'REVERIFY_REQUIRED', 'ENDED'))
) ENGINE=InnoDB;
