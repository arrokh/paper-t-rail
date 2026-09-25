# Paper T-Rail

Paper T-Rail helps researchers trace citation-backed claims to evidence in academic documents. Its report is a research triage aid, not certification or grading.

This runnable slice accepts an English text-based PDF, stores it locally, creates an immutable Analysis Run pinned to the PDF's SHA-256 and parser, claim-extractor, and reference-resolution configuration, durably queues work, parses citation structure with self-hosted GROBID, extracts heuristic Atomic Claims, conservatively resolves supported bibliography entries, and displays the persisted claims, inferred Citation Target associations, and bibliography-resolution report. Evidence retrieval and verification are not part of this slice.

## Run locally

Requirements: Docker Compose or a compatible Podman Compose endpoint, [`mise`](https://mise.jdx.dev/), and Git. The Sqitch CLI runs from a version-pinned container image; no host Sqitch installation is needed.

```sh
mise install
cp .env.example .env  # optional; committed local-only defaults also work
make dev
```

Open <http://127.0.0.1:3000>. `make dev` starts PostgreSQL + pgvector, Redis, MinIO, and the pinned GROBID service; deploys the Sqitch migrations; then starts the API, worker, and web app. GROBID has no host-published port and is reachable only on the private Compose network. The API and worker use the same Kotlin/Spring Boot image as separate processes. Redis Streams work is at-least-once; the transactional outbox, PostgreSQL inbox, and pending-message reclaim protect committed work from duplicate delivery and worker restart.

The UI pins heuristic extraction, local embeddings, mock System One, and recorded scholarly-metadata fixtures by default. Its workspace places Source Document upload and cursor-paginated persisted runs side by side, with the selected run's progress, parsed structure, and Evidence Coverage Report in a full-width card below. The worker verifies the stored source hash, calls self-hosted GROBID with both external consolidation options explicitly set to `0`, retains the exact raw TEI response in private run-scoped object storage, and persists parser provenance, sections, Citation Contexts, Citation Occurrences, Bibliography Entries, Atomic Claims with source spans, and all-to-all claim-to-target links within each Citation Context. The UI labels those links inferred/provisional. The worker then classifies unsupported bibliography types and resolves supported entries conservatively. Confirmed DOI identities and deterministic title/author/year matches map to Canonical Papers; ambiguous or below-threshold candidates remain unresolved. Crossref is disabled in the safe profile and cannot receive bibliographic metadata without explicit per-run consent. No external provider receives document content. Evidence retrieval and verification are not executed in this slice.

The web app uses project-owned shadcn/ui primitives, Tailwind CSS v4, and the Paper T-Rail semantic theme. See the [Web UI Design System](docs/ui-design-system.md) for component, accessibility, and responsive-layout guidance; `web/AGENTS.md` applies those rules to frontend changes.

The default `grobid/grobid:0.9.1-crf` image includes native linux/arm64 and linux/amd64 builds. Compose waits for GROBID's `/api/isalive` endpoint before starting the worker. Override `GROBID_IMAGE` and `GROBID_PARSER_VERSION` together when selecting a different self-hosted build. To use an externally managed private service instead, set `GROBID_BASE_URL` to an address reachable from the worker and set `GROBID_PARSER_VERSION` to the deployed version; public destinations are rejected. For example, Docker Desktop can use `http://host.docker.internal:8070` when GROBID runs on the host.

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
| `PAPER_MAX_GROBID_RESPONSE_BYTES` | `67108864` | Maximum TEI response bytes read before rejection and XML parsing |
| `PAPER_MIN_EXTRACTED_CHARACTERS` | `100` | Minimum text needed for language validation |
| `PAPER_MIN_LANGUAGE_CONFIDENCE` | `0.65` | Minimum English language-detection confidence |

Scanned PDFs without enough selectable text, unsupported languages, invalid PDFs, page/size-limit violations, and parser failures receive explicit API error codes and explanations. OCR is not performed.

### Conservative bibliography resolution

New runs snapshot the `title-author-year-weighted-edit-similarity-v1` score policy and configured threshold (`PAPER_REFERENCE_RESOLUTION_CONFIDENCE_THRESHOLD`, default `0.9`). This value is configurable and pinned per run; numeric calibration against a human-labeled fixture with near-miss decoys remains a separate release gate. A syntactically valid bibliography DOI is accepted only when the selected scholarly metadata provider confirms the same DOI; a miss or DOI mismatch stays unresolved rather than silently switching identities. Entries without a valid DOI use deterministic title/author/year matching, and ambiguous or below-threshold matches remain unresolved. Unsupported reference types terminate before metadata lookup.

The safe local provider is `recorded-fixtures`. Crossref is disabled by default. Enable it only after reviewing the exact deployment's current terms and updating the provider review; configure `CROSSREF_ENABLED=true`, `CROSSREF_ENABLEMENT_REVIEWED=true`, and a non-empty `CROSSREF_RETENTION_DISCLOSURE`. If an operator contact email is configured with `CROSSREF_CONTACT_EMAIL`, it is an additional disclosed/consented request category. Each run using Crossref must approve the categories shown in the UI; consent is not inherited from another run. Crossref receives only bibliography metadata (and the configured contact email), never Source Document text or PDF content.

## API

The Next.js server proxies same-origin `/api/v1/*` calls to the private API; browser code never needs a public API binding.

- `POST /api/v1/analysis-runs` — multipart `file` plus optional JSON `configuration`; validates, stores the Source Document, creates a `QUEUED` immutable Analysis Run, and commits its outbox event atomically.
- `GET /api/v1/providers` — enabled and classified provider choices grouped by role, plus stable data-category descriptions; disabled and unclassified providers are omitted.
- `GET /api/v1/analysis-runs?limit=25&cursor=...` — cursor-paginated recent persisted runs, ordered by creation time descending and ID descending; the response contains `items` and `nextCursor`.
- `GET /api/v1/analysis-runs/{id}` — persisted status, progress, source hash, and configuration snapshot. A `PARSED` run has persisted citation structure, Atomic Claims, inferred/provisional Citation Target links, and bibliography-resolution outcomes, but is not a completed Evidence Coverage Report; evidence verification has not run.
- `GET /api/v1/analysis-runs/{id}/parsed-document` — parser provenance, normalized source text, sections, Citation Contexts and their marker-to-reference targets, extracted Atomic Claims and source spans, inferred/provisional claim-to-target links, and Bibliography Entries with their current resolution status. Source offsets are zero-based/end-exclusive UTF-16 code-unit indexes into `normalizedSourceText`; the parser separates semicolons and clear contrastive clause connectors, and records sentence fallback when a clause boundary is ambiguous.
- `GET /api/v1/analysis-runs/{id}/report` — persisted resolution outcomes, Canonical Paper identities, match reasons/scores, and the Analysis Run's pinned policy version and configured threshold. A parsed run has no evidence verification.
- `POST /api/v1/documents/{id}/analysis-runs` — re-analyze the same stored Source Document as a new run.
- `GET /api/v1/health` — API/database liveness.

The OpenAPI 3 document is available at <http://127.0.0.1:8080/v3/api-docs> (YAML at `/v3/api-docs.yaml`) and Swagger UI at <http://127.0.0.1:8080/swagger-ui/index.html>. The API host port defaults to `8080`; configure `API_HOST_PORT` in `.env` if that loopback port is unavailable. Compose binds this published port only to `127.0.0.1`; do not expose it publicly. Swagger/OpenAPI endpoints are disabled in the worker process.

The upload form loads provider choices from `GET /api/v1/providers`; only enabled, classified providers are listed. Heuristic extraction, local embeddings, mock System One, and recorded scholarly-metadata fixtures are selected by default. Crossref stays disabled unless an operator explicitly enables the reviewed integration; the form then discloses its actual bibliographic metadata categories and requires fresh per-run approval. The immutable Analysis Run snapshot records the provider, score-policy version, configured confidence threshold, and provider/category consent. Every Crossref DOI or metadata-search request passes through the per-run provider-call gate immediately before HTTP dispatch, so missing consent results in no request. The current default sends no bibliographic metadata externally.

## Structured logs and request correlation

The Spring API and worker emit ECS-compatible JSON logs to stdout. The web API proxy emits JSON request-completion/failure records using the same ECS field conventions. Each request gets an `X-Request-ID`: the web proxy validates or generates it, forwards it to the API, and returns it in the response; the API also validates or generates the ID for direct requests. Search both services' logs by this ID to follow synchronous proxy/API work. Worker records include `analysisRunId`, `documentId`, `eventId`, `correlationId`, and `eventType`; outbox publisher records include `analysisRunId`, `eventId`, and `correlationId`.

Logs include request method, route/path, status, duration, and safe error type as applicable. Request bodies and query strings are not logged, and application logs must not include PDF, Source Document, claim, or evidence text. See the [technical design](docs/paper-t-rail-tech-design.md) for the API and observability conventions.

## Development and verification

Tool versions are pinned in `mise.toml` (Java 21, Gradle 8.14.3, Node.js 24.11.0, and pnpm 12.6.0). After `mise install`, install the web dependencies from the canonical frozen lockfile and run local web development with the pinned toolchain:

```sh
mise exec -- pnpm --dir web install --frozen-lockfile
mise exec -- pnpm --dir web dev
```

The web container also uses Node.js 24.11.0 and installs with `pnpm install --frozen-lockfile`. Docker bootstraps the exact pnpm version declared in `web/package.json` using Node's bundled npm because Node 24.11's Corepack cannot load pnpm 12's `.mjs` entry point; dependency installation and scripts still run through pnpm. `web/pnpm-workspace.yaml` preserves pnpm's default release-age check, exempting only the exact `hono@4.13.9` and `lucide-react@1.48.0` versions already pinned in the lockfile. It also explicitly keeps `unrs-resolver`'s install build script disabled, matching the prior install behavior. Use the repository Make targets for validation; each web target runs through the mise-pinned Node.js and pnpm versions.

```sh
make validate       # Kotlin/queue tests + web proxy tests, lint, typecheck, and production build
make test-web       # run the web proxy behavior tests only
make lint-web       # run web lint
make typecheck-web  # run web typecheck
make build-web      # create the web production build
make migrate        # deploy the Sqitch plan into the local Compose PostgreSQL service
make migrate:ls     # print Sqitch events with full change IDs (no pager)
make migrate:revert CHANGE=<change-id>  # revert that change and later migrations; interactive confirmation
make clean          # destructive: remove all local application volumes
```

The Sqitch IDs are database change IDs, not Git commit SHAs. `migrate:ls` shows the event history; pass a full 40-character change ID from it to `migrate:revert`. Sqitch reverts the selected change and all later changes, retains its interactive confirmation, and runs the migration revert scripts. This can delete persisted schema data (for example, bibliography-resolution outcomes); back up and review the target database before proceeding. Neither target removes Compose volumes.

`make validate` requires a Docker-compatible container runtime for its PostgreSQL/Redis integration tests. The tests execute the same schema migrations and exercise upload/run provenance, GROBID consolidation settings, DOI validation, deterministic matching and ambiguity abstention, unsupported-type precedence, Crossref consent gating with a no-request contract, persisted Canonical Paper/report output, parsing and source spans, citation-clause grouping and sentence fallback, qualifier-preserving claim extraction, per-run/context/span claim deduplication, all-to-all same-context target links with database-enforced context isolation, re-analysis, duplicate stream delivery, inbox idempotency, and reclaiming pending work with a replacement worker.

If using Podman, start its machine and export the Docker-compatible socket as `DOCKER_HOST` before Compose/Testcontainers commands. Podman's socket cannot be bind-mounted into Testcontainers' Ryuk cleanup container on some setups; in that case also set `TESTCONTAINERS_RYUK_DISABLED=true` when running tests. Testcontainers still stops the declared containers during normal test shutdown, but disabling Ryuk removes its crash-cleanup safeguard. The Podman machine must have enough memory for PostgreSQL, Redis, MinIO, and the JVM build/test process.
