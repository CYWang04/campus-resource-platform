ALTER TABLE notifications ADD COLUMN external_event_key VARCHAR(100) NULL;
CREATE UNIQUE INDEX ux_notifications_external_event ON notifications(external_event_key);
