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
    user = User(
        first_name="testfirstname",
        last_name="testlastname",
        username="testuser",
        password_hash="$hash-password$",
        email="test@bank.local",
        status=UserStatus.ACTIVE,
        role=UserRole.USER,
        created_at=datetime.datetime.now(datetime.timezone.utc),
        updated_at=datetime.datetime.now(datetime.timezone.utc),
    )

    db.add(user)
    db.commit()
    db.refresh(user)

    account = Account(
        user_id=user.id,
        account_number=123456789,
        account_type=AccountType.CHECKING,
        currency="USD",
        account_status=AccountStatus.ACTIVE,
        version=1,
        created_at=datetime.datetime.now(datetime.timezone.utc),
        updated_at=datetime.datetime.now(datetime.timezone.utc),
    )

    db.add(account)
    db.commit()
    db.refresh(account)

    transaction = Transaction(
        type=TransactionType.DEPOSIT,
        status=TransactionStatus.POSTED,
        idempotency_key="idem-key-123",
        created_at=datetime.datetime.now(datetime.timezone.utc),
        updated_at=datetime.datetime.now(datetime.timezone.utc),
    )

    db.add(transaction)
    db.commit()
    db.refresh(transaction)

    ledger_entry_credit = LedgerEntry(
        transaction_id=transaction.id,
        account_id=account.id,
        transaction_direction=TransactionDirection.CREDIT,
        amount=100,
        currency="USD",
        created_at=datetime.datetime.now(datetime.timezone.utc),
    )

    ledger_entry_debit = LedgerEntry(
        transaction_id=transaction.id,
        account_id=account.id,
        transaction_direction=TransactionDirection.DEBIT,
        amount=100,
        currency="USD",
        created_at=datetime.datetime.now(datetime.timezone.utc),
    )

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
