ALTER TABLE ai_task
    ADD COLUMN idempotency_key VARCHAR(128) NULL AFTER input_summary,
    ADD COLUMN external_reference_id CHAR(36) NULL AFTER idempotency_key;

ALTER TABLE ai_task DROP CHECK chk_ai_task_type;
ALTER TABLE ai_task
    ADD CONSTRAINT chk_ai_task_type CHECK (
        task_type IN ('ANIMAL_OBSERVATION_PARSE', 'CAMPUS_CREDENTIAL_PRECHECK')
    );

ALTER TABLE ai_task
    ADD UNIQUE KEY uk_ai_task_type_idempotency (task_type, idempotency_key);

ALTER TABLE ai_result MODIFY raw_response MEDIUMTEXT NULL;
