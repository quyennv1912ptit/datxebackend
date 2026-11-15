-- Voucher ownership and personal reward support.

ALTER TABLE vouchers
    ADD COLUMN IF NOT EXISTS allowed_payment_method varchar(30);

-- Safely drop legacy constraints if they exist
ALTER TABLE vouchers
    DROP CONSTRAINT IF EXISTS vouchers_source_check,
    DROP CONSTRAINT IF EXISTS vouchers_owner_check,
    DROP CONSTRAINT IF EXISTS uk30ftp2biebbvpik8e49wlmady,
    DROP CONSTRAINT IF EXISTS vouchers_owner_code_check,
    DROP CONSTRAINT IF EXISTS vouchers_milestone_check;

-- Allow personal reward vouchers to have no public code.
ALTER TABLE vouchers
    ALTER COLUMN code DROP NOT NULL;

-- Replace old unique constraint with a partial unique index.
DROP INDEX IF EXISTS uq_vouchers_code_not_null;
CREATE UNIQUE INDEX uq_vouchers_code_not_null
    ON vouchers (code)
    WHERE code IS NOT NULL;

-- Migrate existing voucher types without deleting voucher records.
UPDATE vouchers
SET source = CASE
        WHEN source = 'PUBLIC' THEN 'PUBLIC_CODE'
        WHEN source IN ('WELCOME', 'MILESTONE') THEN 'SYSTEM_REWARD'
        ELSE source
    END,
    code = CASE
        WHEN source IN ('WELCOME', 'MILESTONE') THEN NULL
        ELSE code
    END;

ALTER TABLE vouchers
    ALTER COLUMN source SET DEFAULT 'PUBLIC_CODE';

ALTER TABLE vouchers
    ADD CONSTRAINT vouchers_source_check
        CHECK (source IN ('PUBLIC_CODE', 'SYSTEM_REWARD')),
    ADD CONSTRAINT vouchers_owner_code_check
        CHECK (
            (
                source = 'PUBLIC_CODE'
                AND owner_customer_id IS NULL
                AND code IS NOT NULL
            )
            OR
            (
                source = 'SYSTEM_REWARD'
                AND owner_customer_id IS NOT NULL
                AND code IS NULL
            )
        ),
    ADD CONSTRAINT vouchers_milestone_check
        CHECK (milestone >= 0);

-- Rating moderation: hiding affects visibility, never average aggregation.
ALTER TABLE ratings
    ADD COLUMN IF NOT EXISTS is_hidden boolean NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS hidden_at timestamptz,
    ADD COLUMN IF NOT EXISTS hidden_by bigint,
    ADD COLUMN IF NOT EXISTS hidden_reason varchar(500);

DROP INDEX IF EXISTS idx_ratings_visible_ratee;
CREATE INDEX idx_ratings_visible_ratee
    ON ratings (ratee_id, created_at DESC)
    WHERE is_hidden = false;

DROP INDEX IF EXISTS idx_ratings_admin_created;
CREATE INDEX idx_ratings_admin_created
    ON ratings (created_at DESC);