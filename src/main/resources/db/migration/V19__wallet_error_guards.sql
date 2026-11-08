ALTER TABLE wallets DROP CONSTRAINT wallets_balance_check;
ALTER TABLE wallets ADD CONSTRAINT wallets_balance_check CHECK(balance >= 0 OR wallet_type = 'CREDIT');
ALTER TABLE drivers DROP CONSTRAINT ck_drivers_credit_balance_nonnegative;
ALTER TABLE drivers DROP CONSTRAINT drivers_status_check;
ALTER TABLE drivers ADD CONSTRAINT drivers_status_check CHECK(status IN ('OFFLINE','ONLINE','BUSY','BLOCKED'));

CREATE FUNCTION provision_account_wallets() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME = 'drivers' THEN
   INSERT INTO wallets(user_id,user_type,wallet_type,balance) VALUES
    (NEW.id,'DRIVER','CREDIT',COALESCE(NEW.credit_balance,0)),
    (NEW.id,'DRIVER','CASH',COALESCE(NEW.income_balance,0));
 ELSE
   INSERT INTO wallets(user_id,user_type,wallet_type,balance)
    VALUES (NEW.id,'CUSTOMER','CUSTOMER',COALESCE(NEW.wallet_balance,0));
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER provision_driver_wallets AFTER INSERT ON drivers FOR EACH ROW EXECUTE FUNCTION provision_account_wallets();
CREATE TRIGGER provision_customer_wallets AFTER INSERT ON customers FOR EACH ROW EXECUTE FUNCTION provision_account_wallets();
