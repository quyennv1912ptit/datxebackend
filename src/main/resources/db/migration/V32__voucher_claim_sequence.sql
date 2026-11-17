ALTER TABLE vouchers
    ADD COLUMN IF NOT EXISTS claim_sequence INT NULL;

WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (PARTITION BY campaign_id ORDER BY id ASC) AS seq
    FROM vouchers
    WHERE campaign_id IS NOT NULL
)
UPDATE vouchers v
SET claim_sequence = ranked.seq
FROM ranked
WHERE v.id = ranked.id
  AND v.claim_sequence IS NULL;