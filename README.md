# Paper T-Rail

Paper T-Rail helps researchers trace citation-backed claims to evidence in academic documents. Its report is a research triage aid, not certification or grading.

This runnable slice accepts an English text-based PDF, stores it locally, creates an immutable Analysis Run pinned to the PDF's SHA-256, parser and claim-extractor identities, reference-resolution policy, and retrieval/embedding profile, durably queues work, parses citation structure with self-hosted GROBID, extracts heuristic Atomic Claims, conservatively resolves supported bibliography entries, and retrieves traceable Evidence Passages from eligible English Cited Paper assets. Researchers can inspect passages per Atomic Claim × Cited Reference and see the pinned asset, parser, language-detector, and retrieval provenance. Semantic Evidence Judgements against retrieved full-text passages have not run; access-based terminal outcomes (for example, abstract-only scope) may still be recorded.

## Run locally

Requirements: Docker Compose or a compatible Podman Compose endpoint, [`mise`](https://mise.jdx.dev/), and Git. The Sqitch CLI runs from a version-pinned container image; no host Sqitch installation is needed.

```sh
mise install
cp .env.example .env  # optional; committed local-only defaults also work
make dev
```

Open <http://127.0.0.1:3000>. `make dev` starts PostgreSQL + pgvector, Redis, MinIO, and the pinned GROBID service; deploys the Sqitch migrations; then starts the API, worker, and web app. For local development it also downloads/verifies the pinned Laya checkpoint in a separate one-shot step, generates a private API key in `.env` if needed, starts the CPU-only sidecar on the internal Compose network, and connects API/worker to it before those services start. This first Laya setup downloads roughly 1.7 GB of model artifacts. Set `LAYA_ENABLED=false` in `.env` to opt out; `make dev` preserves that explicit choice. Laya is available as an optional local provider, but mock remains the selected default and no Laya call occurs unless a run explicitly selects it. Spring's non-Compose fallback and production deployments remain disabled unless explicitly configured. GROBID and Laya have no host-published ports. The API and worker use the same Kotlin/Spring Boot image as separate processes. Redis Streams work is at-least-once; the transactional outbox, PostgreSQL inbox, and pending-message reclaim protect committed work from duplicate delivery and worker restart.

The UI pins heuristic extraction, local feature-hash embeddings, mock System One, and recorded scholarly-metadata fixtures by default. Its workspace places Source Document upload and cursor-paginated persisted runs side by side, with the selected run's progress, parsed structure, and Evidence Coverage Report in a full-width card below. The worker verifies the stored source hash, calls self-hosted GROBID with both external consolidation options explicitly set to `0`, retains the exact raw TEI response in private run-scoped object storage, and persists parser provenance, sections, Citation Contexts, Citation Occurrences, Bibliography Entries, Atomic Claims with source spans, and all-to-all claim-to-target links within each Citation Context. The UI labels those links inferred/provisional. The worker then classifies unsupported bibliography types and resolves supported entries conservatively. Confirmed DOI identities and deterministic title/author/year matches map to Canonical Papers; ambiguous or below-threshold candidates remain unresolved. Crossref and Unpaywall are offered in the repository's local provider catalog for consent-flow testing, but recorded fixtures remain selected by default; every external request requires explicit per-run consent for its actual data categories. No external provider receives Source Document content. For each eligible English Cited Paper, the worker integrity-checks and parses that run's exact acquired asset, creates section-aware chunks, combines pgvector and PostgreSQL full-text ranks with reciprocal-rank fusion, and stores bounded ranked passages per linked Atomic Claim × Cited Reference. The configured local feature-hash vectors are deterministic lexical features, not a trained semantic embedding model. The report and UI expose the exact source asset/hash, parser and language-detector versions, embedding profile, retrieval settings, candidate ranks, and fusion score. Mock System One remains the default; an optional, explicitly configured and selected Laya adapter is available for local evaluation only. Semantic judgements and final verification remain unexecuted in the default run profile.

The web app uses project-owned shadcn/ui primitives, Tailwind CSS v4, and the Paper T-Rail semantic theme. See the [Web UI Design System](docs/ui-design-system.md) for component, accessibility, and responsive-layout guidance; `web/AGENTS.md` applies those rules to frontend changes.

The default `grobid/grobid:0.9.1-crf` image includes native linux/arm64 and linux/amd64 builds. Compose waits for GROBID's `/api/isalive` endpoint before starting the worker. Override `GROBID_IMAGE` and `GROBID_PARSER_VERSION` together when selecting a different self-hosted build. To use an externally managed private service instead, set `GROBID_BASE_URL` to an address reachable from the worker and set `GROBID_PARSER_VERSION` to the deployed version; public destinations are rejected. For example, Docker Desktop can use `http://host.docker.internal:8070` when GROBID runs on the host.

### Local network and data safety

The application has no researcher authentication. The optional Laya sidecar separately requires an API key and is reachable only on its internal Compose network. The web app is published only on `127.0.0.1:3000`; the API binds to a specific address on the private Compose bridge and is published to the host only at `127.0.0.1:${API_HOST_PORT:-8080}` for local API/Swagger access. PostgreSQL, Redis, and the MinIO console/API are published only on loopback. The API rejects wildcard and public-address binds. Do not change these bindings or expose this stack to a public/untrusted network before authentication and authorization exist.

The local Compose credentials are development-only. Uploaded PDFs and run metadata remain in local persistent volumes until explicit deletion; there is no automatic expiry. The workspace requires confirmation before deleting a Source Document and all of its Analysis Runs and derived data. Deletion leaves only a content-free tombstone to prevent pending workers from restoring data, and cannot retract content already sent to external providers. Shared Cited Paper assets remain only while another non-deleted document/run references them. `make clean` remains the destructive whole-installation cleanup operation.

### Optional Laya local evaluation

The local Compose evaluation provider is enabled and offered after `make dev` generates its private key and verifies the checkpoint; it is not production-approved. Mock remains the selected default and manually selectable provider, and failures from a selected Laya adapter do not fall back to mock. The exact candidate is pinned in code and in the [provider matrix](docs/agents/provider-matrix.md): typed-decisions checkpoint at HF revision `1a793eb568e6718f15941d08f85432581df534e3`, `laya-serve` v0.3.20 at source commit `23a17522aa4942da6cce53a995a275760320b691`, with mapping `paper-trail-evidence-judgement-v1`. Spring's fallback in `application.yml` and non-Compose/production configuration remain disabled unless explicitly enabled.

The sidecar has no published host port. It requires a bearer API key on every route other than its container-local health check, and the inference network is internal-only. `make dev` creates or preserves an untracked `.env` with mode `0600`, generates a random 256-bit `LAYA_API_KEY` if none is set, separately downloads/verifies the fixed checkpoint, waits for the sidecar health check, then starts API/worker with the matching key. Do not put the key in a command, browser variable, issue, or log. Set `LAYA_ENABLED=false` in `.env` to opt out. To measure the running local sidecar, execute the optional synthetic smoke benchmark:

```sh
make benchmark-laya
```

The downloader contacts Hugging Face only for the pinned model artifacts and verifies the repository revision, tokenizer/config git-blob OIDs, model weight SHA-256, and 1,024-token configuration before writing the local manifest. The inference container loads that verified snapshot offline; it cannot download models. It uses CPU-only inference, including Apple Silicon. The benchmark sends synthetic claim/evidence text directly to the sidecar and reports latency, input/output tokens, and the server process's peak/current RSS. It is not a calibration result.

Only one claim and one evidence passage plus an optional section heading are sent for each judgement; no Source Document or unrelated passage is included. The complete state and each of six question sequences are counted with the checkpoint tokenizer before inference. Inputs beyond 1,024 tokens are rejected without truncation. Runtime, timeout, schema, and token-limit failures remain provider failures/incomplete work; there is no fallback. The normal Analysis Run profile still leaves aggregation/semantic judgement unexecuted pending calibration; starting this sidecar does not activate those stages. Human-reviewed in-domain calibration and the production decision remain tracked in [issue #45](https://github.com/arrokh/paper-t-rail/issues/45). For response mapping, failure semantics, and the recorded measurement, see [Laya local evaluation](docs/laya-evaluation.md).

### Configurable PDF limits

Set these in `.env`; every cap is enforced by rejection, never by truncating the source or parsed text:

| Variable | Default | Behavior |
|---|---:|---|
| `PAPER_MAX_UPLOAD_BYTES` | `52428800` | Maximum PDF upload size |
| `PAPER_MAX_REQUEST_SIZE` | `51MB` | Maximum complete multipart request size, including boundaries; raise it when increasing the PDF byte cap |
| `PAPER_MAX_PAGES` | `500` | Maximum parsed page count |
| `PAPER_MAX_CLAIM_CITATION_PAIRS` | `5000` | Maximum inferred Atomic Claim × Citation Target links; an over-limit queued run fails with an explicit reason before persisting parsed output |
| `PAPER_MAX_EXTRACTED_CHARACTERS` | `5000000` | Maximum extracted text before rejection |
| `PAPER_MAX_EXTRACTED_CHARACTERS_PER_PAGE` | `100000` | Maximum extracted text on one page before rejection; bounds PDFBox per-page buffering |
| `PAPER_MAX_GROBID_RESPONSE_BYTES` | `67108864` | Maximum TEI response bytes read before rejection and XML parsing |
| `PAPER_MIN_EXTRACTED_CHARACTERS` | `100` | Minimum text needed for language validation |
| `PAPER_MIN_LANGUAGE_CONFIDENCE` | `0.65` | Minimum English language-detection confidence |

The rationale, measured article/dissertation results, pinned runtime/provider matrix, and reproduction command are recorded in [the V1 runtime matrix](docs/benchmarks/v1-runtime-matrix.md); rerun the measurements with `make benchmark-processing`.

Scanned PDFs without enough selectable text, unsupported languages, invalid PDFs, page/size-limit violations, and parser failures receive explicit API error codes and explanations. OCR is not performed.

### Conservative bibliography resolution

New runs snapshot the `title-author-year-weighted-edit-similarity-v1` score policy and configured threshold (`PAPER_REFERENCE_RESOLUTION_CONFIDENCE_THRESHOLD`, default `0.9`). This value is configurable and pinned per run; numeric calibration against a human-labeled fixture with near-miss decoys remains a separate release gate. A syntactically valid bibliography DOI is accepted only when the selected scholarly metadata provider confirms the same DOI; a miss or DOI mismatch stays unresolved rather than silently switching identities. Entries without a valid DOI use deterministic title/author/year matching, and ambiguous or below-threshold matches remain unresolved. Unsupported reference types terminate before metadata lookup.

The safe selected provider is `recorded-fixtures`. The repository's local configuration enables and marks Crossref and Unpaywall reviewed so their consent/acquisition flows can be tested, but neither is selected by default. Before deployment, verify each exact service's current terms, replace the test contact (`test@ptr.test`) and retention disclosures with deployment-specific values, or set both `*_ENABLED` and `*_ENABLEMENT_REVIEWED` flags to `false`. Each external run must approve the actual categories shown in the UI; consent is not inherited from another run. Crossref receives bibliography metadata and the configured contact email, never Source Document text or PDF content; Unpaywall discovery receives the DOI and contact email, while a separate gate covers each content-host URL.

### Configurable hybrid evidence retrieval

Retrieval settings are deployment-configurable through `.env` and pinned into each new Analysis Run:

| Variable | Default | Behavior |
|---|---|---|
| `PAPER_RETRIEVAL_PROFILE_ID` | `postgres-hybrid-rrf-v1` | Diagnostic retrieval profile identity |
| `PAPER_RETRIEVAL_VECTOR_CANDIDATES` | `10` | Maximum vector-ranked candidates per claim/reference |
| `PAPER_RETRIEVAL_LEXICAL_CANDIDATES` | `10` | Maximum PostgreSQL full-text candidates per claim/reference |
| `PAPER_RETRIEVAL_FINAL_CANDIDATES` | `5` | Maximum fused passages retained per claim/reference |
| `PAPER_RETRIEVAL_RRF_CONSTANT` | `60` | Reciprocal-rank fusion constant |

The default embedding selection remains `feature-hash-384-v1`; it is deterministic word-unigram/bigram hashing, not a trained semantic model. Local Compose also starts an internal Ollama service and enables the pinned `nomic-embed-text:v1.5` model (768 dimensions; Ollama manifest digest `0a109f422b47`) as an optional selection. On first startup, Compose downloads the Ollama image and model into the persistent `ollama_data` volume. API and worker startup does not wait for the model download; check it with `docker compose -f infra/docker-compose.yml logs ollama-model-init` before selecting Ollama. New Analysis Runs still select feature-hash unless the operator or researcher explicitly chooses Ollama. Ollama endpoint credentials stay server-side. Chunk and Atomic Claim query vectors use the same selected Ollama model/profile, and the immutable run pins the model, dimension, and a non-secret endpoint fingerprint. Candidate retrieval never crosses the exact Analysis Run and Cited Reference scope.

For non-Compose deployments, configure `OLLAMA_ENABLED`, `OLLAMA_BASE_URL`, `OLLAMA_MODEL`, and `OLLAMA_DIMENSION` in the API environment; local Compose defaults `OLLAMA_MODEL` to `nomic-embed-text:v1.5`. Only endpoint hosts explicitly listed in `OLLAMA_TRUSTED_HOSTS` are `LOCAL`; other hosts are classified `EXTERNAL` and require a reviewed retention disclosure plus fresh per-run consent. External endpoints remain unavailable until reviewed enablement and a deployment-specific disclosure are configured, and each run must approve `atomic_claims`, `cited_paper_chunks`, and `embedding_input`. An optional `OLLAMA_API_KEY` is used only by the API as a bearer credential and is never returned by the provider directory or persisted in Analysis Run configuration.

## API

The Next.js server proxies same-origin `/api/v1/*` calls to the private API; browser code never needs a public API binding.

- `POST /api/v1/analysis-runs` — multipart `file` plus optional JSON `configuration`; validates, stores the Source Document, creates a `QUEUED` immutable Analysis Run, and commits its outbox event atomically.
- `GET /api/v1/providers` — enabled and classified provider choices grouped by role, plus stable data-category descriptions; disabled and unclassified providers are omitted.
- `GET /api/v1/analysis-runs?limit=25&cursor=...` — cursor-paginated recent persisted runs, ordered by creation time descending and ID descending; the response contains `items` and `nextCursor`.
- `GET /api/v1/analysis-runs/{id}` — persisted status, progress, source hash, and configuration snapshot, including the pinned retrieval profile. A ready `PARSED` run has persisted citation structure, Atomic Claims, inferred/provisional Citation Target links, bibliography-resolution outcomes, and retrieval results for eligible cited assets; semantic Evidence Judgements against full-text passages have not run.
- `GET /api/v1/analysis-runs/{id}/parsed-document` — parser provenance, normalized source text, sections, Citation Contexts and their marker-to-reference targets, extracted Atomic Claims and source spans, inferred/provisional claim-to-target links, and Bibliography Entries with their current resolution status. Source offsets are zero-based/end-exclusive UTF-16 code-unit indexes into `normalizedSourceText`; the parser separates semicolons and clear contrastive clause connectors, and records sentence fallback when a clause boundary is ambiguous.
- `GET /api/v1/analysis-runs/{id}/report` — persisted resolution outcomes and Claim–Paper Verification records with ranked Evidence Passages, exact passage text/section, source asset/hash, parser and language-detector provenance, candidate ranks, fusion score, and pinned retrieval/embedding profile. Full-text semantic Evidence Judgements have not run; access-based terminal outcomes remain policy-driven.
- `POST /api/v1/documents/{id}/analysis-runs` — re-analyze the same stored Source Document as a new run.
- `DELETE /api/v1/documents/{id}` — tombstone and delete the Source Document, its Analysis Runs, document-scoped derived data, and unshared stored assets; safe to retry after an incomplete cleanup.
- `GET /api/v1/health` — API/database liveness.

The OpenAPI 3 document is available at <http://127.0.0.1:8080/v3/api-docs> (YAML at `/v3/api-docs.yaml`), Swagger UI at <http://127.0.0.1:8080/swagger-ui/index.html>, and the Scalar API reference at <http://127.0.0.1:8080/scalar>. The API host port defaults to `8080`; configure `API_HOST_PORT` in `.env` if that loopback port is unavailable. Compose binds this published port only to `127.0.0.1`; do not expose it publicly. Swagger, Scalar, and OpenAPI endpoints are disabled in the worker process.

The upload form loads provider choices from `GET /api/v1/providers`; only enabled, classified providers are listed. Heuristic extraction, local feature-hash embeddings, mock System One, and recorded scholarly-metadata fixtures are selected by default. Crossref and Unpaywall are available in the repository's local catalog for consent-flow testing, but neither is selected automatically. Ollama is offered only when its API-side endpoint/model/dimension configuration is valid; an untrusted endpoint is classified `EXTERNAL`, and its actual embedding payload categories require fresh per-run approval before any request. Laya is offered only when explicitly enabled with an API key and a trusted local endpoint; mock remains the default. Laya's runtime/checkpoint/output mapping and non-secret endpoint fingerprint are pinned in each run snapshot. Endpoint credentials are API-side only and are never returned to the web client or snapshotted. The immutable Analysis Run snapshot records provider choices, the score-policy version and threshold, retrieval/embedding profile, and provider/category consent. External provider calls pass through the per-run provider-call gate immediately before dispatch, so missing consent results in no request. The default run sends no document content to external providers.

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
make validate       # Kotlin/queue + Laya contract tests, web tests, lint, typecheck, production build, and calibration
make test-web       # run the web proxy behavior tests only
make lint-web       # run web lint
make typecheck-web  # run web typecheck
make build-web      # create the web production build
make migrate        # deploy the Sqitch plan into the local Compose PostgreSQL service
make migrate:ls     # print full change IDs, oldest-first in the local timezone
make migrate:revert CHANGE=<change-id>  # revert that change and later migrations; interactive confirmation
make clean          # destructive: remove all local application volumes
```

The Sqitch IDs are database change IDs, not Git commit SHAs. `migrate:ls` prints events oldest-first as a full ID, an indented local timestamp/action, and an indented title; timestamps show the machine's local timezone, abbreviation, and offset. It prints directly without a pager; pass a full 40-character change ID from it to `migrate:revert`. Sqitch reverts the selected change and all later changes, retains its interactive confirmation, and runs the migration revert scripts. This can delete persisted schema data (for example, bibliography-resolution outcomes); back up and review the target database before proceeding. Neither target removes Compose volumes.

`make validate` requires a Docker-compatible container runtime for its PostgreSQL/Redis integration tests. The tests execute the same schema migrations and exercise upload/run provenance, GROBID consolidation settings, DOI validation, deterministic matching and ambiguity abstention, unsupported-type precedence, Crossref consent gating with a no-request contract, persisted Canonical Paper/report output, parsing and source spans, citation-clause grouping and sentence fallback, qualifier-preserving claim extraction, per-run/context/span claim deduplication, all-to-all same-context target links with database-enforced context isolation, re-analysis, duplicate stream delivery, inbox idempotency, and reclaiming pending work with a replacement worker.

If using Podman, start its machine and export the Docker-compatible socket as `DOCKER_HOST` before Compose/Testcontainers commands. Podman's socket cannot be bind-mounted into Testcontainers' Ryuk cleanup container on some setups; in that case also set `TESTCONTAINERS_RYUK_DISABLED=true` when running tests. Testcontainers still stops the declared containers during normal test shutdown, but disabling Ryuk removes its crash-cleanup safeguard. The Podman machine must have enough memory for PostgreSQL, Redis, MinIO, and the JVM build/test process.
