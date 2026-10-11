FROM python:3.12.12-slim-bookworm
RUN apt-get update && apt-get install -y --no-install-recommends libseccomp2 antiword=0.37-16 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY services/document-worker/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY services/document-worker/parser.py services/document-worker/worker.py ./
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1
CMD ["python", "worker.py"]
