from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from app.main import app
from app.db.database import Base, get_db
from app.models.user import User, UserRole, UserStatus
from app.models.account import Account, AccountStatus, AccountType
from app.models.transaction import (
    Transaction,
    TransactionDirection,
    TransactionStatus,
    TransactionType,
)
from app.models.ledger_entry import LedgerEntry

import os
import pytest
import httpx2
import datetime

# Create tables
@pytest.fixture
def engine():
    host = os.environ.get("TEST_DB_URL")
    if host == "" or host == None:
        host = "localhost"

    port = os.environ.get("TEST_DB_PORT")
    if port == "" or port == None:
        port = "5432"

    test_db_name = os.environ.get("TEST_DB_NAME")
    if test_db_name == "" or test_db_name == None:
        test_db_name = "test_core_banking"

    username = os.environ.get("TEST_DB_USERNAME")
    if username == "" or username == None:
        username = "postgres"

    password = os.environ.get("TEST_DB_PASSWORD")
    if password == "" or password == None:
        password = "postgres"

    url = (
        "postgresql+psycopg2://"
        + username
        + ":"
        + password
        + "@"
        + host
        + ":"
        + port
        + "/"
        + test_db_name
    )

    return create_engine(url=url)


@pytest.fixture
def db(engine):
    SessionLocal = sessionmaker(autoflush=False, expire_on_commit=False, bind=engine)
    Base.metadata.create_all(engine)

    with SessionLocal() as db:
        yield db


# Create test client
@pytest.fixture
def client(db):
    app.dependency_overrides[get_db] = lambda: db
    with TestClient(app) as test_client:
        yield test_client


@pytest.fixture
def seeded_account(db):
    user = get_mock_user("testfirstname", "testlastname", "testuser", "test@bank.local")

    db.add(user)
    db.commit()
    db.refresh(user)

    account = get_mock_account(user_id=user.id, account_number=123456789)

    db.add(account)
    db.commit()
    db.refresh(account)

    transaction = get_mock_transaction()

    db.add(transaction)
    db.commit()
    db.refresh(transaction)

    ledger_entry_credit = get_mock_ledger_entries(account_id=account.id, transaction_id=transaction.id, direction=TransactionDirection.CREDIT)
    ledger_entry_debit = get_mock_ledger_entries(account_id=account.id, transaction_id=transaction.id, direction=TransactionDirection.DEBIT)

    db.add(ledger_entry_credit)
    db.add(ledger_entry_debit)
    db.commit()
    db.refresh(ledger_entry_credit)
    db.refresh(ledger_entry_debit)

    yield account

    db.delete(ledger_entry_debit)
    db.delete(ledger_entry_credit)
    db.commit()

    db.delete(transaction)
    db.commit()

    db.delete(account)
    db.commit()

    db.delete(user)
    db.commit()

@pytest.fixture
def seeded_account_no_balance(db):
    user = get_mock_user("nobalanceuser", "lastname", "testusername", "nobalance@bank.local")
    
    db.add(user)
    db.commit()
    db.refresh(user)

    account = get_mock_account(user_id=user.id, account_number=741852963)

    db.add(account)
    db.commit()
    db.refresh(account)

    yield account

    db.delete(account)
    db.commit()

    db.delete(user)
    db.commit()

@pytest.fixture
def seeded_statement_account(db):
    user = get_mock_user("testfirstname", "testlastname", "testuser", "test@bank.local")

    db.add(user)
    db.commit()
    db.refresh(user)

    account = get_mock_account(user_id=user.id, account_number=123456789)

    db.add(account)
    db.commit()
    db.refresh(account)

    transaction = get_mock_transaction()

    db.add(transaction)
    db.commit()
    db.refresh(transaction)

    ledger_entry_1 = get_mock_ledger_entries(account_id=account.id, transaction_id=transaction.id, amount=100, direction=TransactionDirection.CREDIT, min=1)
    ledger_entry_2 = get_mock_ledger_entries(account_id=account.id, transaction_id=transaction.id, amount=30, direction=TransactionDirection.DEBIT, min=4)
    ledger_entry_3 = get_mock_ledger_entries(account_id=account.id, transaction_id=transaction.id, amount=50, direction=TransactionDirection.DEBIT, min=5)
    ledger_entry_4 = get_mock_ledger_entries(account_id=account.id, transaction_id=transaction.id, amount=40, direction=TransactionDirection.CREDIT, min=10)

    db.add(ledger_entry_1)
    db.add(ledger_entry_2)
    db.add(ledger_entry_3)
    db.add(ledger_entry_4)
    db.commit()
    db.refresh(ledger_entry_1)
    db.refresh(ledger_entry_2)
    db.refresh(ledger_entry_3)
    db.refresh(ledger_entry_4)

    yield account

    db.delete(ledger_entry_1)
    db.delete(ledger_entry_2)
    db.delete(ledger_entry_3)
    db.delete(ledger_entry_4)
    db.commit()

    db.delete(transaction)
    db.commit()

    db.delete(account)
    db.commit()

    db.delete(user)
    db.commit()

@pytest.fixture
def seeded_summary_account(db):
    user = get_mock_user("testname", "testlast", "testest", "testest@bank.local")

    db.add(user)
    db.commit()
    db.refresh(user)

    account = get_mock_account(user_id=user.id, account_number=741852963)

    db.add(account)
    db.commit()
    db.refresh(account)

    deposit_transaction = get_mock_transaction(idempotency_key="idem-key-321", trasaction_type=TransactionType.DEPOSIT)

    db.add(deposit_transaction)
    db.commit()
    db.refresh(deposit_transaction)

    deposit_ledger = get_mock_ledger_entries(account_id=account.id, transaction_id=deposit_transaction.id, direction=TransactionDirection.CREDIT, amount=100)

    db.add(deposit_ledger)
    db.commit()
    db.refresh(deposit_ledger)

    deposit_transaction_2 = get_mock_transaction(idempotency_key="idem-key-456", trasaction_type=TransactionType.DEPOSIT)

    db.add(deposit_transaction_2)
    db.commit()
    db.refresh(deposit_transaction_2)

    deposit_ledger_2 = get_mock_ledger_entries(account_id=account.id, transaction_id=deposit_transaction_2.id, direction=TransactionDirection.CREDIT, amount=50)

    db.add(deposit_ledger_2)
    db.commit()
    db.refresh(deposit_ledger_2)

    withdrawal_transaction = get_mock_transaction(idempotency_key="idem-key-654", trasaction_type=TransactionType.WITHDRAWAL)
    
    db.add(withdrawal_transaction)
    db.commit()
    db.refresh(withdrawal_transaction)

    withdrawal_ledger = get_mock_ledger_entries(account_id=account.id, transaction_id=withdrawal_transaction.id, direction=TransactionDirection.DEBIT, amount=40)

    db.add(withdrawal_ledger)
    db.commit()
    db.refresh(withdrawal_ledger)

    yield account


    db.delete(deposit_ledger_2)
    db.delete(deposit_ledger)
    db.delete(withdrawal_ledger)
    db.commit()

    db.delete(deposit_transaction)
    db.delete(deposit_transaction_2)
    db.delete(withdrawal_transaction)
    db.commit()

    db.delete(account)
    db.commit()

    db.delete(user)
    db.commit()

def get_mock_user(first_name, last_name, username, email) -> User:
    return User(
        first_name=first_name,
        last_name=last_name,
        username=username,
        password_hash="$hash-password$",
        email=email,
        status=UserStatus.ACTIVE,
        role=UserRole.USER,
        created_at=datetime.datetime.now(datetime.timezone.utc),
        updated_at=datetime.datetime.now(datetime.timezone.utc),
    )

def get_mock_account(user_id: int, account_number: int) -> Account:
    return Account(
        user_id=user_id,
        account_number=account_number,
        account_type=AccountType.CHECKING,
        currency="USD",
        account_status=AccountStatus.ACTIVE,
        version=1,
        created_at=datetime.datetime.now(datetime.timezone.utc),
        updated_at=datetime.datetime.now(datetime.timezone.utc),
    )

def get_mock_transaction(idempotency_key: str = "idem-key-123", trasaction_type: TransactionType = TransactionType.DEPOSIT) -> Transaction:
    return Transaction(
        type=trasaction_type,
        status=TransactionStatus.POSTED,
        idempotency_key=idempotency_key,
        created_at=datetime.datetime.now(datetime.timezone.utc),
        updated_at=datetime.datetime.now(datetime.timezone.utc),
    )

def get_mock_ledger_entries(account_id: int, transaction_id: int, direction: TransactionDirection, min: int = 0, amount: int = None):
    now = datetime.datetime.now()
    if amount is None:
        amount = 100

    return LedgerEntry(
        transaction_id=transaction_id,
        account_id=account_id,
        transaction_direction=direction,
        amount=amount,
        currency="USD",
        created_at=now - datetime.timedelta(minutes=min)
    )