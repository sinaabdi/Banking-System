## Database

`USER`
```
user_id          PK
first_name
last_name
username          unique
password_hash
email             unique
status            (active/disabled/deleted)
created_at
updated_at
```

`ACCOUNT`
```
account_id        PK
user_id           FK -> USER
account_number    unique
account_type      (checking/savings/system)
currency
status            (active/frozen/closed)
version           -- optimistic locking token
created_at
updated_at
```

`TRANSACTION`

```
transaction_id            PK
type                      (transfer/deposit/withdrawal/fee/reversal)
status                    (pending/posted/failed/reversed)
idempotency_key           unique, not null
reversed_transaction_id   FK -> TRANSACTION, nullable, self-referencing
created_at
updated_at
```
`LedgerEntry`
```
entry_id           PK
transaction_id     FK -> TRANSACTION
account_id         FK -> ACCOUNT
direction          (debit/credit)
amount             -- always positive; direction carries the sign
currency
created_at
```

## Services

`USER`

```
create_user
get_user_with_user_id
update_user
change_password
disable_user
enable_user
```

`ACCOUNT`
```
create_account_with_user_id
get_account_with_account_id
get_account_with_account_number
get_balance_with_account_id       -- computed from LEDGER_ENTRY, not stored
freeze_account
close_account
```

`TRANSACTION`
```
create_transaction                        
get_transaction_with_id
get_transaction_with_idempotency_key
get_transactions_for_account              
reverse_transaction
```

`LedgerEntry`
```
get_ledger_entry_with_id
get_ledger_entries_for_transaction
get_ledger_entries_for_account
```
