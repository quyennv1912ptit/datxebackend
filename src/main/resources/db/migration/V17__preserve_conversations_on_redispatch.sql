-- A conversation belongs to one driver on a ride. Releasing the current
-- assignment must preserve the old thread without exposing it to the next driver.
ALTER TABLE conversations DROP CONSTRAINT fk_conversations_ride_driver;
ALTER TABLE conversations DROP CONSTRAINT conversations_ride_id_key;
ALTER TABLE conversations ADD CONSTRAINT fk_conversations_driver FOREIGN KEY (driver_id) REFERENCES drivers(id);
ALTER TABLE conversations ADD CONSTRAINT uq_conversations_ride_driver UNIQUE (ride_id, driver_id);
