ARG PYTHON_IMAGE=python:3.11-slim-bookworm
ARG LAYA_SOURCE_COMMIT=23a17522aa4942da6cce53a995a275760320b691

FROM ${PYTHON_IMAGE} AS build
ARG LAYA_SOURCE_COMMIT
ENV PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends git \
    && rm -rf /var/lib/apt/lists/* \
    && python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# Match the upstream Laya v0.3.20 CPU-only Docker build, including Apple Silicon.
RUN pip install "torch==2.14.0" --index-url https://download.pytorch.org/whl/cpu \
    && pip install "laya[serve] @ git+https://github.com/NandhaKishorM/laya.git@${LAYA_SOURCE_COMMIT}" \
    && pip check

FROM ${PYTHON_IMAGE} AS runtime
ARG LAYA_SOURCE_COMMIT
LABEL org.opencontainers.image.title="Paper T-Rail Laya evaluation sidecar" \
    org.opencontainers.image.source="https://github.com/NandhaKishorM/laya" \
    org.opencontainers.image.revision="${LAYA_SOURCE_COMMIT}" \
    org.opencontainers.image.licenses="Apache-2.0"

ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    USE_TF=0 \
    USE_TORCH=1 \
    TOKENIZERS_PARALLELISM=false \
    OMP_NUM_THREADS=4 \
    LAYA_DEVICE=cpu \
    HF_HOME=/models/hf

RUN groupadd --gid 10001 laya \
    && useradd --uid 10001 --gid laya --create-home laya \
    && mkdir -p /models/hf /app \
    && chown -R laya:laya /models /app
COPY --from=build /opt/venv /opt/venv
COPY --chown=laya:laya infra/laya/api_key_auth.py /app/api_key_auth.py
COPY --chown=laya:laya infra/laya/download_model.py /app/download_model.py
COPY --chown=laya:laya infra/laya/benchmark_runtime.py /app/benchmark_runtime.py
COPY --chown=laya:laya infra/laya/laya_serve_preflight.py /app/laya_serve_preflight.py
COPY --chown=laya:laya infra/laya/serve.py /app/serve.py

USER laya
WORKDIR /app
CMD ["python", "/app/serve.py"]
