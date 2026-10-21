ALTER TABLE rooms
  ADD COLUMN department_code VARCHAR(40) NULL,
  ADD COLUMN open_time TIME NOT NULL DEFAULT '07:00:00',
  ADD COLUMN close_time TIME NOT NULL DEFAULT '23:00:00',
  ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE seats ADD COLUMN active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE reservations
  ADD COLUMN user_email VARCHAR(200) NOT NULL DEFAULT 'unknown@campus.test',
  ADD COLUMN checked_in_at TIMESTAMP(6) NULL,
  ADD COLUMN checkin_code_date DATE NULL;

CREATE TABLE violation_records (
  id CHAR(36) PRIMARY KEY,
  reservation_id CHAR(36) NOT NULL UNIQUE,
  user_id CHAR(36) NOT NULL,
  reason VARCHAR(100) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY(reservation_id) REFERENCES reservations(id)
);
CREATE INDEX ix_violation_user ON violation_records(user_id,created_at);

CREATE TABLE reservation_notification_tasks (
  id CHAR(36) PRIMARY KEY,
  event_key VARCHAR(100) NOT NULL UNIQUE,
  reservation_id CHAR(36) NOT NULL,
  user_id CHAR(36) NOT NULL,
  email VARCHAR(200) NOT NULL,
  message VARCHAR(500) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  last_error VARCHAR(500),
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY(reservation_id) REFERENCES reservations(id)
);
CREATE INDEX ix_reservation_notice_pending ON reservation_notification_tasks(status,next_attempt);
