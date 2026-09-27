CREATE TABLE matching_decision (
    id CHAR(36) NOT NULL,
    matching_record_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    decision_type VARCHAR(32) NOT NULL,
    selected_animal_id CHAR(36) NULL,
    selected_rank INT NULL,
    selected_score DECIMAL(8,6) NULL,
    post_id CHAR(36) NULL,
    proposal_id CHAR(36) NULL,
    decided_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_matching_decision_record (matching_record_id),
    INDEX idx_matching_decision_user_decided (user_id, decided_at),
    CONSTRAINT fk_matching_decision_record FOREIGN KEY (matching_record_id)
        REFERENCES matching_record(id),
    CONSTRAINT chk_matching_decision_type CHECK (
        decision_type IN ('SELECT_EXISTING', 'NO_MATCH', 'UNSURE')
    ),
    CONSTRAINT chk_matching_decision_selection CHECK (
        (decision_type = 'SELECT_EXISTING'
            AND selected_animal_id IS NOT NULL
            AND selected_rank IS NOT NULL
            AND selected_score IS NOT NULL)
        OR
        (decision_type IN ('NO_MATCH', 'UNSURE')
            AND selected_animal_id IS NULL
            AND selected_rank IS NULL
            AND selected_score IS NULL)
    ),
    CONSTRAINT chk_matching_decision_outputs CHECK (
        (decision_type = 'SELECT_EXISTING' AND post_id IS NOT NULL AND proposal_id IS NULL)
        OR
        (decision_type = 'NO_MATCH' AND post_id IS NOT NULL AND proposal_id IS NOT NULL)
        OR
        (decision_type = 'UNSURE' AND post_id IS NULL AND proposal_id IS NULL)
    ),
    CONSTRAINT chk_matching_decision_rank CHECK (
        selected_rank IS NULL OR selected_rank BETWEEN 1 AND 10
    ),
    CONSTRAINT chk_matching_decision_score CHECK (
        selected_score IS NULL OR selected_score BETWEEN 0 AND 1
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
