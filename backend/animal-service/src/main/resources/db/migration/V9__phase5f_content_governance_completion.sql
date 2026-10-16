ALTER TABLE post
  DROP CHECK chk_post_status,
  ADD CONSTRAINT chk_post_status
    CHECK (status IN ('ACTIVE', 'HIDDEN', 'REMOVED'));

ALTER TABLE comment
  DROP CHECK chk_comment_status,
  ADD CONSTRAINT chk_comment_status
    CHECK (status IN ('ACTIVE', 'HIDDEN', 'REMOVED'));

ALTER TABLE community_governance_audit
  DROP CHECK chk_community_governance_action,
  DROP CHECK chk_community_governance_transition,
  ADD CONSTRAINT chk_community_governance_action
    CHECK (`action` IN ('HIDE', 'RESTORE', 'REMOVE')),
  ADD CONSTRAINT chk_community_governance_transition
    CHECK (
      (`action` = 'HIDE' AND from_state = 'ACTIVE' AND to_state = 'HIDDEN')
      OR
      (`action` = 'RESTORE' AND from_state = 'HIDDEN' AND to_state = 'ACTIVE')
      OR
      (`action` = 'REMOVE' AND from_state IN ('ACTIVE', 'HIDDEN')
                           AND to_state = 'REMOVED')
    );

CREATE TABLE community_content_report (
  id CHAR(36) NOT NULL,
  resource_type VARCHAR(16) NOT NULL,
  resource_id CHAR(36) NOT NULL,
  reporter_user_id CHAR(36) NOT NULL,
  description VARCHAR(1000) NOT NULL,
  status VARCHAR(16) NOT NULL,
  resolution_action VARCHAR(16) NULL,
  resolution_note VARCHAR(1000) NULL,
  reviewed_by_user_id CHAR(36) NULL,
  reviewed_at DATETIME(6) NULL,
  version INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
    ON UPDATE CURRENT_TIMESTAMP(6),
  open_marker TINYINT
    GENERATED ALWAYS AS (CASE WHEN status = 'OPEN' THEN 1 ELSE NULL END) STORED,
  PRIMARY KEY (id),
  UNIQUE KEY uk_content_report_open_reporter_resource
    (reporter_user_id, resource_type, resource_id, open_marker),
  KEY idx_content_report_status_created (status, created_at DESC, id DESC),
  KEY idx_content_report_resource_created
    (resource_type, resource_id, created_at DESC, id DESC),
  CONSTRAINT chk_content_report_resource_type
    CHECK (resource_type IN ('POST', 'COMMENT')),
  CONSTRAINT chk_content_report_status
    CHECK (status IN ('OPEN', 'RESOLVED', 'DISMISSED')),
  CONSTRAINT chk_content_report_resolution_action
    CHECK (resolution_action IS NULL OR resolution_action IN ('NONE', 'HIDE', 'REMOVE')),
  CONSTRAINT chk_content_report_version CHECK (version >= 0),
  CONSTRAINT chk_content_report_description
    CHECK (CHAR_LENGTH(TRIM(description)) BETWEEN 1 AND 1000),
  CONSTRAINT chk_content_report_lifecycle
    CHECK (
      (status = 'OPEN'
        AND resolution_action IS NULL
        AND resolution_note IS NULL
        AND reviewed_by_user_id IS NULL
        AND reviewed_at IS NULL)
      OR
      (status = 'RESOLVED'
        AND resolution_action IS NOT NULL
        AND resolution_action IN ('NONE', 'HIDE', 'REMOVE')
        AND resolution_note IS NOT NULL
        AND CHAR_LENGTH(TRIM(resolution_note)) BETWEEN 1 AND 1000
        AND reviewed_by_user_id IS NOT NULL
        AND reviewed_at IS NOT NULL)
      OR
      (status = 'DISMISSED'
        AND resolution_action IS NULL
        AND resolution_note IS NOT NULL
        AND CHAR_LENGTH(TRIM(resolution_note)) BETWEEN 1 AND 1000
        AND reviewed_by_user_id IS NOT NULL
        AND reviewed_at IS NOT NULL)
    )
) ENGINE=InnoDB;
