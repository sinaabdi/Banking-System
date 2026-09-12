import uvicorn
import app.models
import app.schemas
import datetime
import pandas as pd

from sqlalchemy.orm import Session
from sqlalchemy import select, func, case
from fastapi import FastAPI, Depends, Query, HTTPException, Response
from app.db.database import get_db
from typing import Literal
from app.models.ledger_entry import LedgerEntry
from app.models.transaction import TransactionDirection, Transaction
from app.models.account import Account
from app.schemas.statement_entry import StatementEntry
from app.schemas.summary_entry import SummaryEntry

app = FastAPI(title="Report and Analyze")


def calculate_balance_for_account(
    account_id: int, db: Session, date: datetime.date | None = None
) -> int:
    condition = []
    if date is not None:
        condition.append(LedgerEntry.created_at < date)

    stmt = select(
        func.sum(
            case(
                (
                    LedgerEntry.transaction_direction == TransactionDirection.CREDIT,
                    LedgerEntry.amount,
                ),
                else_=-LedgerEntry.amount,
            )
        )
    ).where(LedgerEntry.account_id == account_id, *condition)
    balance = db.execute(stmt).scalar()
    if balance is None:
        return 0
    return balance


@app.get("/accounts/{account_id}/balance")
def get_balance(account_id: int, db: Session = Depends(get_db)):
    return calculate_balance_for_account(account_id=account_id, db=db)


@app.get("/statements/{account_id}")
def get_statements(
    account_id: int,
    db: Session = Depends(get_db),
    from_date: datetime.date | None = Query(default=None, alias="from"),
    to_date: datetime.date | None = Query(default=None, alias="to"),
    format: Literal["json", "csv"] = Query(default="json"),
) -> list[StatementEntry]:

    if from_date is not None and to_date is not None and from_date > to_date:
        raise HTTPException(
            status_code=400, detail="'from' date must not be after 'to' date."
        )

    condition = []
    opening_balance = 0

    if from_date is not None:
        condition.append(LedgerEntry.created_at >= from_date)
        opening_balance = calculate_balance_for_account(
            account_id=account_id, db=db, date=from_date
        )
    if to_date is not None:
        condition.append(LedgerEntry.created_at < to_date)

    stmt = (
        select(
            LedgerEntry,
            func.sum(
                case(
                    (
                        LedgerEntry.transaction_direction
                        == TransactionDirection.CREDIT,
                        LedgerEntry.amount,
                    ),
                    else_=-LedgerEntry.amount,
                )
            )
            .over(order_by=LedgerEntry.created_at)
            .label("running_balance"),
        )
        .where(LedgerEntry.account_id == account_id, *condition)
        .order_by(LedgerEntry.created_at)
    )

    rows = db.execute(stmt).all()

    entries = []
    for r, running_balance in rows:
        entries.append(
            StatementEntry(
                transaction_id=r.transaction_id,
                direction=r.transaction_direction,
                amount=r.amount,
                currency=r.currency,
                created_at=r.created_at,
                running_balance=opening_balance + running_balance,
            )
        )

    if format == "csv":
        df = pd.DataFrame([entry.model_dump() for entry in entries])
        csv_string = df.to_csv(index=False)
        return Response(
            content=csv_string,
            media_type="text/csv",
            headers={"Content-Disposition": "attachment; filename=statement.csv"},
        )

    return entries


@app.get("/accounts/{account_id}/summary")
def get_account_summary(
    account_id: int,
    db: Session = Depends(get_db),
    from_date: datetime.date | None = Query(default=None, alias="from"),
    to_date: datetime.date | None = Query(default=None, alias="to"),
    format: Literal["json", "csv"] = Query(default="json"),
) -> list[SummaryEntry]:

    if from_date is not None and to_date is not None and from_date > to_date:
        raise HTTPException(
            status_code=400, detail="'from' date must not be after 'to' date."
        )

    condition = []

    if from_date is not None:
        condition.append(LedgerEntry.created_at >= from_date)
    if to_date is not None:
        condition.append(LedgerEntry.created_at < to_date)

    stmt = (
        select(
            Transaction.type,
            LedgerEntry.transaction_direction,
            func.count(LedgerEntry.transaction_direction).label("count"),
            func.sum(
                case(
                    (
                        LedgerEntry.transaction_direction
                        == TransactionDirection.CREDIT,
                        LedgerEntry.amount,
                    ),
                    else_=-LedgerEntry.amount,
                )
            ).label("total_amount"),
        )
        .join(Transaction, Transaction.id == LedgerEntry.transaction_id)
        .where(LedgerEntry.account_id == account_id, *condition)
        .group_by(Transaction.type, LedgerEntry.transaction_direction)
    )

    entries = []
    rows = db.execute(stmt).all()
    for r in rows:
        entries.append(
            SummaryEntry(
                type=r.type,
                direction=r.transaction_direction,
                count=r.count,
                total_amount=r.total_amount,
            )
        )

    if format == "csv":
        df = pd.DataFrame([entry.model_dump() for entry in entries])
        csv_string = df.to_csv(index=False)
        return Response(
            content=csv_string,
            media_type="text/csv",
            headers={"Content-Disposition": "attachment; filename=summary.csv"},
        )

    return entries


if __name__ == "__main__":
    uvicorn.run(app=app, host="localhost", port=9093)
