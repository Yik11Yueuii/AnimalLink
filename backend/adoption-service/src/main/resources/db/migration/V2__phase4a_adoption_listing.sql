CREATE TABLE adoption_listing (
    id varchar(36) NOT NULL PRIMARY KEY,
    animal_id varchar(36) NOT NULL,
    status varchar(20) NOT NULL,
    title varchar(160) NOT NULL,
    description varchar(4000) NOT NULL,
    publisher_user_id varchar(36) NOT NULL,
    created_at timestamp(6) NOT NULL,
    updated_at timestamp(6) NOT NULL,
    published_at timestamp(6) NULL,
    closed_at timestamp(6) NULL,
    active_animal_id varchar(36) GENERATED ALWAYS AS (
        CASE WHEN status IN ('DRAFT', 'PUBLISHED') THEN animal_id ELSE NULL END
    ) STORED,
    CONSTRAINT chk_adoption_listing_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'CLOSED')),
    CONSTRAINT uq_adoption_listing_active_animal UNIQUE (active_animal_id),
    KEY ix_adoption_listing_animal (animal_id),
    KEY ix_adoption_listing_status_published_id (status, published_at, id),
    KEY ix_adoption_listing_publisher_created (publisher_user_id, created_at)
);
