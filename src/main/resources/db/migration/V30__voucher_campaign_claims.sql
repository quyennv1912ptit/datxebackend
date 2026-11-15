ALTER TABLE vouchers
    ADD COLUMN IF NOT EXISTS description varchar(500) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS campaign_id bigint REFERENCES vouchers(id),
    ADD COLUMN IF NOT EXISTS campaign_claim_count integer NOT NULL DEFAULT 0 CHECK (campaign_claim_count >= 0),
    ADD COLUMN IF NOT EXISTS campaign_budget_limit bigint;

CREATE UNIQUE INDEX IF NOT EXISTS uq_voucher_campaign_customer_claim
    ON vouchers(campaign_id, owner_customer_id)
    WHERE campaign_id IS NOT NULL AND owner_customer_id IS NOT NULL;

ALTER TABLE promo_budget_ledger
    ADD COLUMN IF NOT EXISTS campaign_id bigint REFERENCES vouchers(id);

CREATE TABLE IF NOT EXISTS voucher_campaign_budget (
    campaign_id bigint PRIMARY KEY REFERENCES vouchers(id),
    budget_limit bigint NOT NULL CHECK (budget_limit >= 0),
    held_amount bigint NOT NULL DEFAULT 0 CHECK (held_amount >= 0),
    spent_amount bigint NOT NULL DEFAULT 0 CHECK (spent_amount >= 0),
    CHECK (held_amount + spent_amount <= budget_limit)
);
