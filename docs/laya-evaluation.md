# Optional Laya local evaluation

This document describes the exact Laya candidate approved in issue [#21](https://github.com/arrokh/paper-t-rail/issues/21) for local adapter evaluation only. It is not production approval. `make dev` explicitly configures and enables the authenticated local Compose provider by default; outside this local developer path, Laya remains disabled unless configured. Mock remains the Analysis Run default and manually selectable, and no Laya failure falls back to mock. The default run profile also leaves aggregation/semantic judgement unexecuted until the separate calibration and production gate in [#45](https://github.com/arrokh/paper-t-rail/issues/45).

## Pinned candidate and artifacts

- Checkpoint: `convaiinnovations/laya-typed-decisions`, Hugging Face commit `1a793eb568e6718f15941d08f85432581df534e3` (Apache-2.0, 421M parameters, 1,024-token context).
- Checkpoint weight artifact: SHA-256 `4fa56de72383a9d3efa9cfa78955733c81b9fc8067a587ca4beb82c78107a24e`.
- `rl_agent_config.json` Git blob: `5f0e1d5f2366fe8ba2ff330dffaeed53b469e97e`; encoder config: `d4be4829750fb04c0aa8b9897c3ea827f76c0109`; tokenizer: `2f4d8583e507b7466d2490e2d6c045647a822698`; tokenizer config: `ed1ffabc2ce11120754705709569e365e46da71a`.
- Runtime: first-party `laya-serve` v0.3.20 at source commit `23a17522aa4942da6cce53a995a275760320b691`; pinned CPU-only PyTorch build. Runtime dependencies are built from that commit in `infra/laya/Dockerfile`.
- Provider output mapping: `paper-trail-evidence-judgement-v1`, included in the provider version and immutable Analysis Run snapshot along with the checkpoint ID, runtime version, and non-secret endpoint fingerprint.

The model card warns that this checkpoint was trained for unrelated synthetic workflows and that output probability calibration needs refitting. Its reported confidence is not an in-domain calibrated probability. The selected model is only an evaluation candidate.

## Private deployment and model download

The Compose `laya-evaluation` profile contains a one-shot downloader and a separate inference service. The downloader connects to Hugging Face on its own non-internal network, fetches only the pinned artifacts, and verifies the repository revision, configuration/tokenizer Git blob IDs, model weight SHA-256, and 1,024-token context before atomically writing a local manifest to the persistent `laya_model_data` volume. It does not receive a Source Document or call the document-processing pipeline. The inference service requires that manifest, rechecks all hashes, loads the checkpoint from the mounted volume in Hugging Face/Transformers offline mode, and refuses startup if any pin differs.

The inference sidecar is CPU-only (including Apple Silicon), has no published host port, and is attached only to an internal Compose network shared with API and worker. Its ASGI middleware requires an exact bearer API key on every route except the health check, compares keys with `hmac.compare_digest`, and never logs the key or request body. The API and worker pass the key server-to-server; web has no Laya environment variables. Keep `.env` untracked with file mode `0600` (`chmod 600 .env`); never put the secret in a command line, browser variable, log, issue, or run snapshot.

1. Start the local developer stack:

   ```sh
   make dev
   ```

   If `.env` is absent, `make dev` creates it from `.env.example`, sets mode `0600`, generates a random 256-bit API key only if Laya is enabled and no key is present, downloads/verifies the exact checkpoint using the separate one-shot downloader, starts/waits for the private sidecar, and then starts API/worker with the same key. It preserves a pre-existing key and an explicit `LAYA_ENABLED=false`. Keep `LAYA_BASE_URL=http://laya:8000` and `LAYA_TRUSTED_HOSTS=laya,localhost,127.0.0.1` for Compose. `application.yml` still defaults Laya off; the local Compose environment explicitly enables it. The API exposes Laya in the provider directory only when the endpoint is valid, the key is present, the endpoint host is trusted, and explicit enablement is true.

2. For a synthetic one-pair runtime smoke measurement, execute the benchmark inside the sidecar; the key is read from the service environment and is not passed as a command argument:

   ```sh
   make benchmark-laya
   ```

The benchmark request is synthetic and is not calibration evidence. Its source is `infra/laya/benchmark_runtime.py`; it reports wall-clock request latency, runtime-reported input/output token counts, the response model, and Laya process RSS/high-water RSS from `/proc/1/status`. The model weights and inference cache are in a persistent Docker volume. `make clean` deletes that volume along with other local application data.

## Request and output contract

`LayaSystemOneProvider` uses the Jev-compatible `POST /v1/systemone` endpoint with an explicit `typed-decisions` model alias. It sends one claim/evidence pair per request. `state` contains only `claim`, `evidence`, and, when present, the Evidence Passage section heading. It omits Source Document text, unrelated evidence passages, and internal identifiers. The `questions` object contains exactly six stable question IDs:

| Question ID | Laya response | Paper T-Rail mapping |
|---|---|---|
| `judgement` | Choice | One of `DIRECT_SUPPORT`, `PARTIAL_SUPPORT`, `CONTRADICTS`, `UNRELATED`, or `INSUFFICIENT`. Laya's `answer_confidence` is mapped to `EvidenceJudgement.confidence`; the separate normalized-entropy `confidence` field and full probability distribution are validated but not persisted. `answer_confidence` is not calibrated for this evidence domain. |
| `evidence_role` | Choice | `PRIMARY_FINDING` (this paper's own result), `AUTHOR_SYNTHESIS` (the authors interpret/synthesize evidence), or `SECONDARY_REPORT` (a result attributed to another work). |
| `directness` | Score | Ordered 0–4 rubric: no evidence → indirect/weak → relevant but not direct → directly addresses most → directly reports the full claim. |
| `claim_scope_match` | Score | Ordered 0–4 rubric: population/conditions/outcome mismatch → major qualifier missing → core match with qualifier uncertain → nearly all qualifiers match → all material qualifiers match. |
| `study_design_quality` | Score | Ordered 0–4 rubric: no design stated → very weak → limited/observational → reasonably strong → rigorous and directly suited. |
| `relevance` | Score | Ordered 0–4 rubric: unrelated → slight topical connection → relevant but partial → strongly relevant → directly and fully relevant. |

For each score question, the request gives the dimension-specific 0–4 descriptions in its instructions and the ordered labels `none`, `low`, `moderate`, `high`, `complete`. The output must echo those exact labels and provide the complete probability distribution for keys `0`–`4`; the numeric score is divided by four and stored as a normalized 0–1 score. Choice answers must use the supported labels, give a complete distribution whose finite values are in `[0,1]` and sum to `1` within `0.02`, and provide finite `confidence` in `[0,1]`; the `judgement` answer must also provide finite `answer_confidence` in `[0,1]`. The adapter checks the pinned runtime name, explicit routing alias, usage metadata, exact answer IDs, answer types, choice values, labels, score range, and required score fields. An absent or malformed answer is not replaced with a fabricated judgement.

## Context limit and failure behavior

The checkpoint limit is 1,024 tokens for the complete sequence, not just the state. `infra/laya/laya_serve_preflight.py` patches the pinned router at the pre-inference boundary and uses the checkpoint tokenizer and v0.3.20 sequence renderer to count the full state plus each question/options/instruction. It also rejects truncated question instructions/options. Any over-limit sequence returns an error before model inference; the request is never silently truncated. Python unit tests verify the complete-sequence boundary and that every question is checked.

The API provider has a bounded request timeout and response size. HTTP errors (including context rejection), connection failures, timeouts, malformed/unsupported responses, and context failures raise provider errors. Existing worker retry/failure handling leaves the affected verification incomplete and the run visibly warning/incomplete; it does not switch to mock or recorded fixtures. The default Analysis Run still selects `mock`, and if `LAYA_ENABLED` is false, a key/endpoint is missing, or the endpoint is outside the trusted host list, Laya is omitted from the selectable provider directory. Selecting Laya is an explicit per-run choice; a running sidecar alone never changes the default.

## Recorded local runtime smoke measurement

Measured three sequential requests after sidecar health readiness, using the synthetic one-claim/one-passage request and six questions from `infra/laya/benchmark_runtime.py`:

| Environment/metric | Measurement |
|---|---:|
| Host | Apple M4 Max (`Mac16,5`), arm64; Docker Linux/aarch64, 15.7 GiB memory available |
| CPU mode | CPU-only, 4 configured threads |
| Wall-clock request latency | 10.20 s, 13.34 s, 16.03 s (median 13.34 s; n=3) |
| Runtime-reported input tokens | 3,361 summed across the six per-question sequences |
| Runtime-reported output tokens | 0 (the runtime returned answers; do not interpret this counter as zero generated answer content) |
| Laya server process RSS high-water mark | 2,939.4 MiB |
| Laya server process RSS after request | approximately 2.13 GiB |
| Runtime response | model `laya-rl-agent`, route `typed-decisions`; request returned a typed judgement |

These figures are an operational sizing observation for this synthetic request only, not evidence of accuracy, calibration, production suitability, or a service-level objective. Input-token usage is summed across questions; the pre-inference guard separately checks every complete question/state sequence against 1,024 tokens. Re-run on the target deployment hardware and record hardware/OS/Docker limits with any new measurement. Human review of in-domain calibration and the production enable/disable decision remains in issue #45.
