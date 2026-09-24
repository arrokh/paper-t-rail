# Paper T-Rail

Paper T-Rail helps researchers trace citation-backed claims to evidence in academic documents. Its report is a research triage aid, not certification or grading.

This first runnable slice accepts an English text-based PDF, stores it locally, creates an immutable Analysis Run pinned to the PDF's SHA-256 and configuration, durably queues work, and displays persisted run progress. Citation extraction and later analysis stages are not part of this slice.

## Run locally

Requirements: Docker Compose or a compatible Podman Compose endpoint, [`mise`](https://mise.jdx.dev/), and Git. The Sqitch CLI runs from a version-pinned container image; no host Sqitch installation is needed.

```sh
mise install
cp .env.example .env  # optional; committed local-only defaults also work
make dev
```

Open <http://127.0.0.1:3000>. `make dev` starts PostgreSQL + pgvector, Redis, and MinIO; deploys the Sqitch migrations; then starts the API, worker, and web app. The API and worker use the same Kotlin/Spring Boot image as separate processes. Redis Streams work is at-least-once; the transactional outbox, PostgreSQL inbox, and pending-message reclaim protect committed work from duplicate delivery and worker restart.

The UI pins a safe local run configuration: heuristic extraction, local embeddings, and mock System One. This issue #3 slice only validates the PDF with PDFBox and has the worker verify the stored source hash before persisting `PROCESSING`; it does not yet execute extraction, embeddings, GROBID parsing, or verification. No external provider receives document content.

### Local network and data safety

There is no authentication. The web app is published only on `127.0.0.1:3000`; the API binds to a specific address on the private Compose bridge and is published to the host only at `127.0.0.1:${API_HOST_PORT:-8080}` for local API/Swagger access. PostgreSQL, Redis, and the MinIO console/API are published only on loopback. The API rejects wildcard and public-address binds. Do not change these bindings or expose this stack to a public/untrusted network before authentication and authorization exist.

The local Compose credentials are development-only. Uploaded PDFs and run metadata remain in local persistent volumes. Per-Source-Document deletion is not part of this slice (tracked by issue #14); until it is implemented, `make clean` is the only provided deletion operation and removes all local documents, runs, queue state, and stored objects.

### Configurable PDF limits

Set these in `.env`; every cap is enforced by rejection, never by truncating the source or parsed text:

| Variable | Default | Behavior |
|---|---:|---|
| `PAPER_MAX_UPLOAD_BYTES` | `52428800` | Maximum PDF upload size |
| `PAPER_MAX_REQUEST_SIZE` | `51MB` | Maximum complete multipart request size, including boundaries; raise it when increasing the PDF byte cap |
| `PAPER_MAX_PAGES` | `500` | Maximum parsed page count |
| `PAPER_MAX_EXTRACTED_CHARACTERS` | `5000000` | Maximum extracted text before rejection |
| `PAPER_MAX_EXTRACTED_CHARACTERS_PER_PAGE` | `100000` | Maximum extracted text on one page before rejection; bounds PDFBox per-page buffering |
| `PAPER_MIN_EXTRACTED_CHARACTERS` | `100` | Minimum text needed for language validation |
| `PAPER_MIN_LANGUAGE_CONFIDENCE` | `0.65` | Minimum English language-detection confidence |

Scanned PDFs without enough selectable text, unsupported languages, invalid PDFs, page/size-limit violations, and parser failures receive explicit API error codes and explanations. OCR is not performed.

## API

The Next.js server proxies same-origin `/api/v1/*` calls to the private API; browser code never needs a public API binding.

- `POST /api/v1/analysis-runs` — multipart `file` plus optional JSON `configuration`; validates, stores the Source Document, creates a `QUEUED` immutable Analysis Run, and commits its outbox event atomically.
- `GET /api/v1/providers` — enabled and classified provider choices grouped by role, plus stable data-category descriptions; disabled and unclassified providers are omitted.
- `GET /api/v1/analysis-runs` — list recent persisted runs.
- `GET /api/v1/analysis-runs/{id}` — persisted status, progress, source hash, and configuration snapshot.
- `POST /api/v1/documents/{id}/analysis-runs` — re-analyze the same stored Source Document as a new run.
- `GET /api/v1/health` — API/database liveness.

The OpenAPI 3 document is available at <http://127.0.0.1:8080/v3/api-docs> (YAML at `/v3/api-docs.yaml`) and Swagger UI at <http://127.0.0.1:8080/swagger-ui/index.html>. The API host port defaults to `8080`; configure `API_HOST_PORT` in `.env` if that loopback port is unavailable. Compose binds this published port only to `127.0.0.1`; do not expose it publicly. Swagger/OpenAPI endpoints are disabled in the worker process.

The upload form loads provider choices from `GET /api/v1/providers`; only enabled, classified providers are listed. Heuristic extraction, local embeddings, and mock System One are selected by default. External integrations remain disabled in the safe profile. If a reviewed external provider is enabled in a future profile, the form discloses its exact payload categories and requires fresh per-run approval for each; the immutable run snapshot records that provider/category consent. The outbound provider-call gate rejects disabled, unclassified, unselected, or unconsented requests before invoking the adapter's send action. The current upload-to-run slice has no external provider adapter, and local/mock runs send no document content externally.

## Structured logs and request correlation

The Spring API and worker emit ECS-compatible JSON logs to stdout. The web API proxy emits JSON request-completion/failure records using the same ECS field conventions. Each request gets an `X-Request-ID`: the web proxy validates or generates it, forwards it to the API, and returns it in the response; the API also validates or generates the ID for direct requests. Search both services' logs by this ID to follow synchronous proxy/API work. Worker records include `analysisRunId`, `documentId`, `eventId`, `correlationId`, and `eventType`; outbox publisher records include `analysisRunId`, `eventId`, and `correlationId`.

Logs include request method, route/path, status, duration, and safe error type as applicable. Request bodies and query strings are not logged, and application logs must not include PDF, Source Document, claim, or evidence text. See the [technical design](docs/paper-t-rail-tech-design.md) for the API and observability conventions.

## Development and verification

Tool versions are pinned in `mise.toml` (Java 21, Gradle 8.14.3, Node 22.19). Use `mise exec -- ...` or the Make targets so local commands use those versions.

```sh
make validate   # Kotlin/queue tests + web proxy tests, lint, typecheck, and production build
make test-web   # run the web proxy behavior tests only
make migrate    # deploy the Sqitch plan into the local Compose PostgreSQL service
make clean      # destructive: remove all local application volumes
```

`make validate` requires a Docker-compatible container runtime for its PostgreSQL/Redis integration tests. The tests execute the same schema migration SQL and exercise upload/run provenance, re-analysis, duplicate stream delivery, inbox idempotency, and reclaiming pending work with a replacement worker.

If using Podman, start its machine and export the Docker-compatible socket as `DOCKER_HOST` before Compose/Testcontainers commands. Podman's socket cannot be bind-mounted into Testcontainers' Ryuk cleanup container on some setups; in that case also set `TESTCONTAINERS_RYUK_DISABLED=true` when running tests. Testcontainers still stops the declared containers during normal test shutdown, but disabling Ryuk removes its crash-cleanup safeguard. The Podman machine must have enough memory for PostgreSQL, Redis, MinIO, and the JVM build/test process.
