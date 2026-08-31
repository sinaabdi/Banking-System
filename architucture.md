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
role              (USER/ADMIN) -- see Authentication & Authorization below; no promotion endpoint
                  -- yet, so ADMIN is granted by hand in the DB (the seeded "system" user is ADMIN)
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

`AUTH` (AuthenticationService)
```
login    -- authenticates username/password via AuthenticationManager (which delegates to
         -- AppUserDetailsService + PasswordEncoder under the hood), then issues a JWT
```

## Authentication & Authorization

Stateless JWT auth - no server-side session store. A client logs in once, gets a signed token back,
and sends it as `Authorization: Bearer <token>` on every later request.

**How a request gets authenticated:**
1. `POST /api/auth/login` (`AuthenticationController` -> `AuthenticationService`) verifies the
   username/password via Spring Security's `AuthenticationManager`, which under the hood calls
   `AppUserDetailsService` (loads the `USER` row, throws `UsernameNotFoundException` if missing)
   and checks the password against the stored BCrypt hash. A `DISABLED` user is rejected here too -
   see `AppUserPrincipal.isEnabled()`.
2. On success, `JwtService` signs a JWT (HMAC, key from `jwt.secret`) containing the username and
   an expiry (`jwt.expiration-ms`). The token can't be revoked early - a leaked token is only as
   dangerous as however long is left until it expires, which is why the expiry is kept short.
3. Every subsequent request passes through `JwtAuthenticationFilter` (runs once per request, before
   the rest of the chain). It validates the token's signature and expiry, loads the corresponding
   `AppUserPrincipal` again via `AppUserDetailsService`, and populates `SecurityContext` - or, if the
   token is missing/invalid, just leaves the request unauthenticated and lets `SecurityConfig`'s
   `authorizeHttpRequests` rules decide whether that's allowed for the endpoint being hit.

**Roles**: `USER`/`ADMIN` on `USER.role`, exposed to Spring Security as a `ROLE_USER`/`ROLE_ADMIN`
authority (`AppUserPrincipal.getAuthorities()`). Bank-operations actions - freezing/closing/
activating an account, reversing a transaction, disabling/enabling a user, listing all users - are
`@PreAuthorize("hasRole('ADMIN')")`-gated; an `ADMIN` can also act on any other user's resources.

**Ownership**: everywhere else, a caller can only act on their own resources. Two different
mechanisms enforce this, depending on whether the identity being checked is already present in the
request or has to be looked up:
- Where the request already names a user id directly (a path variable like `UserController`'s
  `/{id}`, or a request-body field like `AccountController.createAccount`'s `userId`),
  `@PreAuthorize("#id == authentication.principal.id or hasRole('ADMIN')")`-style SpEL checks it
  against the authenticated caller with no database lookup at all.
- Where only an account/entity id is known (`AccountService.getAccountByAccountId`,
  `TransactionService.deposit`/`withdraw`/`transfer`), the row has to be loaded first to discover
  its owning user, then checked in the service layer (`checkAccountOwnershipOrThrow`) - an id alone
  carries no ownership information until the row behind it is read. A mismatch throws Spring
  Security's `AccessDeniedException`. `transfer` only checks the *source* account - the destination
  can belong to anyone, the same way paying another person works in any real bank.

## Concurrency

Two different locks protect two different things:

- **`@Version` (optimistic)** on `ACCOUNT` protects direct mutations to that row - `freeze`/`close`/`activate`.
- **A pessimistic row lock** (`findByIdForUpdate`, `SELECT ... FOR UPDATE`) protects the `withdraw`/`transfer` balance check. `@Version` alone can't do this job: balance is derived from `LEDGER_ENTRY`, not stored on `ACCOUNT`, so nothing ever writes to the account row during a withdrawal for `@Version` to catch. The row lock is acquired *before* computing the balance and held until the transaction commits, so a second concurrent withdrawal on the same account has to wait its turn rather than reading the same stale balance and overdrawing it.
- `transfer` locks **both** accounts, always in ascending id order regardless of which is source and which is destination - otherwise two transfers between the same two accounts in opposite directions could deadlock, each holding one lock while waiting on the other's.

A concurrency test (`TransactionServiceConcurrencyTest`) fires many simultaneous withdrawals at the same account against a real Postgres instance and asserts the final balance is exactly what it should be - this is the one thing the Mockito unit tests can't prove, since there's no real locking to verify without a real database and real threads.

## Error handling

A single `GlobalExceptionHandler` maps exceptions to HTTP responses: `NoSuchElementException` -> 404, `IllegalArgumentException` -> 400, `AuthenticationException` -> 401 (wrong credentials, unknown username, or a disabled account), `AccessDeniedException` -> 403 (a valid, authenticated caller who isn't the owner or an admin - see Authentication & Authorization above), anything else -> 500 (logged server-side with full detail; the caller only sees a generic message).

## API docs

`springdoc-openapi` is wired in - Swagger UI at `/swagger-ui/index.html`, raw OpenAPI spec at `/v3/api-docs`.
