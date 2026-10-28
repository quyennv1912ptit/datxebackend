CREATE TABLE ride_route_quotes (
    id UUID PRIMARY KEY,
    fingerprint VARCHAR(64) NOT NULL UNIQUE,
    polyline TEXT NOT NULL,
    distance_meters DOUBLE PRECISION NOT NULL CHECK (distance_meters > 0),
    duration_seconds DOUBLE PRECISION NOT NULL CHECK (duration_seconds > 0),
    provider VARCHAR(30) NOT NULL,
    vehicle_type VARCHAR(30) NOT NULL,
    limitation TEXT,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_ride_route_quotes_expiry ON ride_route_quotes(expires_at);
ALTER TABLE rides
    ADD COLUMN route_polyline TEXT,
    ADD COLUMN route_distance_meters DOUBLE PRECISION,
    ADD COLUMN route_duration_seconds DOUBLE PRECISION,
    ADD COLUMN route_provider VARCHAR(30),
    ADD COLUMN route_limitation TEXT;
-- Historical rides keep NULL route data; do not fabricate routes or call providers to backfill.
