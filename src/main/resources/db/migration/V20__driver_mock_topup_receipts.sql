CREATE TABLE driver_mock_topups (
 driver_id bigint NOT NULL REFERENCES drivers(id),
 request_key varchar(100) NOT NULL,
 amount bigint NOT NULL CHECK(amount > 0 AND amount <= 50000000),
 transaction_id bigint NOT NULL UNIQUE REFERENCES wallet_transactions(id),
 PRIMARY KEY(driver_id,request_key)
);
