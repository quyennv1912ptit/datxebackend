ALTER TABLE drivers
    ADD COLUMN online_vehicle_type varchar(30);

ALTER TABLE drivers
    ADD CONSTRAINT ck_drivers_online_vehicle_type
        CHECK (online_vehicle_type IS NULL OR online_vehicle_type IN ('CAR', 'MOTORCYCLE'));
