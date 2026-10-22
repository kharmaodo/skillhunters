FROM python:3.12.12-slim-bookworm
WORKDIR /app
COPY services/embedding-worker/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt
COPY services/embedding-worker/manifest.json services/embedding-worker/download_model.py ./
RUN python download_model.py /models
COPY services/embedding-worker/engine.py services/embedding-worker/worker.py services/embedding-worker/MODEL_NOTICE.md services/embedding-worker/APACHE-2.0.txt ./
USER 10001:10001
ENV PYTHONDONTWRITEBYTECODE=1 TOKENIZERS_PARALLELISM=false
CMD ["python", "worker.py"]
