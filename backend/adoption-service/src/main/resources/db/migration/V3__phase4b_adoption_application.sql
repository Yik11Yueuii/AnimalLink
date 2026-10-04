CREATE TABLE adoption_application (
    id varchar(36) NOT NULL PRIMARY KEY,
    listing_id varchar(36) NOT NULL,
    applicant_user_id varchar(36) NOT NULL,
    status varchar(20) NOT NULL,
    message varchar(2000) NOT NULL,
    created_at timestamp(6) NOT NULL,
    updated_at timestamp(6) NOT NULL,
    withdrawn_at timestamp(6) NULL,
    active_listing_applicant varchar(73) GENERATED ALWAYS AS (
        CASE WHEN status IN ('SUBMITTED', 'APPROVED') THEN CONCAT(listing_id, ':', applicant_user_id) ELSE NULL END
    ) STORED,
    CONSTRAINT chk_adoption_application_status CHECK (status IN ('SUBMITTED', 'WITHDRAWN', 'APPROVED', 'REJECTED')),
    CONSTRAINT fk_adoption_application_listing FOREIGN KEY (listing_id) REFERENCES adoption_listing(id),
    CONSTRAINT uq_adoption_application_active UNIQUE (active_listing_applicant),
    KEY ix_adoption_application_listing (listing_id),
    KEY ix_adoption_application_applicant_created (applicant_user_id, created_at, id),
    KEY ix_adoption_application_status (status)
);
