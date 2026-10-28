-- The schema column is named active (not is_active).
-- Preserve any active admin-managed rule; repeated execution is harmless.
INSERT INTO pricing_rules (
    vehicle_type, base_fare, per_km, min_fare,
    per_minute_waiting, night_surcharge_percent, return_fee_for_driver, active
)
SELECT 'CAR', 20000, 10000, 30000, 0, 0, 0, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM pricing_rules WHERE vehicle_type = 'CAR' AND active = TRUE
);

INSERT INTO pricing_rules (
    vehicle_type, base_fare, per_km, min_fare,
    per_minute_waiting, night_surcharge_percent, return_fee_for_driver, active
)
SELECT 'MOTORCYCLE', 10000, 5000, 15000, 0, 0, 0, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM pricing_rules WHERE vehicle_type = 'MOTORCYCLE' AND active = TRUE
);
