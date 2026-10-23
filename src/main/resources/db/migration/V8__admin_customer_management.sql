CREATE TABLE admin_customer_actions (
    id bigserial PRIMARY KEY,
    customer_id bigint NOT NULL REFERENCES customers(id),
    admin_id bigint NOT NULL REFERENCES admins(id),
    action varchar(20) NOT NULL,
    previous_status varchar(20),
    new_status varchar(20),
    previous_flagged boolean,
    new_flagged boolean,
    note text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_admin_customer_actions_action CHECK (action IN ('STATUS', 'FLAG'))
);

CREATE INDEX idx_admin_customer_actions_customer_created
    ON admin_customer_actions (customer_id, created_at DESC);
