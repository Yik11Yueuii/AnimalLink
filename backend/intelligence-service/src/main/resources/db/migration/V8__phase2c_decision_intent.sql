CREATE TABLE matching_decision_intent (
    id CHAR(36) NOT NULL,
    matching_record_id CHAR(36) NOT NULL,
    source_ai_task_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    decision_type VARCHAR(32) NOT NULL,
    selected_animal_id CHAR(36) NULL,
    selected_rank INT NULL,
    selected_score DECIMAL(8,6) NULL,
    post_text VARCHAR(2000) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_matching_decision_intent_task (source_ai_task_id),
    CONSTRAINT fk_matching_decision_intent_record FOREIGN KEY (matching_record_id)
        REFERENCES matching_record(id),
    CONSTRAINT fk_matching_decision_intent_task FOREIGN KEY (source_ai_task_id)
        REFERENCES ai_task(id),
    CONSTRAINT chk_matching_decision_intent_type CHECK (
        decision_type IN ('SELECT_EXISTING', 'NO_MATCH', 'UNSURE')
    ),
    CONSTRAINT chk_matching_decision_intent_selection CHECK (
        (decision_type = 'SELECT_EXISTING' AND selected_animal_id IS NOT NULL
            AND selected_rank IS NOT NULL AND selected_score IS NOT NULL)
        OR (decision_type IN ('NO_MATCH', 'UNSURE') AND selected_animal_id IS NULL
            AND selected_rank IS NULL AND selected_score IS NULL)
    ),
    CONSTRAINT chk_matching_decision_intent_post CHECK (
        (decision_type = 'UNSURE' AND post_text IS NULL)
        OR (decision_type IN ('SELECT_EXISTING', 'NO_MATCH') AND post_text IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
