ALTER TABLE work_orders
  ADD COLUMN repairer_name VARCHAR(100) NULL AFTER repairer_id,
  ADD COLUMN repairer_email VARCHAR(200) NULL AFTER repairer_name,
  ADD COLUMN assigned_at TIMESTAMP(6) NULL,
  ADD COLUMN started_at TIMESTAMP(6) NULL,
  ADD COLUMN completed_at TIMESTAMP(6) NULL,
  ADD COLUMN accepted_at TIMESTAMP(6) NULL,
  ADD COLUMN completion_note VARCHAR(1000) NULL,
  ADD COLUMN acceptance_note VARCHAR(1000) NULL,
  ADD COLUMN rating INT NULL,
  ADD COLUMN rating_comment VARCHAR(1000) NULL,
  ADD COLUMN rated_at TIMESTAMP(6) NULL,
  ADD COLUMN escalation_level INT NOT NULL DEFAULT 0,
  ADD COLUMN escalated_at TIMESTAMP(6) NULL;
