-- Audit the last driver-initiated redispatch without corrupting the terminal cancel fields.
ALTER TABLE rides ADD COLUMN last_redispatch_reason varchar(255);
ALTER TABLE rides ADD COLUMN last_redispatch_at timestamptz;
ALTER TABLE rides ADD COLUMN last_redispatch_driver_id bigint;
