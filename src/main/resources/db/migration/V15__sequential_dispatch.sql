-- Search state and work survive Redis/worker restarts. Existing API enum names remain compatible.
ALTER TABLE rides ADD COLUMN search_expires_at timestamptz;
ALTER TABLE rides ADD COLUMN redispatch_count integer NOT NULL DEFAULT 0 CHECK (redispatch_count >= 0);
UPDATE rides SET search_expires_at = CURRENT_TIMESTAMP + INTERVAL '90 seconds' WHERE status = 'SEARCHING';
CREATE INDEX idx_rides_search_deadline ON rides(search_expires_at) WHERE status = 'SEARCHING';
-- Fail migration rather than silently cancelling an existing duplicate active ride.
CREATE UNIQUE INDEX uq_rides_active_customer ON rides(customer_id)
    WHERE status IN ('SEARCHING','DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED',
                    'VEHICLE_HANDOVER','IN_PROGRESS','ARRIVED_DESTINATION','VEHICLE_RETURNED');
CREATE UNIQUE INDEX uq_rides_active_driver ON rides(driver_id)
    WHERE driver_id IS NOT NULL AND status IN ('DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED',
                    'VEHICLE_HANDOVER','IN_PROGRESS','ARRIVED_DESTINATION','VEHICLE_RETURNED');
ALTER TABLE ride_dispatch_outbox ADD COLUMN revision bigint NOT NULL DEFAULT 0;
ALTER TABLE ride_dispatch_outbox ADD COLUMN claim_token varchar(36);
CREATE TABLE ride_dispatch_offers (
    offer_id varchar(36) PRIMARY KEY,
    ride_id bigint NOT NULL REFERENCES rides(id),
    driver_id bigint NOT NULL REFERENCES drivers(id),
    expires_at timestamptz NOT NULL,
    generation integer NOT NULL DEFAULT 0,
    status varchar(16) NOT NULL CHECK(status IN ('OFFERED','ACCEPTED','REJECTED','IGNORED','CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(ride_id, driver_id)
);
CREATE UNIQUE INDEX uq_dispatch_offered_ride ON ride_dispatch_offers(ride_id) WHERE status='OFFERED';
CREATE UNIQUE INDEX uq_dispatch_offered_driver ON ride_dispatch_offers(driver_id) WHERE status='OFFERED';
CREATE INDEX idx_dispatch_offer_deadline ON ride_dispatch_offers(expires_at) WHERE status='OFFERED';

ALTER TABLE ride_dispatch_outbox ADD COLUMN failures integer NOT NULL DEFAULT 0;
ALTER TABLE rides DROP CONSTRAINT rides_status_check;
ALTER TABLE rides ADD CONSTRAINT rides_status_check CHECK (status IN (
 'PENDING','SEARCHING','DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','VEHICLE_HANDOVER',
 'IN_PROGRESS','ARRIVED_DESTINATION','VEHICLE_RETURNED','COMPLETED','CANCELLED','NO_DRIVER_FOUND','EXPIRED','FAILED_SYSTEM'));

ALTER TABLE ride_status_histories DROP CONSTRAINT ride_status_histories_from_status_check;
ALTER TABLE ride_status_histories DROP CONSTRAINT ride_status_histories_to_status_check;
ALTER TABLE ride_status_histories ADD CONSTRAINT ride_status_histories_from_status_check CHECK(from_status IN ('PENDING','SEARCHING','DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','VEHICLE_HANDOVER','IN_PROGRESS','ARRIVED_DESTINATION','VEHICLE_RETURNED','COMPLETED','CANCELLED','NO_DRIVER_FOUND','EXPIRED','FAILED_SYSTEM'));
ALTER TABLE ride_status_histories ADD CONSTRAINT ride_status_histories_to_status_check CHECK(to_status IN ('PENDING','SEARCHING','DRIVER_ASSIGNED','DRIVER_ARRIVING','DRIVER_ARRIVED','VEHICLE_HANDOVER','IN_PROGRESS','ARRIVED_DESTINATION','VEHICLE_RETURNED','COMPLETED','CANCELLED','NO_DRIVER_FOUND','EXPIRED','FAILED_SYSTEM'));
