ALTER TABLE drivers ADD COLUMN account_status varchar(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE drivers ADD CONSTRAINT drivers_account_status_check CHECK (account_status IN ('ACTIVE','SUSPENDED','BANNED'));
ALTER TABLE drivers ADD COLUMN account_status_reason text;
ALTER TABLE drivers ADD COLUMN withdrawal_frozen boolean NOT NULL DEFAULT false;

CREATE TABLE IF NOT EXISTS admin_driver_actions (
    id bigserial PRIMARY KEY,
    driver_id bigint NOT NULL REFERENCES drivers(id),
    admin_id bigint NOT NULL REFERENCES admins(id),
    action_type varchar(40) NOT NULL,
    previous_status varchar(16),
    new_status varchar(16),
    withdrawal_frozen boolean,
    reason text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_admin_driver_actions_driver_created
    ON admin_driver_actions (driver_id, created_at DESC);
