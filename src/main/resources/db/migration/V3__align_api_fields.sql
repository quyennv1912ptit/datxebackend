-- Fields already exposed by the API modules but missing from the initial schema.
-- Keep V1/V2 immutable because Flyway uses their checksums.
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS brand varchar(100);
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS model varchar(100);
ALTER TABLE vehicles ADD COLUMN IF NOT EXISTS color varchar(50);

ALTER TABLE drivers ADD COLUMN IF NOT EXISTS license_image_url text;

ALTER TABLE ratings ADD COLUMN IF NOT EXISTS tip bigint NOT NULL DEFAULT 0;
ALTER TABLE ratings ADD CONSTRAINT ck_ratings_tip_nonnegative CHECK (tip >= 0);

ALTER TABLE wallet_transactions ADD COLUMN IF NOT EXISTS balance_after bigint;
