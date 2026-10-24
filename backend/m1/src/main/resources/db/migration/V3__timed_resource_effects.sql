ALTER TABLE resource_effects ADD COLUMN end_at TIMESTAMP(6) NULL AFTER start_at;
CREATE INDEX ix_effect_room_time ON resource_effects(room_id,state,start_at,end_at);
