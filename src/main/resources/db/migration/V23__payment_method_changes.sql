CREATE TABLE payment_method_changes (
 id bigserial PRIMARY KEY,
 ride_id bigint NOT NULL REFERENCES rides(id),
 customer_id bigint NOT NULL REFERENCES customers(id),
 request_key varchar(100) NOT NULL,
 from_method varchar(20) NOT NULL CHECK(from_method IN ('CASH','WALLET')),
 to_method varchar(20) NOT NULL CHECK(to_method IN ('CASH','WALLET')),
 amount bigint NOT NULL CHECK(amount >= 0),
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(ride_id,customer_id,request_key), CHECK(from_method <> to_method)
);
