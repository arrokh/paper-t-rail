# Optional Laya local evaluation

This document describes the exact Laya candidate approved in issue [#21](https://github.com/arrokh/paper-t-rail/issues/21) for local adapter evaluation only. It is not production approval. Local `make dev` now prepares the private key, verifies/downloads the pinned model, starts the sidecar, and then starts API/worker/web; set `LAYA_ENABLED=false` to skip Laya. The first startup downloads roughly 1.7 GB of model artifacts to the persistent `laya_model_data` volume. Local Compose prefers Laya for new runs when selectable; if Laya is unavailable, an omitted provider choice safely resolves to mock. `make laya-up` remains available to start/recreate the sidecar separately. Outside Compose, the configured default provider is Laya; an omitted choice resolves to `mock` if Laya is not selectable. An explicitly selected Laya that is unavailable at request time fails visibly without mock fallback. Local `.env.example` and `make dev`/`make laya-up` setup enable experimental local aggregation by default; set `LOCAL_LAYA_AGGREGATION_ENABLED=false` to retain uncalibrated judgements without aggregation. Neither mode is production approval. The production gate remains human-reviewed calibration and go/no-go in [#45](https://github.com/arrokh/paper-t-rail/issues/45).

## Pinned candidate and artifacts

- Checkpoint: `convaiinnovations/laya-typed-decisions`, Hugging Face commit `1a793eb568e6718f15941d08f85432581df534e3` (Apache-2.0, 421M parameters, 1,024-token context).
- Checkpoint weight artifact: SHA-256 `4fa56de72383a9d3efa9cfa78955733c81b9fc8067a587ca4beb82c78107a24e`.
- `rl_agent_config.json` Git blob: `5f0e1d5f2366fe8ba2ff330dffaeed53b469e97e`; encoder config: `d4be4829750fb04c0aa8b9897c3ea827f76c0109`; tokenizer: `2f4d8583e507b7466d2490e2d6c045647a822698`; tokenizer config: `ed1ffabc2ce11120754705709569e365e46da71a`.
- Runtime: first-party `laya-serve` v0.3.20 at source commit `23a17522aa4942da6cce53a995a275760320b691`; pinned CPU-only PyTorch build. Runtime dependencies are built from that commit in `infra/laya/Dockerfile`.
- Provider output mapping: `paper-trail-evidence-judgement-v1` (compact provider-version identifier `pt-ej-v1`), pinned in code and the immutable Analysis Run snapshot along with the checkpoint ID, full runtime commit, and non-secret endpoint fingerprint.

The model card warns that this checkpoint was trained for unrelated synthetic workflows and that output probability calibration needs refitting. Its reported confidence is not an in-domain calibrated probability. The selected model is only an evaluation candidate.

The pinned runtime emits a startup warning because the checkpoint's unused `choice:11+` temperature (`0.10058`) is outside its supported range and is clamped to `0.5`. The six configured questions use only `choice:3-5` and `score:3-5` buckets, whose pinned temperatures are within range; the model's base temperatures are also within range. Do not patch the checkpoint to suppress this warning: preserve its approved digest and treat the broader confidence-calibration limitation as an evaluation/production gate.

## Private deployment and model download

The Compose `laya-evaluation` profile contains a one-shot downloader and a separate inference service. The downloader connects to Hugging Face on its own non-internal network, fetches only the pinned artifacts, and verifies the repository revision, configuration/tokenizer Git blob IDs, model weight SHA-256, 1,024-token context, and installed Laya package version/source commit before atomically writing a runtime manifest to the persistent `laya_model_data` volume. It does not receive a Source Document or call the document-processing pipeline. The inference service requires that manifest, rechecks the runtime identity and artifact hashes, loads the checkpoint from the mounted volume in Hugging Face/Transformers offline mode, and refuses startup if any pin differs.

The inference sidecar is CPU-only (including Apple Silicon), has no published host port, and is attached only to an internal Compose network shared with API and worker. Its ASGI middleware requires an exact bearer API key on every route except the health check and compares keys with `hmac.compare_digest`. Uvicorn access logs are disabled so request query strings are not logged; the sidecar does not log API keys or request bodies. The API and worker pass the key server-to-server; web has no Laya environment variables. Keep `.env` untracked with file mode `0600` (`chmod 600 .env`); never put the secret in a command line, browser variable, log, issue, or run snapshot.

1. Start the local developer stack:

   ```sh
   make dev
   ```

   `make dev` starts the standard app stack and, unless `LAYA_ENABLED=false`, also prepares `.env` with mode `0600`, generates a random 256-bit API key if none exists, runs the separate downloader to verify/download the pinned checkpoint, starts/waits for the private sidecar, then starts API/worker/web with the matching key. The first startup downloads roughly 1.7 GB; later startups reuse the persistent model volume while verifying the pinned files. The local sample sets `SYSTEM_ONE_DEFAULT_PROVIDER=laya`, `LAYA_BASE_URL=http://laya:8000`, and trusted Compose host settings. Explicitly setting `LAYA_ENABLED=false` skips the downloader and sidecar, and the setup resolves an omitted System One provider to mock. Outside Compose, `application.yml` enables the candidate and points at `http://laya:8000`, but the API key defaults to empty and the System One preference defaults to Laya; the API resolves omitted choices to mock if Laya is not selectable. Laya is offered only with a valid endpoint/key on a trusted host. `make laya-up` can start/recreate the sidecar separately.

2. For a synthetic one-pair runtime smoke measurement, execute the benchmark inside the sidecar; the key is read from the service environment and is not passed as a command argument:

   ```sh
   make benchmark-laya
   ```

The benchmark request is synthetic and is not calibration evidence. Its source is `infra/laya/benchmark_runtime.py`; it reports wall-clock request latency, runtime-reported input/output token counts, the response model, and Laya process RSS/high-water RSS from `/proc/1/status`. The model weights and inference cache are in a persistent Docker volume. `make clean` deletes that volume along with other local application data.

## Local-only aggregation default

The local default lives in the repository-root `.env`: `.env.example` enables it, and `make dev`/`make laya-up` adds `true` when the setting is absent while preserving an existing value. If an older `.env` still has `false`, change it to `true` once to adopt the new default. Set it to `false` to disable aggregation for new runs. `application.yml` and Compose also default the variable to `true` when absent, and Compose passes it to API and worker. Production must explicitly set it to `false`. `make dev` starts the sidecar by default; set `LAYA_ENABLED=false` to skip it. The aggregation flag only has an effect when a run selects Laya.

| Environment variable | Default | Description |
|---|---:|---|
| `LOCAL_LAYA_AGGREGATION_ENABLED` | `true` | Enables experimental aggregation only when a run selects Laya. Production must explicitly set it to `false`. |
| `LOCAL_LAYA_AGGREGATION_DIRECT_SUPPORT_THRESHOLD` | `0.80` | Minimum direct-support strength. |
| `LOCAL_LAYA_AGGREGATION_PARTIAL_SUPPORT_THRESHOLD` | `0.70` | Minimum partial-support strength. |
| `LOCAL_LAYA_AGGREGATION_CONTRADICTION_THRESHOLD` | `0.80` | Minimum contradiction strength. |
| `LOCAL_LAYA_AGGREGATION_COMPARABILITY_MARGIN` | `0.08` | Margin for comparable support/contradiction. |

When enabled, each new eligible Laya run snapshots these values plus the evidence-strength and aggregation policy versions, persists raw Laya judgements, and applies the deterministic aggregator to produce experimental Claim–Paper statuses. These outputs remain uncalibrated, unreviewed, and not production-approved; the UI and progress message explicitly disclose this. Set the flag to `false` to persist Laya judgements without semantic final aggregation (`NOT_RUN`). If Laya is unselectable and the omitted provider choice falls back to mock, the Laya-specific thresholds are not applied. The choice is immutable per run: changing `.env` affects only newly created runs.

Production deployments must explicitly set `LAYA_ENABLED=false`, `SYSTEM_ONE_DEFAULT_PROVIDER=mock`, and `LOCAL_LAYA_AGGREGATION_ENABLED=false`. The threshold environment variables may remain at their documented values while aggregation is disabled; those values are not calibration evidence. A production GO still requires issue #45 human review and deployment-scoped approval.

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

The API provider has a bounded request timeout and response size. A deterministic HTTP 422 is not retried: the API records the affected Claim–Reference pair as failed with `SYSTEM_ONE_CONTEXT_LIMIT_EXCEEDED` when the response identifies the 1,024-token guard, or `SYSTEM_ONE_REQUEST_REJECTED` for another request rejection. The report explains the failure and preserves the no-truncation rule; other connection failures, timeouts, and malformed/unsupported responses retain worker retry handling. There is no mock or recorded-fixture fallback. If Laya is disabled or lacks a valid key/endpoint/trust configuration, it is omitted from the provider directory and an omitted provider choice uses mock. An explicit request to select unavailable Laya is rejected before queueing.

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

These figures are an operational sizing observation for this synthetic request only, not evidence of accuracy, calibration, production suitability, or a service-level objective. Input-token usage is summed across questions; the pre-inference guard separately checks every complete question/state sequence against 1,024 tokens. Re-run on the target deployment hardware and record hardware/OS/Docker limits with any new measurement.

After adding runtime identity to the verified artifact manifest and restarting the same Apple M4 Max sidecar, the latest healthy-sidecar request measured 7.533 s, 3,361 input tokens across the six questions, 0 reported output tokens, 2,937.6 MiB process peak RSS, and 2,184.8 MiB RSS after the request. It returned `DIRECT_SUPPORT` for the synthetic pair; this is an operational smoke sample, not calibration or accuracy evidence. Before measuring, startup logs showed only the unused-bucket calibration warning above, health reported `typed-decisions` loaded, an unauthenticated inference probe returned 401, and an authenticated invalid-body probe returned 400 before inference. The installed runtime and manifest matched `laya-serve-0.3.20@23a17522aa4942da6cce53a995a275760320b691`; no runtime incompatibility or hardware blocker was observed. The captured benchmark JSON is:

```json
{"checkpoint":"typed-decisions","elapsed_ms":7533.4,"input_tokens":3361,"judgement":"DIRECT_SUPPORT","output_tokens":0,"reported_model":"laya-rl-agent","server_peak_rss_mib":2937.6,"server_rss_mib_after_request":2184.8}
```

## Current pipeline trial and production-readiness recommendation

Read-only local database observations establish inference and persistence, not accuracy or calibration. An evaluation-only run pinned aggregation as `NOT_RUN` and persisted 10 Evidence Judgements across two Claim–Reference pairs for one Cited Paper (7 `DIRECT_SUPPORT`, 2 `PARTIAL_SUPPORT`, 1 `CONTRADICTS`); it had zero human reviews and no final semantic statuses. A more recent local aggregation run persisted 10 judgements on successful pairs but completed with warnings because one pair's top-ranked complete request measured 1,178 tokens against the 1,024-token limit. The other four candidate payloads measured within the limit, but evaluation stopped at the first top-ranked rejection and no judgement set was stored for that pair; evidence is not truncated and no final status is fabricated. The worker previously retried this deterministic HTTP 422, obscuring the cause; the API now records a terminal context-limit reason for the affected pair without retrying. These operational observations cover one Cited Paper and have no gold labels.

The draft aggregation fixture is also insufficient for release: it has six synthetic evidence cases and zero human-reviewed evidence labels. The permissive candidate (`0.700/0.650/0.700`, margin `0.100`) and design-baseline candidate (`0.800/0.700/0.800`, margin `0.080`) both agree on 6/6 draft cases; the conservative candidate (`0.900/0.850/0.900`, margin `0.050`) agrees on 5/6 and abstains on the draft partial-support case. This does not discriminate between thresholds or estimate real-world performance. **Recommendation:** the design-baseline is approved only for the local experimental configuration; permissive and conservative values may be varied as local sensitivity checks. No threshold is approved for production from these results, and the baseline remains uncalibrated.

**Current go/no-go recommendation: NO-GO for production aggregation or production Laya use.** Continue local experimental evaluation and persist explicitly uncalibrated judgements. The parts requiring human approval in issue [#45](https://github.com/arrokh/paper-t-rail/issues/45) are:

1. **Dataset and labels:** approve legally usable, in-domain Cited Papers; cover every judgement class (`DIRECT_SUPPORT`, `PARTIAL_SUPPORT`, `CONTRADICTS`, `UNRELATED`, `INSUFFICIENT`), each evidence role, scope/qualifier mismatches, secondary reports, conflicts, and abstention. Obtain independent human labels for judgement, role, scope/design scores, and expected Claim–Paper outcomes; adjudicate disagreements and hold out whole papers from tuning.
2. **Laya judgement quality:** review held-out per-class precision/recall and confusion matrix, role and ordinal-score agreement, confidence calibration, abstention, and high-risk errors for the exact pinned checkpoint/runtime/prompt/output mapping. Do not interpret the current `answer_confidence` as a calibrated probability.
3. **Aggregation policy:** compare predeclared threshold/margin candidates on frozen Laya outputs and human Claim–Paper labels; review per-status errors, conflicts, partial support, and insufficient-evidence/abstention. Approve the exact values and policy version only after the held-out results meet a predeclared safety bar.
4. **Release decision:** review the exact artifact/license, private authenticated deployment boundary, data-retention/deletion behavior, and failure/rollback plan; record a deployment-scoped go/no-go. Approval does not automatically change defaults: production stays disabled with mock selected until its operator explicitly opts in.

The synthetic runtime benchmark and the current database smoke are operational/integration evidence only. The calibration harness evaluates the deterministic aggregation policy using fixture-supplied judgements; it does not invoke Laya or measure Laya's judgement accuracy. See the [calibration report](benchmarks/v1-calibration.md) for its draft-only results.
