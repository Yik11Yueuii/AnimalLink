ALTER TABLE observation_finalization
    ADD UNIQUE KEY uk_observation_finalization_task (source_ai_task_id);
