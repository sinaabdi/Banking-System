from enum import StrEnum
from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.database import Base

import datetime

class TransactionDirection(StrEnum):
    DEBIT = 'DEBIT'
    CREDIT = 'CREDIT'

class TransactionType(StrEnum):
    TRANSFER = 'TRANSFER'
    DEPOSIT = 'DEPOSIT'
    WITHDRAWAL = 'WITHDRAWAL'
    FEE = 'FEE'
    REVERSAL = 'REVERSAL'

class TransactionStatus(StrEnum):
    PENDING = 'PENDING'
    POSTED = 'POSTED'
    FAILED = 'FAILED'
    REVERSED = 'REVERSED'

class Transaction(Base):
    __tablename__ = 'transactions'

    id: Mapped[int] = mapped_column(primary_key=True)
    type: Mapped[TransactionType] = mapped_column(name='type', nullable=False)
    status: Mapped[TransactionStatus] = mapped_column(name='status', nullable=False)
    idempotency_key: Mapped[str] = mapped_column(name='idempotency_key', nullable=False, unique=True)
    reversed_transaction_id: Mapped[int] = mapped_column(name='reversed_transaction_id', nullable=True)
    created_at: Mapped[datetime.datetime] = mapped_column(name='created_at')
    updated_at: Mapped[datetime.datetime] = mapped_column(name='updated_at')
    ledger_entries: Mapped[list['LedgerEntry']] = relationship(back_populates='transaction')