ALTER TABLE voucher_usages ADD COLUMN IF NOT EXISTS discount_amount bigint NOT NULL DEFAULT 0;
ALTER TABLE vouchers ADD COLUMN IF NOT EXISTS forecast_reserve_per_use bigint NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS promo_budget (
  id integer PRIMARY KEY DEFAULT 1 CHECK (id = 1),
  budget_limit bigint NOT NULL DEFAULT 0 CHECK (budget_limit >= 0),
  held_amount bigint NOT NULL DEFAULT 0 CHECK (held_amount >= 0),
  issuance_reserved bigint NOT NULL DEFAULT 0 CHECK (issuance_reserved >= 0),
  spent_amount bigint NOT NULL DEFAULT 0 CHECK (spent_amount >= 0),
  updated_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO promo_budget (id) VALUES (1) ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS promo_budget_ledger (
  id bigserial PRIMARY KEY,
  ride_id bigint,
  voucher_id bigint,
  action varchar(20) NOT NULL CHECK (action IN ('BUDGET_SET','ISSUE_RESERVE','HOLD','SPEND','RELEASE')),
  amount bigint NOT NULL CHECK (amount >= 0),
  forecast_amount bigint NOT NULL DEFAULT 0 CHECK (forecast_amount >= 0),
  note varchar(500),
  created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_promo_ledger_ride_action
  ON promo_budget_ledger (ride_id, action) WHERE ride_id IS NOT NULL;
