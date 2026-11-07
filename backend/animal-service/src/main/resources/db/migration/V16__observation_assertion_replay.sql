CREATE TABLE observation_assertion_replay (
    jti CHAR(36) NOT NULL PRIMARY KEY,
    expires_at TIMESTAMP(6) NOT NULL,
    KEY idx_observation_assertion_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
