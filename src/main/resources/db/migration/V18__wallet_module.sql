CREATE TABLE wallets (
 id bigserial PRIMARY KEY,
 user_id bigint NOT NULL,
 user_type varchar(20) NOT NULL CHECK (user_type IN ('DRIVER','CUSTOMER')),
 wallet_type varchar(20) NOT NULL CHECK (wallet_type IN ('CREDIT','CASH','CUSTOMER')),
 balance bigint NOT NULL DEFAULT 0 CHECK (balance >= 0),
 UNIQUE(user_id,user_type,wallet_type),
 CHECK ((user_type = 'DRIVER' AND wallet_type IN ('CREDIT','CASH')) OR
        (user_type = 'CUSTOMER' AND wallet_type = 'CUSTOMER'))
);
INSERT INTO wallets(user_id,user_type,wallet_type,balance)
 SELECT id,'DRIVER','CREDIT',COALESCE(credit_balance,0) FROM drivers;
INSERT INTO wallets(user_id,user_type,wallet_type,balance)
 SELECT id,'DRIVER','CASH',COALESCE(income_balance,0) FROM drivers;
INSERT INTO wallets(user_id,user_type,wallet_type,balance)
 SELECT id,'CUSTOMER','CUSTOMER',COALESCE(wallet_balance,0) FROM customers;

CREATE TABLE withdrawal_requests (
 id bigserial PRIMARY KEY,
 driver_id bigint NOT NULL REFERENCES drivers(id),
 amount bigint NOT NULL CHECK(amount > 0),
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','SUCCESS','FAILED')),
 idempotency_key varchar(100) NOT NULL,
 reviewed_by bigint,
 reviewed_at timestamp,
 created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(driver_id,idempotency_key),
 CHECK ((status = 'PENDING' AND reviewed_by IS NULL AND reviewed_at IS NULL) OR
        (status <> 'PENDING' AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);
CREATE INDEX idx_withdrawals_driver_created ON withdrawal_requests(driver_id,created_at DESC);
ALTER TABLE wallet_transactions ADD COLUMN transaction_type varchar(30);
UPDATE wallet_transactions SET transaction_type = CASE type
 WHEN 'DEPOSIT' THEN 'TOP_UP' WHEN 'TRIP_PAYMENT' THEN 'RIDE_PAYMENT'
 WHEN 'TRIP_EARNING' THEN 'RIDE_PAYMENT' ELSE type END;
ALTER TABLE wallet_transactions ALTER COLUMN transaction_type SET NOT NULL;
ALTER TABLE wallet_transactions ADD CONSTRAINT ck_wallet_transaction_type
 CHECK(transaction_type IN ('TOP_UP','WITHDRAW','RIDE_PAYMENT','COMMISSION_FEE','REFUND'));
ALTER TABLE wallet_transactions ADD COLUMN withdrawal_id bigint REFERENCES withdrawal_requests(id);
CREATE UNIQUE INDEX uq_withdrawal_ledger ON wallet_transactions(withdrawal_id,transaction_type);
-- Public ledger aliases preserve compatibility with the existing mobile API.
ALTER TABLE wallet_transactions ADD COLUMN transaction_id bigint GENERATED ALWAYS AS (id) STORED;
ALTER TABLE wallet_transactions ADD COLUMN user_id bigint GENERATED ALWAYS AS (COALESCE(driver_id,customer_id)) STORED;
ALTER TABLE wallet_transactions ADD COLUMN user_type varchar(20) GENERATED ALWAYS AS
 (CASE WHEN driver_id IS NOT NULL THEN 'DRIVER' ELSE 'CUSTOMER' END) STORED;
ALTER TABLE wallet_transactions ADD COLUMN reference_trip_id bigint GENERATED ALWAYS AS (ride_id) STORED;
CREATE TABLE ride_wallet_settlements (
 ride_id bigint PRIMARY KEY REFERENCES rides(id),
 created_at timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE wallet_transactions DROP CONSTRAINT chk_wallet_type_owner;
ALTER TABLE wallet_transactions DROP CONSTRAINT chk_wallet_type;
UPDATE wallet_transactions SET wallet_type = CASE wallet_type
 WHEN 'INCOME' THEN 'CASH' WHEN 'CUSTOMER_WALLET' THEN 'CUSTOMER' ELSE wallet_type END;
ALTER TABLE wallet_transactions ADD CONSTRAINT chk_wallet_type CHECK(wallet_type IN ('CREDIT','CASH','CUSTOMER'));
ALTER TABLE wallet_transactions ADD CONSTRAINT chk_wallet_type_owner CHECK
 ((customer_id IS NOT NULL AND driver_id IS NULL AND wallet_type = 'CUSTOMER') OR
 (driver_id IS NOT NULL AND customer_id IS NULL AND wallet_type IN ('CREDIT','CASH')));
