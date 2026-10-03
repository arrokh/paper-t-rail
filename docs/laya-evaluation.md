# Laya evaluation and aggregation defaults

This document describes the exact Laya candidate and local adapter evaluation approved in issue [#21](https://github.com/arrokh/paper-t-rail/issues/21). Per the repository-owner decision in [#45](https://github.com/arrokh/paper-t-rail/issues/45), the production Spring profile defaults Laya enabled, selected, and experimental aggregation enabled. This is a configuration-default decision only; it does not establish model calibration or accuracy. Local `make dev` now prepares the private key, verifies/downloads the pinned model, starts the sidecar, and then starts API/worker/web; set `LAYA_ENABLED=false` to skip Laya. The first startup downloads roughly 1.7 GB of model artifacts to the persistent `laya_model_data` volume. Local Compose prefers Laya for new runs when selectable; if Laya is unavailable, an omitted provider choice safely resolves to mock. `make laya-up` remains available to start/recreate the sidecar separately. Outside Compose, the configured default provider is Laya; an omitted choice resolves to `mock` if Laya is not selectable. An explicitly selected Laya that is unavailable at request time fails visibly without mock fallback. Aggregation defaults to `true` in `.env.example`, base Spring configuration, and Compose; `make dev`/`make laya-up` preserves explicit `.env` values. Set `SYSTEM_ONE_AGGREGATION_ENABLED=false` to retain eligible Laya or Jev judgements without final aggregation. The shared policy does not calibrate either provider. These defaults provide no calibration evidence, and calibration is not a product or release requirement. Make no accuracy or calibrated-output claim; all non-mock System One results remain labeled uncalibrated. Keep unauthenticated access on localhost/trusted private networks, require per-run consent for external calls, and provide a rollback path. Issue #45 is closed as not planned; evaluation tooling is optional and does not gate release.

## Pinned candidate and artifacts

- Checkpoint: `convaiinnovations/laya-typed-decisions`, Hugging Face commit `1a793eb568e6718f15941d08f85432581df534e3` (Apache-2.0, 421M parameters, 1,024-token context).
- Checkpoint weight artifact: SHA-256 `4fa56de72383a9d3efa9cfa78955733c81b9fc8067a587ca4beb82c78107a24e`.
- `rl_agent_config.json` Git blob: `5f0e1d5f2366fe8ba2ff330dffaeed53b469e97e`; encoder config: `d4be4829750fb04c0aa8b9897c3ea827f76c0109`; tokenizer: `2f4d8583e507b7466d2490e2d6c045647a822698`; tokenizer config: `ed1ffabc2ce11120754705709569e365e46da71a`.
- Runtime: first-party `laya-serve` v0.3.20 at source commit `23a17522aa4942da6cce53a995a275760320b691`; pinned CPU-only PyTorch build. Runtime dependencies are built from that commit in `infra/laya/Dockerfile`.
- Provider output mapping: `paper-trail-evidence-judgement-v1` (compact provider-version identifier `pt-ej-v1`), pinned in code and the immutable Analysis Run snapshot along with the checkpoint ID, full runtime commit, and non-secret endpoint fingerprint.

The model card warns that this checkpoint was trained for unrelated synthetic workflows and that output probability calibration needs refitting. Its reported confidence is not an in-domain calibrated probability. The selected model is only an evaluation candidate.

The pinned runtime emits a startup warning because the checkpoint's unused `choice:11+` temperature (`0.10058`) is outside its supported range and is clamped to `0.5`. The six configured questions use only `choice:3-5` and `score:3-5` buckets, whose pinned temperatures are within range; the model's base temperatures are also within range. Do not patch the checkpoint to suppress this warning: preserve its approved digest and disclose that confidence outputs are uncalibrated. Calibration is not required for product release.

## Private deployment and model download

The Compose `laya-evaluation` profile contains a one-shot downloader and a separate inference service. The downloader connects to Hugging Face on its own non-internal network, fetches only the pinned artifacts, and verifies the repository revision, configuration/tokenizer Git blob IDs, model weight SHA-256, 1,024-token context, and installed Laya package version/source commit before atomically writing a runtime manifest to the persistent `laya_model_data` volume. It does not receive a Source Document or call the document-processing pipeline. The inference service requires that manifest, rechecks the runtime identity and artifact hashes, loads the checkpoint from the mounted volume in Hugging Face/Transformers offline mode, and refuses startup if any pin differs.

The inference sidecar is CPU-only (including Apple Silicon) and is attached only to an internal Compose network shared with API and worker. Compose publishes its HTTP port as `127.0.0.1:8000` by default; this loopback-only mapping does not expose it to the LAN. Inference and preflight requests still require the bearer API key. Its ASGI middleware requires an exact bearer API key on every route except the health check and compares keys with `hmac.compare_digest`. The pinned service exposes HTTP health, inference, and tokenizer-preflight endpoints only; it does not provide WebSockets. Uvicorn's “Unsupported upgrade request” warning therefore indicates an unexpected client/probe attempted a WebSocket handshake; it is not an inference/acquisition failure. Do not install WebSocket support merely to hide it. Uvicorn access logs are disabled so request query strings are not logged; the sidecar does not log API keys or request bodies. The API and worker pass the key server-to-server; web has no Laya environment variables. Keep `.env` untracked with file mode `0600` (`chmod 600 .env`); never put the secret in a command line, browser variable, log, issue, or run snapshot.

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

## Aggregation default

Aggregation defaults to `true` in the repository-root `.env.example`, base `application.yml`, and Compose; `make dev`/`make laya-up` preserves any explicit value in `.env`. Set `SYSTEM_ONE_AGGREGATION_ENABLED=false` to suppress experimental final statuses for new runs. This production default is the explicit owner decision recorded in #45; it does not calibrate the model or approve thresholds. Any generated judgements and final statuses remain uncalibrated and must be presented as such. `make dev` starts the sidecar by default; set `LAYA_ENABLED=false` to skip it. The shared aggregation flag applies to eligible runs selecting Laya or configured Jev; Jev remains an explicit, consent-gated alternative and is never the default.

| Environment variable | Default | Description |
|---|---:|---|
| `SYSTEM_ONE_AGGREGATION_ENABLED` | `true` | Enables experimental aggregation for eligible Laya and Jev runs. Outputs remain uncalibrated; set `false` to keep final statuses `NOT_RUN` while retaining judgement-only results. |
| `SYSTEM_ONE_AGGREGATION_DIRECT_SUPPORT_THRESHOLD` | `0.80` | Minimum direct-support strength. |
| `SYSTEM_ONE_AGGREGATION_PARTIAL_SUPPORT_THRESHOLD` | `0.70` | Minimum partial-support strength. |
| `SYSTEM_ONE_AGGREGATION_CONTRADICTION_THRESHOLD` | `0.80` | Minimum contradiction strength. |
| `SYSTEM_ONE_AGGREGATION_COMPARABILITY_MARGIN` | `0.08` | Margin for comparable support/contradiction. |

When enabled, each new eligible Laya or Jev run snapshots these values plus the evidence-strength and aggregation policy versions, persists raw provider judgements, and applies the shared deterministic aggregator to produce experimental Claim–Paper statuses. These outputs remain uncalibrated and are not Human Reviews. Human calibration and deployment-specific approval are not product requirements; this does not establish accuracy. The UI and progress message explicitly disclose that they are uncalibrated. Set the flag to `false` to persist selected non-mock System One judgements without semantic final aggregation (`NOT_RUN`). If Laya is unselectable and the omitted provider choice falls back to mock, no model judgements are produced and aggregation is not applied. The choice is immutable per run: changing `.env` affects only newly created runs.

The production Spring profile defaults to `LAYA_ENABLED=true`, `SYSTEM_ONE_DEFAULT_PROVIDER=laya`, and `SYSTEM_ONE_AGGREGATION_ENABLED=true` per #45. The configured Laya default is not a model-calibration result. Calibration and target-specific production approval are not product requirements. Outputs and thresholds remain uncalibrated and must not be represented as validated accuracy. The enabled defaults are not calibration evidence or approval of the threshold values; explicitly set aggregation to `false` to suppress final statuses.

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

For each score question, the request gives the dimension-specific 0–4 descriptions in its instructions and the ordered labels `none`, `low`, `moderate`, `high`, `complete`. The output must echo those exact labels and provide the complete probability distribution for keys `0`–`4`; the numeric score is divided by four and stored as a normalized 0–1 score. Choice answers must use the supported labels, give a complete distribution whose finite values are in `[0,1]` and sum to `1` within `0.02`, and provide finite `confidence` in `[0,1]`; the `judgement` answer must also provide finite `answer_confidence` in `[0,1]`. The adapter checks the pinned runtime name, explicit routing alias, exact answer IDs, answer types, choice values, labels, score range, and required score fields. The calibration-only capture path additionally requires valid input/output usage metadata; ordinary Analysis Run mapping does not depend on that optional metadata. An absent or malformed answer is not replaced with a fabricated judgement.

## Optional calibration research tooling

The optional dataset model, runner, metrics, and report are in the isolated `api/src/calibration` source set; they do not run in the production application and are not part of release validation. The provider exposes a separate capture hook for this tooling, but ordinary Analysis Run calls use the normal path, which does not retain or expose raw responses or token usage. Synthetic software fixtures remain `DRAFT`; no legally usable human dataset or approved safety bar is checked in. Calibration is not required for the product, and all live judgements/statuses remain uncalibrated.

Keep `dataset.json`, `plan.json`, run JSON, and reports outside the checkout or under the Git-ignored and Docker-ignored `.laya-evaluation/` directory. Create the caller-owned directory with owner-only permissions (for example, `install -d -m 700 /secure/local/laya-review`). A dataset includes `schemaVersion`, `datasetId`, `datasetVersion`, `status` (`DRAFT` or `HUMAN_REVIEWED`), `protocolId`, the exact pinned candidate (`checkpoint`, `runtime`, `promptVersion`, `outputMapping`, `contextLimitTokens`), Cited Paper provenance, claim–passage cases, Claim–Paper outcomes, and aggregation candidates. Each paper records a source citation/HTTPS URL, exact acquired asset SHA-256, rights basis, and resolution status; a release dataset requires resolved papers and human-reviewed rights metadata. Each case records a stable ID, Cited Paper/Claim–Paper IDs, paper-level split, exact `sourcePage` and/or `sectionHeading` plus an opaque stable `sourceLocatorId`, Atomic Claim, Evidence Passage, adjudicated judgement/role/four 0–4 labels, every independent reviewer annotation, adjudicator/rationale, and coverage tags. Claim–Paper outcomes preserve independent reviews and the adjudicated final status/conflict. Release validation requires all judgement/role/final-status classes in held-out data, scope/qualifier mismatches, secondary-report and abstention cases, plus a support/contradiction pair whose adjudicated comparable outcome remains `INSUFFICIENT_EVIDENCE`. A human-reviewed dataset cannot use a DRAFT protocol.

### JSON field names

Names below are the literal, case-sensitive JSON property names. ISO timestamps must parse as `Instant` (recording UTC `Z` form is recommended); labels and review notes belong only in the private input file.

- Dataset root: `schemaVersion`, `datasetId`, `datasetVersion`, `status`, `protocolId`, `candidate`, `papers`, `cases`, `claimPaperOutcomes`, `aggregationCandidates`.
- `candidate`: `checkpoint`, `runtime`, `promptVersion`, `outputMapping`, `contextLimitTokens` (all must match the exact values pinned in code).
- Each paper: `citedPaperId`, `citation`, `sourceUrl`, `licenseOrRightsBasis`, `resolutionStatus`, `assetSha256`, `rightsReviewedBy`, `rightsReviewedAt`. `resolutionStatus` is `RESOLVED`, `UNRESOLVED`, or `UNSUPPORTED_REFERENCE_TYPE`; release requires `RESOLVED` and both rights-review fields.
- Each case: `caseId`, `citedPaperId`, `claimPaperId`, `split`, `sourceLocatorId`, `sourcePage`, `sectionHeading`, `atomicClaim`, `evidencePassage`, `adjudicatedLabels`, `independentReviews`, `adjudicatorId`, `adjudicatedAt`, `adjudicationRationale`, `coverageTags`. `split` is `CALIBRATION` or `HELD_OUT`; `coverageTags` may include `SCOPE_OR_QUALIFIER_MISMATCH`, `SECONDARY_REPORT`, `ABSTENTION`.
- `adjudicatedLabels` and each review's `labels`: `judgement`, `evidenceRole`, `directness`, `claimScopeMatch`, `studyDesignQuality`, `relevance`. Judgements are `DIRECT_SUPPORT`, `PARTIAL_SUPPORT`, `CONTRADICTS`, `UNRELATED`, `INSUFFICIENT`; roles are `PRIMARY_FINDING`, `AUTHOR_SYNTHESIS`, `SECONDARY_REPORT`; ordinal dimensions are integers 0–4.
- Each case `independentReviews` item: `reviewerId`, `reviewerQualification`, `reviewedAt`, `rationale`, `labels`.
- Each `claimPaperOutcomes` item: `claimPaperId`, `citedPaperId`, `split`, `expectedStatus`, `expectedConflict`, `adjudicatorId`, `adjudicatedAt`, `rationale`, `independentReviews`. Statuses are `SUPPORTED`, `PARTIALLY_SUPPORTED`, `CONTRADICTED`, `INSUFFICIENT_EVIDENCE`. Each outcome review has `reviewerId`, `reviewerQualification`, `reviewedAt`, `rationale`, `expectedStatus`, `expectedConflict`.
- Each `aggregationCandidates` item: `candidateId`, `verificationPolicyVersion`, `aggregationPolicyVersion`, `thresholds`; the thresholds object has `directSupport`, `partialSupport`, `contradiction`, `comparabilityMargin` (each 0–1).
- `plan.json` root: `schemaVersion`, `planId`, `datasetId`, `datasetVersion`, `datasetSha256`, `heldOutSplitSha256`, `candidate`, `approvedBy`, `approvedAt`, `applicationRevision`, `heldOutCitedPaperIds`, `uncertaintyMethodId`, `safetyBar`. Each safety-bar item has `metricId`, `comparison` (`AT_LEAST`, `AT_MOST`, `EXACTLY`), finite numeric `threshold`, and non-empty `rationale`.

The dated `plan.json` is separate from `dataset.json` so its hashes do not form a circular dependency. It pins the dataset file SHA-256, canonical held-out split SHA-256 and Cited Paper IDs, candidate, application revision (which identifies the embedded prompt implementation), reviewer/date, uncertainty method (`cited-paper-cluster-bootstrap-p95-v1`), and a non-empty, reviewer-supplied numerical safety bar. The repository supplies no default safety-bar values.

#### Safety-bar metric IDs

Use exact report metric IDs. Unknown IDs render `NOT EVALUABLE` and cannot meet a criterion. The currently supported IDs are:

- Case-level: `coverage`, `token_limit.failure_rate`, `judgement.accuracy`, `judgement.abstention_rate`, `judgement.<kind>.precision`, `judgement.<kind>.recall`, `role.agreement`, `score.<dimension>.exact_agreement`, `score.<dimension>.mae`, `confidence.brier`, `confidence.ece`, `high_risk.error_rate`, `high_risk.error_count`, `token_usage.input_tokens.mean`, `token_usage.input_tokens.max`, `token_usage.output_tokens.mean`.
- `<kind>` is a lowercase Evidence Judgement enum name: `direct_support`, `partial_support`, `contradicts`, `unrelated`, or `insufficient`. `<dimension>` is `directness`, `claim_scope_match`, `study_design_quality`, or `relevance`.
- Aggregation, for each exact dataset `candidateId`: `aggregation.<candidateId>.status_accuracy`, `.coverage`, `.conflict_agreement`, `.false_decisive_rate`, `.false_decisive_count`, `.insufficient_evidence_rate`, and `aggregation.<candidateId>.status.<status>.precision` / `.recall`. `<status>` is a lowercase Claim–Paper status enum name: `supported`, `partially_supported`, `contradicted`, or `insufficient_evidence`.

Metrics are calculated on completed cases/groups except coverage and token-limit failure rate, whose denominator includes all selected cases/groups. Precision and recall use predicted and gold counts respectively; both counts appear in the report. Ordinal-score exact agreement rounds the normalized prediction multiplied by four to the nearest integer; ordinal-score MAE uses the unrounded prediction on the 0–4 scale. Rate, agreement, score, and mean metrics include a Cited-Paper bootstrap interval when estimable. Counts and maximum-token summaries are descriptive. Safety-bar comparisons (`AT_LEAST`, `AT_MOST`, `EXACTLY`) compare the point estimate only; they do not apply an interval bound. The report shows intervals separately, and the reviewer must interpret them under the approved protocol. A `MEETS` result is arithmetic only; it is optional research output and does not control product release. Generate fingerprints before running this optional evaluator:

```sh
make laya-evaluation-fingerprint DATA_DIR=/secure/local/laya-review
```

That command performs no inference and prints only dataset/version identifiers, hashes, held-out paper IDs, and the committed application revision. Then have the reviewer author `plan.json` using those exact values and review the safety bar before running either split. The evaluator uses 2,000 deterministic percentile bootstrap replicates resampled by Cited Paper; a metric's interval is reported as not estimable when fewer than two paper clusters or too few valid replicates are available.

Start the sidecar separately, then run the desired split:

```sh
make laya-up
make laya-evaluate DATA_DIR=/secure/local/laya-review SPLIT=calibration
make laya-evaluate DATA_DIR=/secure/local/laya-review SPLIT=held-out
```

Held-out runs require `HUMAN_REVIEWED` labels and the exact prior plan. The Make target refuses uncommitted evaluation code, sidecar/Compose boundary configuration, and protocol/report changes so `applicationRevision` identifies the reviewed prompt and evaluation implementation. The one-shot evaluator runs on a dedicated internal Compose `laya-evaluation` network shared only with the authenticated `laya` sidecar; it publishes no port, has no database-network attachment or network egress, and does not mount the dataset during image build. The sidecar is also attached to `laya-inference` for ordinary API/worker traffic. Compose bind-mounts the caller-owned data directory read/write so the runner can create results and reports. The Make targets build and run the evaluator with the invoking host UID/GID, so private outputs remain caller-owned on Linux as well as Docker Desktop; the CLI creates output files with owner-only permissions and rejects paths that collide with the dataset or plan. The data steward must restrict directory access. It does not invoke production API/worker or change provider selection or aggregation settings. The user-owned evaluation directory persists after the run; `make clean` does not delete it, so the authorized data steward must apply the separately approved retention/deletion schedule.

The results JSON stores each mapped prediction, the exact raw Laya response as base64, runtime-reported input/output token counts, measured complete-sequence token counts on context-limit failures, stable failure codes, and dataset/candidate/plan/source pins. Raw responses can echo protected input and must stay inside the approved local data boundary; never commit or share the results JSON. The Markdown report excludes Atomic Claim, Evidence Passage, request body, and raw response text; it reports opaque case/paper IDs and source locators, sample counts, confusion/precision/recall, score and role agreement, Brier/ECE, abstention/coverage, high-risk errors, aggregation candidate comparisons, and token-limit behavior. Successful `usage.input_tokens` is the runtime's total across the six question sequences; the pre-inference guard includes exact complete-sequence token counts for all questions in a rejected request. Incomplete cases stay in coverage/failure counts and never receive fabricated judgements. Numerical safety-bar arithmetic is informational only and does not grant or gate production use.

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

## Current pipeline trial and evidence status

Read-only local database observations establish inference and persistence, not accuracy or calibration. An evaluation-only run pinned aggregation as `NOT_RUN` and persisted 10 Evidence Judgements across two Claim–Reference pairs for one Cited Paper (7 `DIRECT_SUPPORT`, 2 `PARTIAL_SUPPORT`, 1 `CONTRADICTS`); it had zero human reviews and no final semantic statuses. A more recent local aggregation run persisted 10 judgements on successful pairs but completed with warnings because one pair's top-ranked complete request measured 1,178 tokens against the 1,024-token limit. The other four candidate payloads measured within the limit, but evaluation stopped at the first top-ranked rejection and no judgement set was stored for that pair; evidence is not truncated and no final status is fabricated. The worker previously retried this deterministic HTTP 422, obscuring the cause; the API now records a terminal context-limit reason for the affected pair without retrying. These operational observations cover one Cited Paper and have no gold labels.

**MVP PDF smoke status (DB snapshot 2026-09-29):** the latest Laya-backed Analysis Run persisted 14 Evidence Judgements and ended `COMPLETED_WITH_WARNINGS` with incomplete verification work; no Human Review was attached to that run. The other latest supplied-PDF run completed without Laya judgements. This confirms app-level processing and persistence, not model quality: no Cited-Paper-held-out set, adjudicated calibration labels, or safety-bar approval was produced.

The draft aggregation fixture has six synthetic evidence cases and zero human-reviewed evidence labels; its results are informational and are not evidence of model quality. The permissive candidate (`0.700/0.650/0.700`, margin `0.100`) and design-baseline candidate (`0.800/0.700/0.800`, margin `0.080`) both agree on 6/6 draft cases; the conservative candidate (`0.900/0.850/0.900`, margin `0.050`) agrees on 5/6 and abstains on the draft partial-support case. This does not discriminate between thresholds or estimate real-world performance. **Interpretation:** retain the design-baseline only as the configured experimental starting point; these draft results do not validate it. Permissive and conservative values may be varied as optional sensitivity checks. No threshold has been validated against human-reviewed data, and all outputs remain uncalibrated; calibration is not a release requirement.

**MVP status:** Calibration and target-specific production approval are not product or release requirements. The PDF runs above count as smoke testing only; keep their outcomes and every other Laya result explicitly uncalibrated. Issue #45 is closed as not planned; this does not mean calibration passed or accuracy was established.

### Before/after impact of the aggregation-default recommendation

| Area | Before | After | Impact |
|---|---|---|---|
| Configuration | Base Spring defaulted aggregation off; Compose and `.env.example` defaulted it on. | Base Spring, Compose, and `.env.example` all default it on. | Non-Compose/base-Spring Laya runs now align with local Compose behavior. |
| New Laya runs | Aggregation ran by default in Compose, but base Spring runs without an override kept final statuses `NOT_RUN`. | New eligible runs that select Laya aggregate by default and snapshot the configured thresholds and policy versions. | More runs can produce final Claim–Paper statuses; failures and incomplete pairs remain incomplete and do not fabricate statuses. |
| Existing runs | Each run retained its original immutable configuration and results. | Unchanged. | The default change affects new runs only; it does not recalculate historical runs. |
| Calibration and release meaning | Synthetic evidence did not establish accuracy; calibration was `NOT_APPROVED`. | Still `NOT_APPROVED`; all new judgements and statuses remain explicitly uncalibrated. | Calibration is not a product or release requirement; aggregation changes behavior, not evidence quality. |
| Opt-out | Base Spring was implicitly opted out unless overridden. | Set `SYSTEM_ONE_AGGREGATION_ENABLED=false` to keep final statuses `NOT_RUN`. | Calibration is not required; operators may still disable Laya or aggregation if they prefer not to produce experimental judgements/statuses. |

### Optional calibration research plan (not a release requirement)

Per the repository owner's direction, use the exact Laya checkpoint/runtime/prompt/output mapping pinned in #21; retain the existing judgement, role, score, and expected Claim–Paper label schema; split by whole Cited Paper; and use independent double review with adjudication where practical. Compare all three documented threshold arms on frozen Laya outputs: permissive (`0.70/0.65/0.70`, margin `0.10`), design-baseline (`0.80/0.70/0.80`, margin `0.08`), and conservative (`0.90/0.85/0.90`, margin `0.05`). None is pre-approved.

**Optional research criterion:** zero observed false decisive `SUPPORTED` or `CONTRADICTED` outcomes on a locked held-out set. Any exploratory report should include per-class precision/recall, role and score agreement, confidence calibration, abstention/coverage, sample counts, and uncertainty. This optional criterion neither gates product release nor changes the fact that current outputs are uncalibrated.

The domain-specific, legally usable corpus and qualified human labelers have not yet been selected. The existing synthetic fixture and the current one-paper operational observations are not calibration data.

If the project elects to conduct optional calibration research later, the following evaluation plan may be used:

1. **Dataset and labels:** approve legally usable, in-domain Cited Papers; cover every judgement class (`DIRECT_SUPPORT`, `PARTIAL_SUPPORT`, `CONTRADICTS`, `UNRELATED`, `INSUFFICIENT`), each evidence role, scope/qualifier mismatches, secondary reports, conflicts, and abstention. Obtain independent human labels for judgement, role, scope/design scores, and expected Claim–Paper outcomes; adjudicate disagreements and hold out whole papers from tuning.
2. **Laya judgement quality:** review held-out per-class precision/recall and confusion matrix, role and ordinal-score agreement, confidence calibration, abstention, and high-risk errors for the exact pinned checkpoint/runtime/prompt/output mapping. Do not interpret the current `answer_confidence` as a calibrated probability.
3. **Aggregation policy:** compare predeclared threshold/margin candidates on frozen Laya outputs and human Claim–Paper labels; review per-status errors, conflicts, partial support, and insufficient-evidence/abstention. Approve the exact values and policy version only after the held-out results meet a predeclared safety bar.
4. **Optional deployment review:** review the exact artifact/license, private authenticated network boundary, data-retention/deletion behavior, and failure/rollback plan if an operator requests that additional review. This review is not a product release requirement.

The synthetic runtime benchmark and the current database smoke are operational/integration evidence only. The calibration harness evaluates the deterministic aggregation policy using fixture-supplied judgements; it does not invoke Laya or measure Laya's judgement accuracy. See the [calibration report](benchmarks/v1-calibration.md) for its draft-only results.
