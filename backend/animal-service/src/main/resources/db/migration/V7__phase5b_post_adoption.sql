ALTER TABLE post
  ADD COLUMN relation_id CHAR(36) NULL AFTER animal_id,
  ADD KEY ix_post_relation_created (relation_id, created_at DESC, id DESC),
  ADD CONSTRAINT chk_post_adoption_relation
    CHECK (
      (post_type = 'POST_ADOPTION' AND relation_id IS NOT NULL)
      OR
      (post_type <> 'POST_ADOPTION' AND relation_id IS NULL)
    );
