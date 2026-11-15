ALTER TABLE voucher_campaign_budget
    ADD COLUMN IF NOT EXISTS released_amount bigint NOT NULL DEFAULT 0 CHECK (released_amount >= 0);

ALTER TABLE voucher_campaign_budget
    DROP CONSTRAINT IF EXISTS voucher_campaign_budget_check;

ALTER TABLE voucher_campaign_budget
    ADD CONSTRAINT voucher_campaign_budget_lifecycle_check
    CHECK (held_amount + spent_amount + released_amount <= budget_limit);
