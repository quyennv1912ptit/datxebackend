-- Driver presence is represented by the typed drivers.status column.
ALTER TABLE drivers DROP COLUMN IF EXISTS online_status;
