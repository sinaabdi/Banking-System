from sqlalchemy.orm import Mapped, mapped_column, relationship
from app.db.database import Base
from enum import StrEnum

import datetime

class UserStatus(StrEnum):
    ACTIVE = 'ACTIVE'
    DISABLED = 'DISABLED'
    DELETED = 'DELETED'

class UserRole(StrEnum):
    USER = 'USER'
    ADMIN = 'ADMIN'

class User(Base):
    __tablename__ = 'users'

    id: Mapped[int] = mapped_column(primary_key=True)
    first_name: Mapped[str] = mapped_column(name='first_name', nullable=False)
    last_name: Mapped[str] = mapped_column(name='last_name', nullable=False)
    username: Mapped[str] = mapped_column(name='username', nullable=False)
    password_hash: Mapped[str] = mapped_column(name='password_hash', nullable=False)
    email: Mapped[str] = mapped_column(name='email', nullable=False)
    status: Mapped[UserStatus] = mapped_column(name='status', nullable=False)
    role: Mapped[UserRole] = mapped_column(name='role', nullable=False)
    created_at: Mapped[datetime.datetime] = mapped_column(name='created_at')
    updated_at: Mapped[datetime.datetime] = mapped_column(name='updated_at')
    accounts: Mapped[list['Account']] = relationship(back_populates='user')