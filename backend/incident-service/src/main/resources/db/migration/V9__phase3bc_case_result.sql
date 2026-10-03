ALTER TABLE animal_case
  ADD COLUMN result_code varchar(32) NULL,
  ADD COLUMN result_summary varchar(2000) NULL,
  ADD COLUMN result_submitted_by_user_id varchar(36) NULL,
  ADD COLUMN result_submitted_at timestamp(6) NULL,
  ADD COLUMN closed_at timestamp(6) NULL;

ALTER TABLE case_action
  ADD COLUMN result_code varchar(32) NULL;
