from sqlalchemy import create_engine
from sqlalchemy.orm import DeclarativeBase, sessionmaker

import os
import psycopg2

class Base(DeclarativeBase):
    pass



def get_database_url() -> str:
    host = os.environ.get('DB_URL')
    if host == "" or host == None:
        host = 'localhost'

    port = os.environ.get('DB_PORT')
    if port == "" or port == None:
        port = "5432"

    db_name = os.environ.get('DB_NAME')
    if db_name == "" or db_name == None:
        db_name = 'core_banking'

    username = os.environ.get('DB_USERNAME')
    if username == "" or username == None:
        username = 'postgres'

    password = os.environ.get('DB_PASSWORD')
    if password == "" or password == None:
        password = 'postgres'

    return 'postgresql+psycopg2://' + username + ':' + password + '@' + host + ':' + port + '/' + db_name


engine = create_engine(url=get_database_url())

SessionLocal = sessionmaker(autoflush=False, bind=engine)

def get_db():
    with SessionLocal() as db:
        yield db