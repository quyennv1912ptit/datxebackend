ALTER TABLE vehicles
    ADD COLUMN archived_at timestamptz,
    ADD COLUMN is_default boolean NOT NULL DEFAULT false,
    ADD COLUMN engine_type varchar(20),
    ADD COLUMN electric_motor_kw double precision,
    ADD COLUMN passenger_seats integer,
    ADD COLUMN designed_mass_kg integer,
    ADD COLUMN trailer_mass_kg integer,
    ADD CONSTRAINT chk_garage_active_default CHECK (archived_at IS NULL OR NOT is_default);

ALTER TABLE vehicles DROP CONSTRAINT vehicles_plate_number_key;
CREATE UNIQUE INDEX vehicles_plate_number_key ON vehicles (plate_number) WHERE archived_at IS NULL;
CREATE UNIQUE INDEX uq_garage_owner_default ON vehicles (customer_id) WHERE is_default;

ALTER TABLE rides ADD COLUMN vehicle_booking_snapshot jsonb;
CREATE FUNCTION prohibit_vehicle_snapshot_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.vehicle_booking_snapshot IS DISTINCT FROM NEW.vehicle_booking_snapshot THEN
        RAISE EXCEPTION 'Ride vehicle snapshot is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER rides_immutable_vehicle_snapshot BEFORE UPDATE OF vehicle_booking_snapshot ON rides
    FOR EACH ROW EXECUTE FUNCTION prohibit_vehicle_snapshot_update();
