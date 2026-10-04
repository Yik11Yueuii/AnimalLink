ALTER TABLE adoption_application
    ADD COLUMN reviewer_user_id varchar(36) NULL,
    ADD COLUMN reviewed_at timestamp(6) NULL,
    ADD COLUMN review_comment varchar(500) NULL,
    ADD KEY ix_adoption_application_status_created_id (status, created_at, id),
    ADD KEY ix_adoption_application_listing_status_created_id (listing_id, status, created_at, id),
    ADD CONSTRAINT chk_adoption_application_review_metadata CHECK (
        (status IN ('SUBMITTED', 'WITHDRAWN') AND reviewer_user_id IS NULL AND reviewed_at IS NULL AND review_comment IS NULL)
        OR (status = 'APPROVED' AND reviewer_user_id IS NOT NULL AND reviewed_at IS NOT NULL)
        OR (status = 'REJECTED' AND reviewer_user_id IS NOT NULL AND reviewed_at IS NOT NULL
            AND review_comment IS NOT NULL AND CHAR_LENGTH(TRIM(review_comment)) > 0)
    );
