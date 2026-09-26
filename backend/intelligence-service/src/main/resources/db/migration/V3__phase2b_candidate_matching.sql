CREATE TABLE ai_task_media (
    task_id CHAR(36) NOT NULL,
    sort_order INT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    PRIMARY KEY (task_id, sort_order),
    UNIQUE KEY uk_ai_task_media_key (task_id, object_key),
    CONSTRAINT fk_ai_task_media_task FOREIGN KEY (task_id) REFERENCES ai_task(id),
    CONSTRAINT chk_ai_task_media_sort CHECK (sort_order >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE animal_embedding (
    id CHAR(36) NOT NULL,
    animal_id CHAR(36) NOT NULL,
    media_id CHAR(36) NOT NULL,
    media_object_key VARCHAR(512) NOT NULL,
    model_provider VARCHAR(64) NOT NULL,
    model_name VARCHAR(128) NOT NULL,
    model_version VARCHAR(64) NOT NULL,
    vector_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_animal_embedding_version (animal_id, media_id, model_name, model_version),
    INDEX idx_animal_embedding_animal (animal_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE matching_record (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    ai_task_id CHAR(36) NOT NULL,
    campus_id CHAR(36) NOT NULL,
    algorithm_version VARCHAR(64) NOT NULL,
    weight_version VARCHAR(64) NOT NULL,
    experiment_code CHAR(1) NOT NULL,
    weights_json JSON NOT NULL,
    top_k INT NOT NULL,
    candidate_count INT NOT NULL,
    low_confidence BOOLEAN NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_matching_record_user_created (user_id, created_at),
    INDEX idx_matching_record_task_created (ai_task_id, created_at),
    CONSTRAINT fk_matching_record_task FOREIGN KEY (ai_task_id) REFERENCES ai_task(id),
    CONSTRAINT chk_matching_record_experiment CHECK (experiment_code IN ('A','B','C','D')),
    CONSTRAINT chk_matching_record_top_k CHECK (top_k BETWEEN 1 AND 10),
    CONSTRAINT chk_matching_record_count CHECK (candidate_count >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE matching_candidate (
    id CHAR(36) NOT NULL,
    matching_record_id CHAR(36) NOT NULL,
    rank_number INT NOT NULL,
    animal_id CHAR(36) NOT NULL,
    display_name VARCHAR(80) NOT NULL,
    species VARCHAR(32) NOT NULL,
    cover_media_object_key VARCHAR(512) NULL,
    image_score DECIMAL(8,6) NULL,
    trait_score DECIMAL(8,6) NULL,
    geo_score DECIMAL(8,6) NULL,
    history_score DECIMAL(8,6) NULL,
    final_score DECIMAL(8,6) NOT NULL,
    reasons_json JSON NOT NULL,
    missing_dimensions_json JSON NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_matching_candidate_rank (matching_record_id, rank_number),
    UNIQUE KEY uk_matching_candidate_animal (matching_record_id, animal_id),
    CONSTRAINT fk_matching_candidate_record FOREIGN KEY (matching_record_id) REFERENCES matching_record(id),
    CONSTRAINT chk_matching_candidate_rank CHECK (rank_number BETWEEN 1 AND 10),
    CONSTRAINT chk_matching_candidate_final CHECK (final_score BETWEEN 0 AND 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
