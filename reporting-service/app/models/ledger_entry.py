from app.models.transaction import TransactionDirection
from sqlalchemy.orm import Mapped, mapped_column, relationship
from sqlalchemy import ForeignKey
from app.db.database import Base

import datetime

class LedgerEntry(Base):
    __tablename__ = "ledger_entries"

    id: Mapped[int] = mapped_column(primary_key=True)
    transaction_id: Mapped[int] = mapped_column(ForeignKey('transactions.id'), nullable=False)
    transaction: Mapped['Transaction'] = relationship(back_populates="ledger_entries")
    account_id: Mapped[int] = mapped_column(ForeignKey('accounts.id'), nullable=False)
    account: Mapped['Account'] = relationship(back_populates="ledger_entries")
    transaction_direction: Mapped[TransactionDirection] = mapped_column(name='direction', nullable=False)
    # Always positive; direction alone carries the sign. Every balance/statement/summary
    # query in app/main.py assumes this and negates DEBIT rows itself.
    amount: Mapped[int] = mapped_column(name='amount', nullable=False)
    currency: Mapped[str] = mapped_column(name='currency', nullable=False)
    created_at: Mapped[datetime.datetime] = mapped_column(name='created_at')