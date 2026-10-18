CREATE TABLE animal_merge_record (
  id CHAR(36) NOT NULL,
  source_animal_id CHAR(36) NOT NULL,
  target_animal_id CHAR(36) NOT NULL,
  actor_user_id CHAR(36) NOT NULL,
  reason VARCHAR(1000) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_animal_merge_record_source (source_animal_id),
  KEY idx_animal_merge_record_target_created
    (target_animal_id, created_at DESC, id DESC),
  CONSTRAINT fk_animal_merge_record_source
    FOREIGN KEY (source_animal_id) REFERENCES animal (id),
  CONSTRAINT fk_animal_merge_record_target
    FOREIGN KEY (target_animal_id) REFERENCES animal (id),
  CONSTRAINT chk_animal_merge_record_distinct
    CHECK (source_animal_id <> target_animal_id)
) ENGINE=InnoDB;
