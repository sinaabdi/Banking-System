from pydantic import BaseModel

class SummaryEntry(BaseModel):
    type: str
    direction: str
    count: int
    total_amount: int