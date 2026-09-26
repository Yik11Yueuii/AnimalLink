CREATE TABLE ai_task (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    campus_id CHAR(36) NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    model_provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    prompt_version VARCHAR(64) NOT NULL,
    input_summary JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    started_at TIMESTAMP(6) NULL,
    completed_at TIMESTAMP(6) NULL,
    failed_at TIMESTAMP(6) NULL,
    error_code VARCHAR(64) NULL,
    error_message VARCHAR(500) NULL,
    confirmed_at TIMESTAMP(6) NULL,
    confirmed_by CHAR(36) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    INDEX idx_ai_task_user_created (user_id, created_at),
    INDEX idx_ai_task_campus_created (campus_id, created_at),
    INDEX idx_ai_task_status_created (status, created_at),
    CONSTRAINT chk_ai_task_type CHECK (task_type IN ('ANIMAL_OBSERVATION_PARSE')),
    CONSTRAINT chk_ai_task_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_result (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    result_type VARCHAR(64) NOT NULL,
    raw_response MEDIUMTEXT NOT NULL,
    structured_json JSON NOT NULL,
    schema_version VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_result_task (task_id),
    CONSTRAINT fk_ai_result_task FOREIGN KEY (task_id) REFERENCES ai_task(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE ai_confirmation (
    id CHAR(36) NOT NULL,
    task_id CHAR(36) NOT NULL,
    result_id CHAR(36) NOT NULL,
    confirmed_structured_json JSON NOT NULL,
    was_modified BOOLEAN NOT NULL,
    confirmed_by CHAR(36) NOT NULL,
    confirmed_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_confirmation_task (task_id),
    UNIQUE KEY uk_ai_confirmation_result (result_id),
    CONSTRAINT fk_ai_confirmation_task FOREIGN KEY (task_id) REFERENCES ai_task(id),
    CONSTRAINT fk_ai_confirmation_result FOREIGN KEY (result_id) REFERENCES ai_result(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
