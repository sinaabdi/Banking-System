from pydantic import BaseModel
import datetime

class StatementEntry(BaseModel):
    transaction_id: int
    direction: str
    amount: int
    currency: str
    created_at: datetime.datetime
    running_balance: int 