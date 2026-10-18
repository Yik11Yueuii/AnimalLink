CREATE TABLE animal_adoption_status_command (
  idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  animal_id CHAR(36) NOT NULL,
  transition_action VARCHAR(16) NOT NULL,
  from_status VARCHAR(32) NOT NULL,
  to_status VARCHAR(32) NOT NULL,
  actor_user_id CHAR(36) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  result_version INT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (idempotency_key),
  KEY idx_animal_adoption_command_animal_created (animal_id, created_at DESC),
  CONSTRAINT fk_animal_adoption_command_animal
    FOREIGN KEY (animal_id) REFERENCES animal (id),
  CONSTRAINT chk_animal_adoption_command_action
    CHECK (transition_action IN ('OPEN', 'CLOSE')),
  CONSTRAINT chk_animal_adoption_command_transition
    CHECK ((from_status = 'NOT_OPEN' AND to_status = 'OPEN')
        OR (from_status = 'OPEN' AND to_status = 'NOT_OPEN')),
  CONSTRAINT chk_animal_adoption_command_version CHECK (result_version > 0)
) ENGINE=InnoDB;
