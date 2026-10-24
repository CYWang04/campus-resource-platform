CREATE TABLE reservations (
  id CHAR(36) PRIMARY KEY,
  user_id CHAR(36) NOT NULL,
  seat_id CHAR(36) NOT NULL,
  room_id CHAR(36) NOT NULL,
  start_at TIMESTAMP(6) NOT NULL,
  end_at TIMESTAMP(6) NOT NULL,
  status VARCHAR(24) NOT NULL,
  purpose VARCHAR(200) NOT NULL,
  created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  cancelled_at TIMESTAMP(6) NULL,
  FOREIGN KEY (seat_id) REFERENCES seats(id),
  FOREIGN KEY (room_id) REFERENCES rooms(id)
);
CREATE INDEX ix_reservation_user ON reservations(user_id, created_at);
CREATE INDEX ix_reservation_seat_time ON reservations(seat_id, status, start_at, end_at);

CREATE TABLE reservation_commands (
  actor_id CHAR(36) NOT NULL,
  idempotency_key VARCHAR(100) NOT NULL,
  fingerprint CHAR(64) NOT NULL,
  result_json JSON NOT NULL,
  PRIMARY KEY(actor_id, idempotency_key)
);
