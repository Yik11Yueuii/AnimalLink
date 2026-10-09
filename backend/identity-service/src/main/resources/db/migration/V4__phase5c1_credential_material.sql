CREATE TABLE credential_material (
  id CHAR(36) NOT NULL,
  owner_user_id CHAR(36) NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  content_type VARCHAR(128) NULL,
  size_bytes BIGINT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  uploaded_at DATETIME(6) NULL,
  attached_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_credential_material_object_key (object_key),
  KEY ix_credential_material_owner_status_created (owner_user_id, status, created_at),
  CONSTRAINT fk_credential_material_owner FOREIGN KEY (owner_user_id) REFERENCES `user` (id),
  CONSTRAINT chk_credential_material_status CHECK (status IN ('PENDING_UPLOAD', 'READY', 'ATTACHED', 'INVALID')),
  CONSTRAINT chk_credential_material_size CHECK (size_bytes IS NULL OR size_bytes > 0)
) ENGINE=InnoDB;

ALTER TABLE campus_verification
  ADD CONSTRAINT fk_verification_material FOREIGN KEY (material_media_id) REFERENCES credential_material (id),
  ADD UNIQUE KEY uk_verification_material (material_media_id);

CREATE TABLE credential_precheck_attempt (
  id CHAR(36) NOT NULL,
  verification_id CHAR(36) NOT NULL,
  attempt_no INT NOT NULL,
  intelligence_task_id CHAR(36) NULL,
  status VARCHAR(32) NOT NULL,
  provider VARCHAR(64) NULL,
  model_name VARCHAR(128) NULL,
  overall_confidence DECIMAL(4,3) NULL,
  extracted_school_name VARCHAR(160) NULL,
  extracted_person_name VARCHAR(80) NULL,
  credential_type VARCHAR(64) NULL,
  consistency_flags JSON NULL,
  summary VARCHAR(1000) NULL,
  error_category VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  started_at DATETIME(6) NULL,
  completed_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_credential_precheck_attempt_number (verification_id, attempt_no),
  UNIQUE KEY uk_credential_precheck_intelligence_task (intelligence_task_id),
  KEY ix_credential_precheck_status_created (status, created_at),
  CONSTRAINT fk_credential_precheck_verification FOREIGN KEY (verification_id) REFERENCES campus_verification (id),
  CONSTRAINT chk_credential_precheck_attempt_no CHECK (attempt_no > 0),
  CONSTRAINT chk_credential_precheck_status CHECK (status IN ('PENDING', 'PROCESSING', 'PASSED', 'MANUAL_REVIEW_REQUIRED', 'UNAVAILABLE')),
  CONSTRAINT chk_credential_precheck_confidence CHECK (overall_confidence IS NULL OR (overall_confidence >= 0 AND overall_confidence <= 1))
) ENGINE=InnoDB;
