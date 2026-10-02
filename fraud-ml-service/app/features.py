import numpy as np
import datetime

TRANSACTION_TYPES = np.array(["TRANSFER", "DEPOSIT", "WITHDRAWAL", "FEE", "REVERSAL"])
KNOWN_CURRENCIES = np.array(['USD', 'EUR', 'GBP', 'JPY', 'CAD', 'AUD', 'CHF'])

def build_feature_vector(amount: float, currency: str, type_: str, has_counterparty: bool, received_at: datetime.datetime, velocity_count: int) -> np.ndarray:
    log_amount = np.log1p(amount)
    hour = received_at.hour
    hour_sin = np.sin((2 * hour * np.pi)/ 24)
    hour_cos = np.cos((2 * hour * np.pi)/ 24)

    is_weekend = 1 if received_at.weekday() >= 5 else 0

    type_onehot = TRANSACTION_TYPES == type_

    matches = KNOWN_CURRENCIES == currency 
    other = int(not np.any(matches)) 
    currency_onehot=np.append(matches,other)

    arr = [log_amount, hour_sin, hour_cos, is_weekend, type_onehot, currency_onehot, has_counterparty, velocity_count]
    return np.hstack(arr)

def build_feature_matrix(records: list[dict]) -> np.ndarray:
    feature_list = []
    for record in records:
        feature_list.append(
            build_feature_vector(
                amount=record["amount"], 
                currency=record["currency"],
                type_=record["type_"],
                has_counterparty=record["has_counterparty"],
                received_at=record["received_at"],
                velocity_count=record["velocity_count"]))
    return np.vstack(feature_list)
