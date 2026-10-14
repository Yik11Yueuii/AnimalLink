CREATE TABLE community_governance_audit (
  id CHAR(36) NOT NULL,
  resource_type VARCHAR(16) NOT NULL,
  resource_id CHAR(36) NOT NULL,
  `action` VARCHAR(16) NOT NULL,
  from_state VARCHAR(16) NOT NULL,
  to_state VARCHAR(16) NOT NULL,
  actor_user_id CHAR(36) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  KEY idx_community_governance_audit_resource_created
    (resource_type, resource_id, created_at DESC, id DESC),
  CONSTRAINT chk_community_governance_resource_type
    CHECK (resource_type IN ('POST', 'COMMENT')),
  CONSTRAINT chk_community_governance_action
    CHECK (`action` IN ('HIDE', 'RESTORE')),
  CONSTRAINT chk_community_governance_transition
    CHECK (
      (`action` = 'HIDE' AND from_state = 'ACTIVE' AND to_state = 'HIDDEN')
      OR
      (`action` = 'RESTORE' AND from_state = 'HIDDEN' AND to_state = 'ACTIVE')
    )
) ENGINE=InnoDB;

CREATE INDEX idx_post_governance_status_created
  ON post (status, created_at DESC, id DESC);

CREATE INDEX idx_comment_governance_status_created
  ON comment (status, created_at DESC, id DESC);

UPDATE timeline_entry te
JOIN post p
  ON p.id = te.source_id
SET te.visibility = 'RESTRICTED'
WHERE p.post_type = 'POST_ADOPTION'
  AND p.status = 'HIDDEN'
  AND te.source_type = 'POST'
  AND te.entry_type = 'POST_ADOPTION_PUBLISHED'
  AND te.visibility = 'PUBLIC';
