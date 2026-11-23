ALTER TABLE withdrawal_requests
    ADD COLUMN bank_name_snapshot varchar(255),
    ADD COLUMN bank_account_number_snapshot varchar(255),
    ADD CONSTRAINT ck_withdrawal_bank_snapshot CHECK (
        (bank_name_snapshot IS NULL AND bank_account_number_snapshot IS NULL)
        OR (bank_name_snapshot IS NOT NULL AND bank_account_number_snapshot IS NOT NULL
            AND btrim(bank_name_snapshot) <> '' AND btrim(bank_account_number_snapshot) <> '')
    );

-- Treat values written by SQL defaults as UTC wall time without reinterpreting historical rows.
ALTER TABLE withdrawal_requests
    ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');
ALTER TABLE wallet_transactions
    ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC'),
    ALTER COLUMN updated_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');
ALTER TABLE ride_wallet_settlements
    ALTER COLUMN created_at SET DEFAULT (CURRENT_TIMESTAMP AT TIME ZONE 'UTC');
