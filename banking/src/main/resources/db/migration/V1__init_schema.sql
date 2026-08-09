CREATE TABLE IF NOT EXISTS users (
    id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    first_name VARCHAR(20) NOT NULL,
    last_name VARCHAR(20) NOT NULL,
    username VARCHAR(20) NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'DISABLED', 'DELETED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS accounts (
    id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users (id),
    account_number BIGINT NOT NULL UNIQUE,
    account_type VARCHAR(10) NOT NULL CHECK (account_type IN ('CHECKING', 'SAVINGS', 'SYSTEM')),
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_accounts_user_id ON accounts (user_id);

CREATE TABLE IF NOT EXISTS transactions (
    id INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    type VARCHAR(10) NOT NULL CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'FEE', 'REVERSAL')),
    status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING', 'POSTED', 'FAILED', 'REVERSED')),
    idempotency_key TEXT NOT NULL UNIQUE,
    reversed_transaction_id INTEGER REFERENCES transactions (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS ledger_entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id INTEGER NOT NULL REFERENCES transactions (id),
    account_id INTEGER NOT NULL REFERENCES accounts (id),
    direction VARCHAR(10) NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ledger_entries_transaction_id ON ledger_entries (transaction_id);
CREATE INDEX IF NOT EXISTS idx_ledger_entries_account_id ON ledger_entries (account_id);
