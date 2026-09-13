FROM python:3.12-alpine

WORKDIR /app

COPY requirements.txt ./

RUN pip install --no-cache-dir -r requirements.txt

COPY app/ ./app/

EXPOSE 9093

RUN adduser -D app
USER app

CMD [ "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "9093" ]