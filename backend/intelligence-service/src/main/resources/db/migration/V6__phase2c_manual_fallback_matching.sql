ALTER TABLE matching_record
    ADD COLUMN matching_mode VARCHAR(32) NOT NULL DEFAULT 'AUTOMATED' AFTER experiment_code,
    ADD COLUMN fallback_reason VARCHAR(64) NULL AFTER matching_mode,
    ADD CONSTRAINT chk_matching_record_mode CHECK (
        matching_mode IN ('AUTOMATED', 'MANUAL_FALLBACK')
    ),
    ADD CONSTRAINT chk_matching_record_fallback CHECK (
        (matching_mode = 'AUTOMATED' AND fallback_reason IS NULL)
        OR (matching_mode = 'MANUAL_FALLBACK' AND fallback_reason IS NOT NULL)
    );
