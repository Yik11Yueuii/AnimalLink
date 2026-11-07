ALTER TABLE matching_decision_intent
    ADD COLUMN fencing_version BIGINT NULL,
    ADD COLUMN deterministic_failure_code VARCHAR(64) NULL,
    ADD CONSTRAINT chk_matching_intent_fencing CHECK (fencing_version IS NULL OR fencing_version > 0);

CREATE TABLE matching_decision_intent_history (
    id CHAR(36) NOT NULL PRIMARY KEY,
    matching_record_id CHAR(36) NOT NULL,
    source_ai_task_id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    decision_type VARCHAR(32) NOT NULL,
    selected_animal_id CHAR(36) NULL,
    selected_rank INT NULL,
    selected_score DECIMAL(8,6) NULL,
    post_text VARCHAR(2000) NULL,
    fencing_version BIGINT NOT NULL,
    deterministic_failure_code VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    retired_at TIMESTAMP(6) NOT NULL,
    KEY idx_matching_intent_history_task (source_ai_task_id),
    CONSTRAINT fk_matching_intent_history_record FOREIGN KEY (matching_record_id)
        REFERENCES matching_record(id),
    CONSTRAINT fk_matching_intent_history_task FOREIGN KEY (source_ai_task_id)
        REFERENCES ai_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
