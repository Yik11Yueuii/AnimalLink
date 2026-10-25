ALTER TABLE post
  ADD COLUMN review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' AFTER status,
  ADD COLUMN reviewed_by_user_id CHAR(36) NULL AFTER review_status,
  ADD COLUMN reviewed_at DATETIME(6) NULL AFTER reviewed_by_user_id,
  ADD CONSTRAINT chk_post_review_status
    CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
  ADD CONSTRAINT chk_post_review_lifecycle
    CHECK (
      (review_status = 'PENDING' AND reviewed_by_user_id IS NULL AND reviewed_at IS NULL)
      OR (review_status IN ('APPROVED', 'REJECTED')
          AND reviewed_by_user_id IS NOT NULL AND reviewed_at IS NOT NULL)
    ),
  ADD INDEX idx_post_public_review
    (campus_id, status, visibility, review_status, created_at DESC, id DESC);

ALTER TABLE comment
  ADD COLUMN review_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' AFTER status,
  ADD COLUMN reviewed_by_user_id CHAR(36) NULL AFTER review_status,
  ADD COLUMN reviewed_at DATETIME(6) NULL AFTER reviewed_by_user_id,
  ADD CONSTRAINT chk_comment_review_status
    CHECK (review_status IN ('PENDING', 'APPROVED', 'REJECTED')),
  ADD CONSTRAINT chk_comment_review_lifecycle
    CHECK (
      (review_status = 'PENDING' AND reviewed_by_user_id IS NULL AND reviewed_at IS NULL)
      OR (review_status IN ('APPROVED', 'REJECTED')
          AND reviewed_by_user_id IS NOT NULL AND reviewed_at IS NOT NULL)
    ),
  ADD INDEX idx_comment_public_review
    (post_id, status, review_status, created_at, id);

ALTER TABLE community_governance_audit
  DROP CHECK chk_community_governance_action,
  DROP CHECK chk_community_governance_transition,
  ADD CONSTRAINT chk_community_governance_action
    CHECK (`action` IN ('HIDE', 'RESTORE', 'REMOVE', 'APPROVE', 'REJECT')),
  ADD CONSTRAINT chk_community_governance_transition
    CHECK (
      (`action` = 'HIDE' AND from_state = 'ACTIVE' AND to_state = 'HIDDEN')
      OR (`action` = 'RESTORE' AND from_state = 'HIDDEN' AND to_state = 'ACTIVE')
      OR (`action` = 'REMOVE' AND from_state IN ('ACTIVE', 'HIDDEN') AND to_state = 'REMOVED')
      OR (`action` = 'APPROVE' AND from_state IN ('PENDING', 'REJECTED') AND to_state = 'APPROVED')
      OR (`action` = 'REJECT' AND from_state IN ('PENDING', 'APPROVED') AND to_state = 'REJECTED')
    );

UPDATE timeline_entry te
JOIN post p ON p.id = te.source_id
SET te.visibility = 'RESTRICTED'
WHERE te.source_type = 'POST'
  AND te.entry_type = 'POST_ADOPTION_PUBLISHED'
  AND p.post_type = 'POST_ADOPTION'
  AND p.review_status <> 'APPROVED'
  AND te.visibility = 'PUBLIC';
