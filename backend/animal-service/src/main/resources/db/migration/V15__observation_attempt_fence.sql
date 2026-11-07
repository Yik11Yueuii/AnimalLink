CREATE TABLE observation_attempt_gate (
    source_ai_task_id CHAR(36) NOT NULL PRIMARY KEY,
    attempt_id CHAR(36) NOT NULL,
    actor_user_id CHAR(36) NOT NULL,
    campus_id CHAR(36) NOT NULL,
    matching_record_id CHAR(36) NOT NULL,
    decision_type VARCHAR(32) NOT NULL,
    selected_animal_id CHAR(36) NULL,
    fencing_version BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    finalization_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT chk_observation_attempt_gate_status CHECK (status IN ('ACTIVE', 'RETIRED', 'FINALIZED')),
    CONSTRAINT chk_observation_attempt_gate_decision CHECK (
        (decision_type = 'SELECT_EXISTING' AND selected_animal_id IS NOT NULL)
        OR (decision_type = 'NO_MATCH' AND selected_animal_id IS NULL)),
    CONSTRAINT chk_observation_attempt_gate_version CHECK (fencing_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE observation_attempt_history (
    source_ai_task_id CHAR(36) NOT NULL,
    fencing_version BIGINT NOT NULL,
    attempt_id CHAR(36) NOT NULL,
    actor_user_id CHAR(36) NOT NULL,
    campus_id CHAR(36) NOT NULL,
    matching_record_id CHAR(36) NOT NULL,
    decision_type VARCHAR(32) NOT NULL,
    selected_animal_id CHAR(36) NULL,
    status VARCHAR(16) NOT NULL,
    failure_code VARCHAR(64) NULL,
    finalization_id CHAR(36) NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (source_ai_task_id, fencing_version),
    UNIQUE KEY uk_observation_attempt_identity (source_ai_task_id, attempt_id),
    CONSTRAINT chk_observation_attempt_history_status CHECK (status IN ('ACTIVE', 'RETIRED', 'FINALIZED')),
    CONSTRAINT chk_observation_attempt_history_decision CHECK (
        (decision_type = 'SELECT_EXISTING' AND selected_animal_id IS NOT NULL)
        OR (decision_type = 'NO_MATCH' AND selected_animal_id IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Preserve any formal records created before the fence migration. They can never be retired.
INSERT INTO observation_attempt_gate
    (source_ai_task_id, attempt_id, actor_user_id, campus_id, matching_record_id,
     decision_type, selected_animal_id,
     fencing_version, status, finalization_id, created_at, updated_at)
SELECT source_ai_task_id, source_matching_record_id, user_id, campus_id,
       source_matching_record_id, decision_type, selected_animal_id,
       0, 'FINALIZED', id, created_at, created_at
FROM observation_finalization;

INSERT INTO observation_attempt_history
    (source_ai_task_id, fencing_version, attempt_id, actor_user_id, campus_id,
     matching_record_id, decision_type, selected_animal_id,
     status, finalization_id, created_at, updated_at)
SELECT source_ai_task_id, 0, source_matching_record_id, user_id, campus_id,
       source_matching_record_id, decision_type, selected_animal_id,
       'FINALIZED', id, created_at, created_at
FROM observation_finalization;
