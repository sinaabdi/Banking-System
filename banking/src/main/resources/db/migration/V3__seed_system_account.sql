CREATE UNIQUE INDEX IF NOT EXISTS uq_system_account_per_currency
    ON accounts(currency)
    WHERE account_type = 'SYSTEM';

INSERT INTO users (first_name, last_name, username, password_hash, email, status)
VALUES ('Bank', 'Operations', 'system', 'unusable', 'system@bank.internal', 'ACTIVE');

INSERT INTO accounts (user_id, account_number, account_type, currency, status, version)
SELECT id,account_number,'SYSTEM', currency, 'ACTIVE', 0
FROM users
CROSS JOIN (VALUES
    (1, 'USD'),
    (2, 'EUR'),
    (3, 'GBP'),
    (4, 'JPY'),
    (5, 'CAD'),
    (6, 'AUD'),
    (7, 'CHF')
) AS system_currencies(account_number, currency)
WHERE username = 'system';