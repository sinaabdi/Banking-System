from fastapi.security import HTTPBearer, HTTPAuthorizationCredentials
from fastapi import Depends, HTTPException
from typing import Annotated
from sqlalchemy.orm import Session
from app.db.database import get_db
from app.models.account import Account

import os
import jwt


def get_secret():
    secret = os.environ.get("JWT_SECRET")
    if secret == "" or secret is None:
        raise jwt.PyJWTError("secret not found")
    return secret

def get_current_user(credentials: Annotated[HTTPAuthorizationCredentials, Depends(HTTPBearer())]):
    secret = get_secret()
    token = credentials.credentials

    try:
        payload = jwt.decode(token, secret, algorithms=["HS512"])
        return payload
    except jwt.PyJWTError as e:
        raise HTTPException(status_code=401, detail=f"failed to decode jwt token: {e}")

def require_account_access(account_id: int, current_user = Depends(get_current_user), db: Session = Depends(get_db)):
    if current_user["role"] == "ADMIN":
        return

    account = db.get(Account, account_id)
    if not account:
        raise HTTPException(status_code=404, detail="account does not exist")

    if account.user_id != current_user["user_id"]:
        raise HTTPException(status_code=403, detail="account does not belong to requester")

    