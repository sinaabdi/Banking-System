## Database

`USER`
```
id                PK
first_name
last_name
username          unique
password_hash     -- BCrypt hash, via Spring Security's PasswordEncoder; never stored/logged raw
email             unique
status            (ACTIVE/DISABLED/DELETED)
created_at
updated_at
```

`ACCOUNT`
```
id                PK
user_id           FK -> USER, not null -- even SYSTEM accounts have an owning user (a seeded "bank operations" user)
account_number    unique -- a DB sequence value with a Luhn check digit appended
account_type      (CHECKING/SAVINGS/SYSTEM)
currency
status            (ACTIVE/FROZEN/CLOSED)
version           -- optimistic lock; protects direct mutations to this row (freeze/close/activate) only.
                  -- does NOT protect the withdraw/transfer balance check - see Concurrency below.
created_at
updated_at
```

`TRANSACTION`
```
id                        PK
type                      (TRANSFER/DEPOSIT/WITHDRAWAL/FEE/REVERSAL) -- FEE is defined but not yet wired to any operation
status                    (PENDING/POSTED/FAILED/REVERSED)           -- FAILED is defined but no code path sets it yet
idempotency_key           unique, not null
reversed_transaction_id   FK -> TRANSACTION, nullable, self-referencing -- set on the reversal, pointing back at what it reverses
created_at
updated_at
```

`LEDGER_ENTRY`
```
id                 PK
transaction_id     FK -> TRANSACTION
account_id         FK -> ACCOUNT
direction          (CREDIT/DEBIT)
amount             -- Long, minor units (e.g. cents) to avoid floating-point rounding; always positive, direction carries the sign
currency
created_at         -- no updated_at, no setters: append-only. A mistake is corrected by posting a
                   -- new reversal, never by editing an existing entry.
```

Balance is never stored - it's always derived as `sum(CREDIT) - sum(DEBIT)` over an account's ledger entries, so it can never drift out of sync with the transaction history that produced it.

## Services

`USER` (UserService)
```
createUser              -- hashes the password before storing; rejects duplicate username/email
updateUser               -- updates first name, last name, email (not username)
changePassword            -- verifies current password via PasswordEncoder.matches, hashes the new one
disableUser
enableUser
getUserById
getUserByEmail
getUserByUsername
getAllUsers
```

`ACCOUNT` (AccountService)
```
createAccount              -- generates the account number from a DB sequence + Luhn check digit
freezeAccount
closeAccount
activeAccount              -- reactivates a frozen/closed account
getBalance                 -- computed from LEDGER_ENTRY, not stored
getAccountByAccountId
getAccountByAccountNumber
getAccountsForUser
```

`TRANSACTION` (TransactionService)

No generic "create transaction" - each operation is its own method with its own validation:
```
deposit    -- credits the account, debits the SYSTEM account for that currency (money entering
           -- the ledger from outside the bank)
withdraw   -- debits the account, credits the SYSTEM account; rejects if the amount exceeds the
           -- current balance; locks the account row for the duration (see Concurrency)
transfer   -- debits the source, credits the destination, no SYSTEM account involved; both
           -- accounts are locked, always in a fixed order, to avoid deadlock; only the source's
           -- balance can ever reject a transfer
reverse    -- posts a new REVERSAL transaction whose entries mirror the original's with direction
           -- flipped; only a POSTED, not-already-reversed, non-REVERSAL-type transaction is eligible
getTransactionById
getTransactionByIdempotencyKey
```

`deposit`/`withdraw`/`transfer`/`reverse` all take a client-supplied `idempotency_key` and check for an existing transaction with that key first - a retried request returns the original result instead of creating a duplicate.

`LEDGER_ENTRY`

No dedicated service - accessed directly via `LedgerEntryRepository` from `AccountService`/`TransactionService`:
```
computeBalanceForAccount  -- the balance-derivation query
findByTransactionId       -- used by reverse to find the entries to mirror
```

## Concurrency

Two different locks protect two different things:

- **`@Version` (optimistic)** on `ACCOUNT` protects direct mutations to that row - `freeze`/`close`/`activate`.
- **A pessimistic row lock** (`findByIdForUpdate`, `SELECT ... FOR UPDATE`) protects the `withdraw`/`transfer` balance check. `@Version` alone can't do this job: balance is derived from `LEDGER_ENTRY`, not stored on `ACCOUNT`, so nothing ever writes to the account row during a withdrawal for `@Version` to catch. The row lock is acquired *before* computing the balance and held until the transaction commits, so a second concurrent withdrawal on the same account has to wait its turn rather than reading the same stale balance and overdrawing it.
- `transfer` locks **both** accounts, always in ascending id order regardless of which is source and which is destination - otherwise two transfers between the same two accounts in opposite directions could deadlock, each holding one lock while waiting on the other's.

A concurrency test (`TransactionServiceConcurrencyTest`) fires many simultaneous withdrawals at the same account against a real Postgres instance and asserts the final balance is exactly what it should be - this is the one thing the Mockito unit tests can't prove, since there's no real locking to verify without a real database and real threads.

## Error handling

A single `GlobalExceptionHandler` maps exceptions to HTTP responses: `NoSuchElementException` -> 404, `IllegalArgumentException` -> 400, anything else -> 500 (logged server-side with full detail; the caller only sees a generic message).

## API docs

`springdoc-openapi` is wired in - Swagger UI at `/swagger-ui/index.html`, raw OpenAPI spec at `/v3/api-docs`.
