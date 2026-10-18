CREATE TABLE animal_adoption_lifecycle_projection (
  animal_id CHAR(36) NOT NULL,
  handover_id CHAR(36) NOT NULL,
  lifecycle_started_at DATETIME(6) NOT NULL,
  handover_status VARCHAR(16) NOT NULL,
  relation_id CHAR(36) NULL,
  relation_status VARCHAR(16) NULL,
  reconciled_at DATETIME(6) NOT NULL,
  PRIMARY KEY (animal_id),
  UNIQUE KEY uk_animal_adoption_lifecycle_handover (handover_id),
  CONSTRAINT fk_animal_adoption_lifecycle_animal
    FOREIGN KEY (animal_id) REFERENCES animal (id) ON DELETE CASCADE,
  CONSTRAINT chk_animal_adoption_lifecycle_handover_status
    CHECK (handover_status IN ('PENDING', 'COMPLETED', 'CANCELLED')),
  CONSTRAINT chk_animal_adoption_lifecycle_relation_status
    CHECK (relation_status IS NULL OR relation_status IN ('ACTIVE', 'ENDED')),
  CONSTRAINT chk_animal_adoption_lifecycle_relation_pair
    CHECK ((relation_id IS NULL AND relation_status IS NULL)
        OR (relation_id IS NOT NULL AND relation_status IS NOT NULL))
) ENGINE=InnoDB;
