ALTER TABLE rides
    ADD COLUMN pricing_rule_id BIGINT REFERENCES pricing_rules(id),
    ADD COLUMN pricing_base_fare BIGINT,
    ADD COLUMN pricing_distance_fare BIGINT,
    ADD COLUMN pricing_waiting_fare BIGINT,
    ADD COLUMN pricing_night_surcharge BIGINT,
    ADD COLUMN pricing_return_fee BIGINT,
    ADD CONSTRAINT rides_pricing_snapshot_complete CHECK (
        num_nonnulls(pricing_rule_id, pricing_base_fare, pricing_distance_fare,
                     pricing_waiting_fare, pricing_night_surcharge, pricing_return_fee) IN (0, 6)
    );

CREATE FUNCTION prohibit_ride_pricing_snapshot_update() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF ROW(OLD.pricing_rule_id, OLD.pricing_base_fare, OLD.pricing_distance_fare,
           OLD.pricing_waiting_fare, OLD.pricing_night_surcharge, OLD.pricing_return_fee)
       IS DISTINCT FROM
       ROW(NEW.pricing_rule_id, NEW.pricing_base_fare, NEW.pricing_distance_fare,
           NEW.pricing_waiting_fare, NEW.pricing_night_surcharge, NEW.pricing_return_fee) THEN
        RAISE EXCEPTION 'Ride pricing snapshot is immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER rides_immutable_pricing_snapshot
    BEFORE UPDATE OF pricing_rule_id, pricing_base_fare, pricing_distance_fare,
                     pricing_waiting_fare, pricing_night_surcharge, pricing_return_fee ON rides
    FOR EACH ROW EXECUTE FUNCTION prohibit_ride_pricing_snapshot_update();

CREATE TABLE pricing_rule_changes (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    pricing_rule_id BIGINT NOT NULL REFERENCES pricing_rules(id),
    admin_id BIGINT NOT NULL,
    previous_rule JSONB NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX pricing_rule_changes_rule_time ON pricing_rule_changes(pricing_rule_id, changed_at, id);
