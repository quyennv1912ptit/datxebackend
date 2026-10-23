ALTER TABLE driver_vehicle_skills
    DROP CONSTRAINT IF EXISTS uq_dvs_driver_skill;

CREATE UNIQUE INDEX uq_dvs_driver_skill
    ON driver_vehicle_skills (driver_id, vehicle_type, transmission, COALESCE(license_class, ''));
