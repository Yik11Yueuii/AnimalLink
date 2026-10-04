ALTER TABLE adoption_application
    ADD CONSTRAINT uq_adoption_application_id_listing UNIQUE (id, listing_id);

CREATE TABLE adoption_selection (
    id varchar(36) NOT NULL PRIMARY KEY,
    listing_id varchar(36) NOT NULL,
    application_id varchar(36) NOT NULL,
    status varchar(20) NOT NULL,
    selected_by_user_id varchar(36) NOT NULL,
    selected_at timestamp(6) NOT NULL,
    note varchar(500) NULL,
    cancelled_by_user_id varchar(36) NULL,
    cancelled_at timestamp(6) NULL,
    cancel_reason varchar(500) NULL,
    active_listing_id varchar(36) GENERATED ALWAYS AS (
        CASE WHEN status = 'ACTIVE' THEN listing_id ELSE NULL END
    ) STORED,
    CONSTRAINT chk_adoption_selection_status CHECK (status IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT chk_adoption_selection_cancellation_metadata CHECK (
        (status = 'ACTIVE' AND cancelled_by_user_id IS NULL AND cancelled_at IS NULL AND cancel_reason IS NULL)
        OR (status = 'CANCELLED' AND cancelled_by_user_id IS NOT NULL AND cancelled_at IS NOT NULL
            AND cancel_reason IS NOT NULL AND CHAR_LENGTH(TRIM(cancel_reason)) > 0)
    ),
    CONSTRAINT fk_adoption_selection_listing FOREIGN KEY (listing_id) REFERENCES adoption_listing(id),
    CONSTRAINT fk_adoption_selection_application_listing FOREIGN KEY (application_id, listing_id)
        REFERENCES adoption_application(id, listing_id),
    CONSTRAINT uq_adoption_selection_active_listing UNIQUE (active_listing_id),
    KEY ix_adoption_selection_application_status (application_id, status)
);
