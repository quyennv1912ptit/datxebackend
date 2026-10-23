-- Keep customer/driver rows referenced by ride, payment, wallet and rating history,
-- while marking accounts that have been anonymized and removed from Firebase.
ALTER TABLE customers ADD COLUMN deleted_at timestamptz;
ALTER TABLE drivers ADD COLUMN deleted_at timestamptz;

CREATE INDEX idx_customers_deleted_at ON customers (deleted_at) WHERE deleted_at IS NOT NULL;
CREATE INDEX idx_drivers_deleted_at ON drivers (deleted_at) WHERE deleted_at IS NOT NULL;
