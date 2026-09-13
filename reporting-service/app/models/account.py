from sqlalchemy.orm import Mapped, mapped_column, relationship
from sqlalchemy import ForeignKey
from enum import StrEnum
from app.db.database import Base


import datetime

class AccountType(StrEnum):
    CHECKING = 'CHECKING'
    SAVINGS = 'SAVINGS'
    SYSTEM = 'SYSTEM'

class AccountStatus(StrEnum):
    ACTIVE = 'ACTIVE'
    FROZEN = 'FROZEN'
    CLOSED = 'CLOSED'

class Account(Base):
    __tablename__ = 'accounts'

    id: Mapped[int] = mapped_column(primary_key=True)
    user_id: Mapped[int] = mapped_column(ForeignKey('users.id'), nullable=False)
    user: Mapped['User'] = relationship(back_populates="accounts")
    account_number: Mapped[int] = mapped_column(name='account_number', nullable=False)
    account_type: Mapped[AccountType] = mapped_column(name='account_type', nullable=False)
    currency: Mapped[str] = mapped_column(name='currency', nullable=False)
    account_status: Mapped[AccountStatus] = mapped_column(name='status', nullable=False)
    version: Mapped[int] = mapped_column(name='version')
    created_at: Mapped[datetime.datetime] = mapped_column(name='created_at')
    updated_at: Mapped[datetime.datetime] = mapped_column(name='updated_at')
    ledger_entries: Mapped[list['LedgerEntry']] = relationship(back_populates='account')

