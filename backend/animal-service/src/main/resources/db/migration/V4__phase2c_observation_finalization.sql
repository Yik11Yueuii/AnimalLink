ALTER TABLE post
    ADD COLUMN source_ai_task_id CHAR(36) NULL AFTER animal_id,
    ADD COLUMN source_matching_record_id CHAR(36) NULL AFTER source_ai_task_id,
    ADD COLUMN source_proposal_id CHAR(36) NULL AFTER source_matching_record_id,
    ADD UNIQUE KEY uk_post_source_matching (source_matching_record_id),
    ADD KEY idx_post_source_task (source_ai_task_id);

CREATE TABLE animal_identity_proposal (
    id CHAR(36) NOT NULL,
    campus_id CHAR(36) NOT NULL,
    created_by_user_id CHAR(36) NOT NULL,
    source_ai_task_id CHAR(36) NOT NULL,
    source_matching_record_id CHAR(36) NOT NULL,
    post_id CHAR(36) NOT NULL,
    proposed_species VARCHAR(32) NOT NULL,
    proposed_sex VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    proposed_coat_color VARCHAR(120) NULL,
    proposed_distinctive_features VARCHAR(1000) NULL,
    proposed_description VARCHAR(2000) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_REVIEW',
    reviewed_at DATETIME(6) NULL,
    reviewed_by CHAR(36) NULL,
    resolution_animal_id CHAR(36) NULL,
    review_reason VARCHAR(1000) NULL,
    version INT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_proposal_source_matching (source_matching_record_id),
    UNIQUE KEY uk_proposal_post (post_id),
    KEY idx_proposal_campus_status_created (campus_id, status, created_at DESC, id DESC),
    KEY idx_proposal_resolution (resolution_animal_id),
    CONSTRAINT fk_proposal_post FOREIGN KEY (post_id) REFERENCES post(id),
    CONSTRAINT fk_proposal_resolution_animal FOREIGN KEY (resolution_animal_id) REFERENCES animal(id),
    CONSTRAINT chk_proposal_species CHECK (proposed_species IN ('CAT', 'DOG', 'OTHER', 'UNKNOWN')),
    CONSTRAINT chk_proposal_sex CHECK (proposed_sex IN ('MALE', 'FEMALE', 'UNKNOWN')),
    CONSTRAINT chk_proposal_status CHECK (
        status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED', 'LINKED_EXISTING')
    ),
    CONSTRAINT chk_proposal_resolution CHECK (
        (status = 'PENDING_REVIEW' AND reviewed_at IS NULL AND reviewed_by IS NULL
            AND resolution_animal_id IS NULL)
        OR
        (status = 'REJECTED' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL
            AND resolution_animal_id IS NULL)
        OR
        (status IN ('APPROVED', 'LINKED_EXISTING') AND reviewed_at IS NOT NULL
            AND reviewed_by IS NOT NULL AND resolution_animal_id IS NOT NULL)
    )
) ENGINE=InnoDB;

CREATE TABLE observation_finalization (
    id CHAR(36) NOT NULL,
    source_matching_record_id CHAR(36) NOT NULL,
    source_ai_task_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    campus_id CHAR(36) NOT NULL,
    decision_type VARCHAR(32) NOT NULL,
    selected_animal_id CHAR(36) NULL,
    post_id CHAR(36) NOT NULL,
    proposal_id CHAR(36) NULL,
    decision_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_observation_finalization_matching (source_matching_record_id),
    UNIQUE KEY uk_observation_finalization_post (post_id),
    KEY idx_observation_finalization_task (source_ai_task_id),
    CONSTRAINT fk_observation_finalization_animal FOREIGN KEY (selected_animal_id) REFERENCES animal(id),
    CONSTRAINT fk_observation_finalization_post FOREIGN KEY (post_id) REFERENCES post(id),
    CONSTRAINT fk_observation_finalization_proposal FOREIGN KEY (proposal_id)
        REFERENCES animal_identity_proposal(id),
    CONSTRAINT chk_observation_finalization_type CHECK (
        decision_type IN ('SELECT_EXISTING', 'NO_MATCH')
    ),
    CONSTRAINT chk_observation_finalization_targets CHECK (
        (decision_type = 'SELECT_EXISTING' AND selected_animal_id IS NOT NULL AND proposal_id IS NULL)
        OR
        (decision_type = 'NO_MATCH' AND selected_animal_id IS NULL AND proposal_id IS NOT NULL)
    )
) ENGINE=InnoDB;
