ALTER TABLE users ADD COLUMN department_code VARCHAR(40) NOT NULL DEFAULT 'CS';

CREATE TABLE room_applications (
  id CHAR(36) PRIMARY KEY,
  applicant_id CHAR(36) NOT NULL,
  applicant_email VARCHAR(200) NOT NULL,
  room_id CHAR(36) NOT NULL,
  room_name VARCHAR(100) NOT NULL,
  purpose VARCHAR(500) NOT NULL,
  attendee_count INT NOT NULL,
  start_at TIMESTAMP(6) NOT NULL,
  end_at TIMESTAMP(6) NOT NULL,
  state VARCHAR(40) NOT NULL,
  department_reviewer_id CHAR(36),
  resource_reviewer_id CHAR(36),
  review_comment VARCHAR(500),
  source_version BIGINT NOT NULL DEFAULT 0,
  expected_availability_version BIGINT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY (applicant_id) REFERENCES users(id)
);
CREATE INDEX ix_application_applicant ON room_applications(applicant_id, created_at);
CREATE INDEX ix_application_state_deadline ON room_applications(state, updated_at, start_at);

CREATE TABLE application_commands (
  actor_id CHAR(36) NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  application_id CHAR(36) NOT NULL,
  PRIMARY KEY(actor_id, idempotency_key)
);

CREATE TABLE application_sync_tasks (
  id CHAR(36) PRIMARY KEY,
  application_id CHAR(36) NOT NULL,
  room_id CHAR(36) NOT NULL,
  source_version BIGINT NOT NULL,
  desired_state VARCHAR(20) NOT NULL,
  expected_availability_version BIGINT,
  request_id VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_error VARCHAR(500),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY(application_id) REFERENCES room_applications(id)
);
CREATE INDEX ix_application_tasks_pending ON application_sync_tasks(status,next_attempt);

CREATE TABLE application_audit_events (
  id CHAR(36) PRIMARY KEY,
  actor_id VARCHAR(50) NOT NULL,
  action VARCHAR(80) NOT NULL,
  application_id CHAR(36) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
