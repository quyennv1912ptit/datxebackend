CREATE TABLE customer_mock_topups (
 customer_id bigint NOT NULL REFERENCES customers(id),
 request_key varchar(100) NOT NULL,
 amount bigint NOT NULL CHECK(amount > 0 AND amount <= 50000000),
 transaction_id bigint NOT NULL UNIQUE REFERENCES wallet_transactions(id),
 PRIMARY KEY(customer_id,request_key)
);
