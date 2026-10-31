CREATE TABLE application_resource_events (
  event_id CHAR(36) PRIMARY KEY,
  application_id CHAR(36) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY(application_id) REFERENCES room_applications(id)
);

CREATE TABLE application_notification_tasks (
  id CHAR(36) PRIMARY KEY,
  event_key VARCHAR(100) NOT NULL UNIQUE,
  user_id CHAR(36) NOT NULL,
  email VARCHAR(200) NOT NULL,
  message VARCHAR(500) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_error VARCHAR(500)
);
