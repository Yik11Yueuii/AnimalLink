CREATE TABLE processed_domain_event (
  event_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  processed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (event_id)
) ENGINE=InnoDB;
