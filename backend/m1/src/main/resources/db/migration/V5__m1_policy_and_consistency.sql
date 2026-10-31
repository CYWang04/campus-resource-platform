CREATE TABLE reservation_settings (
  id TINYINT PRIMARY KEY,
  max_duration_hours INT NOT NULL,
  max_advance_days INT NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  CHECK (id = 1),
  CHECK (max_duration_hours BETWEEN 1 AND 12),
  CHECK (max_advance_days BETWEEN 1 AND 30)
);
INSERT INTO reservation_settings(id,max_duration_hours,max_advance_days) VALUES(1,4,7);

UPDATE rooms SET department_code=NULL WHERE TRIM(department_code)='';
ALTER TABLE resource_effects ADD COLUMN invalidated_by CHAR(36) NULL;

CREATE TABLE resource_cancellation_tasks (
  id CHAR(36) PRIMARY KEY,
  application_id CHAR(36) NOT NULL UNIQUE,
  room_id CHAR(36) NOT NULL,
  source_version BIGINT NOT NULL,
  maintenance_source_id CHAR(36) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_error VARCHAR(500),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE INDEX ix_resource_cancellation_pending ON resource_cancellation_tasks(status,next_attempt);

CREATE TABLE resource_audit_events (
  id CHAR(36) PRIMARY KEY,
  actor_id VARCHAR(50) NOT NULL,
  action VARCHAR(80) NOT NULL,
  entity_type VARCHAR(40) NOT NULL,
  entity_id VARCHAR(50) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  request_id VARCHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
);
CREATE INDEX ix_resource_audit_entity ON resource_audit_events(entity_type,entity_id,created_at);
