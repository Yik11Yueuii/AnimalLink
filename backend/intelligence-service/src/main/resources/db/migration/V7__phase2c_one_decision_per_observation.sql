ALTER TABLE matching_decision
    ADD COLUMN source_ai_task_id CHAR(36) NULL AFTER matching_record_id;

UPDATE matching_decision decision_record
JOIN matching_record record ON record.id = decision_record.matching_record_id
SET decision_record.source_ai_task_id = record.ai_task_id
WHERE decision_record.source_ai_task_id IS NULL;

ALTER TABLE matching_decision
    MODIFY COLUMN source_ai_task_id CHAR(36) NOT NULL,
    ADD UNIQUE KEY uk_matching_decision_task (source_ai_task_id),
    ADD CONSTRAINT fk_matching_decision_task FOREIGN KEY (source_ai_task_id)
        REFERENCES ai_task(id);
