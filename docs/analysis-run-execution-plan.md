# Analysis Run Execution — Implementation Plan

**Status:** Core API, persistence, and Execution Trace UI are implemented. The live ICLR run validated trace persistence and UI; verification was skipped because no eligible evidence was available. Content-free OpenTelemetry and finer-grained instrumentation gaps remain. This plan records the intended complete scope, not a separately maintained API contract.

## Goal and boundaries

Let every authorized user inspect how an Analysis Run worked: what each meaningful operation received, produced, and took to execute. Add an **Execution Trace** tab alongside Analysis Pipeline and Paper Review. Use familiar observability-tool composition, not a dashboard of decorative metric cards.

Record stages, sub-pipelines, transformations, retrieval, persistence batches, provider calls and attempts. Do not instrument every helper, loop iteration or SQL statement. Preserve execution outcomes separately from domain evidence outcomes. Record data useful for later performance and quality investigation, without inventing quality scores or validated accuracy.

Use PostgreSQL for durable execution records and protected payload artifacts. Use OpenTelemetry for content-free developer diagnostics; a collector/backend is optional. Do not add an enterprise audit subsystem, new broker, full event-sourcing framework, bulk export, or evaluation platform.

Related decisions: [ADR 0016](adr/0016-inspectable-analysis-run-execution.md), [observability policy](paper-t-rail-tech-design.md#49-observability), [UI system](ui-design-system.md#execution-trace).

## Existing seams

- `web/features/analysis-runs/components/analysis-run-detail-page.tsx` owns the existing Pipeline and Paper Review views and URL selection. Extend it with `view=execution` without breaking existing review links.
- `web/features/analysis-runs/pipeline.ts` defines the five user-facing stages: source, references, access, evidence, verification. Reuse those IDs; do not invent a competing stage taxonomy.
- `api/.../analysis/service/AnalysisRunProcessingService.kt` coordinates source processing; `AnalysisRunStageCompletionService.kt` owns stage completion.
- Feature queue handlers under `analysis/queue`, `scholarly/references/queue`, `scholarly/acquisition/queue`, and `evidence/queue` are async entry points. Trace context must survive event/outbox/Redis transitions and worker attempts.
- `api/.../infrastructure/providers/openai/OpenAiCompatibleChatClient.kt` is the shared chat transport, not a universal pipeline contract. Capture actual transport bodies there while role adapters own interpreted output snapshots. Other provider clients require their own transport capture seams.
- The API describes itself as local and unauthenticated. `NetworkBindingGuard` restricts where it may bind; current run routes have no per-user ownership or ACL. Execution artifact access follows the same trusted-workspace boundary as existing run inspection. Do not invent an owner identity or claim owner-only payload authorization; revisit if account access is added.
- Extend existing analysis-run query hooks and same-origin proxy behavior. Keep execution feature code within the analysis capability rather than a generic framework.

## UI specification

### Desktop: tree + waterfall + inspector

```text
Analysis Pipeline | Paper Review | Execution
Processing steps, timings, and service calls
Elapsed 82s · Running · Capture enabled
[Find operation] [Status] [Kind] [Expand / Collapse]

Operation / status          Duration   Shared time axis
▾ 01 Read the PDF             12s     ━━━━━━━━━
  ▾ Parse source              10s       ━━━━━━━
    GROBID request             9s        ━━━━━━
  ▾ Analyze claims            18s              ━━━━━━━━━
    Model request             17s               ━━━━━━━━
▾ 02 Resolve references        8s                        ━━━━

Selected span: Model request · Succeeded · Attempt 1
Overview | Input / Request | Response | Result
Provider · Model · Start · Duration · Capture fidelity
[Structured JSON / Text]    [Copy sanitized content]
Captured application body / mapped result
```

The sketch is schematic, not to scale. Use a wide, bordered workspace with a compact toolbar, sticky time ruler, aligned rows and light grid lines. Nest labels with disclosure controls. Bars share one run-relative time axis, with overlapping parallel work visible. Selecting a row/bar highlights both and opens a sticky inspector beside the waterfall on wide screens; at intermediate widths place the inspector below. Do not wrap every row in a card. No imported dark theme or unrelated palette: retain paper/ink tokens, IBM Plex Sans and monospace technical values.

- Row label, text status, numeric duration and bar all represent the same operation. Render zero-duration events as markers. Active bars extend to the last refreshed time and carry a Running label; no invented completion percentage.
- Stage rows start expanded; operation branches and retries can be expanded. Group attempts under a logical operation, retaining failed attempts after eventual success. Fetch every cursor page automatically before filtering; surface pagination failures rather than silently showing a partial trace.
- Toolbar filters operation names, one of the five canonical pipeline stages, status and kind; preserve ancestor context for matched children. Search only safe span labels/metadata initially, not payload text.
- Show queue wait and retry backoff as explicit intervals. Show operation execution and capture overhead separately. Total elapsed time is end minus start, not the sum of children. Do not compute exclusive time by subtracting overlapping child durations.
- Inspector Overview includes operation kind, trust boundary (internal/local/external), provider/model, safe HTTP route/status, attempt, safe error code, domain links and provenance. No credentials, internal hostnames, query strings or raw headers.
- Input/Request, Response and Result are separate tabs: actual application body as captured, received application response, and interpreted typed result. Internal operations use versioned snapshots rather than object dumps. Preformatted JSON/text is selectable and escaped, never interpreted as HTML. Copy copies only the authorized sanitized artifact.
- Label fidelity: Complete, Sanitized, Partial, Omitted, Removed, or Unavailable. Show why and capture/sanitizer versions. Never call a modified artifact an exact request. Unknown usage/cost stays unknown; don't invent token counts or cost estimates.
- Links to existing report items and immutable binary assets use their normal access rules. Label original documents as original, not sanitized artifacts.
- URL selection supports `view=execution&span={id}`. Validate selected spans belong to the run; don't put content in URLs.
- Small screens keep a compact operation/status/duration list with miniature timeline bars. Open the inspector below the selected item; allow local scrolling inside code/time panels, never page overflow. Execution is responsive even though Paper Review is desktop-only.
- Use existing shadcn Tabs, Collapsible, Button, Badge, Input, NativeSelect, Alert, Skeleton and Separator. A feature-specific waterfall supplies geometry, not a new primitive library. Every operation is selectable by keyboard and touch with visible focus and expanded/selected semantics. Timing has an equivalent text representation. Announce meaningful status changes, not each timer tick; respect reduced motion.

Required states: waiting, running, succeeded, failed, skipped/reused, interrupted; loading, API error, no operations yet, no filter matches, capture disabled, permission denied, artifact omitted/removed, incomplete trace, and historical recording unavailable. Expected outcomes such as no legal full text are not automatically execution errors.

## Storage and lifecycle

Proposed tables (names finalized during migration implementation):

| Table | Responsibility |
|---|---|
| `analysis_run_execution` | One run recording: recording version, trace ID, original capture choice, effective capture state, completeness and safe gap reason. Absence means legacy/unavailable, not a fabricated history. |
| `analysis_run_execution_span` | Run ID, span/parent IDs, optional causal links, logical operation ID, attempt, event ID, stage/operation kind, safe label, start/end/duration, execution status, domain outcome, safe metadata/provenance. |
| `analysis_run_execution_artifact` | Run-local sanitized content, media/schema/capture/sanitizer versions, size and fidelity. Never credentials or private PII. |
| `analysis_run_execution_span_artifact` | Span-to-artifact associations, role (input/request/response/result), capture state/reason and optional authorized original-asset reference. Enables run-local deduplication and attempt-specific relationships. |

Enforce run ownership on parent/link/artifact relationships and indexes for run/start/id and parent children. Payload artifacts are loaded only on inspector selection, not in span list responses. Run-local content hashes may support deduplication; no cross-user deduplication. Deleting one artifact removes its content from all deduplicated associations and marks affected associations Removed; explain this in the confirmation. Removing bodies never changes machine results. Use bounded text/JSON artifacts in separate PostgreSQL tables; reference immutable PDFs instead of copying binary data.

No automatic expiry initially. Measure stored bytes and capture overhead. Document deletion purges run execution records/artifacts along with derived results; any future run-deletion operation must do the same. Tombstones prevent late workers/capture writes from recreating removed data. Existing original asset deletion rules remain unchanged.

## Capture and privacy contract

Capture is on by default for new runs, with disclosure and per-run opt-out. Persist the original choice separately from effective capture state. Allow stopping future capture during a run and separately removing existing artifacts. Opt-out is not permission to reconstruct payloads later.

The approved policy permits processing content in protected execution artifacts. It does not permit private contact details, account identities, participant identifiers or authentication secrets. Public scholarly attribution needed for citations is allowed. Sanitize **before** any durable trace storage, including retry buffers. Use adapter-specific schemas and structured field removal. For Crossref and Unpaywall, allowlist bounded bibliographic and open-access-location metadata; omit abstracts, raw search terms and unrecognized fields. Treat arbitrary free text as uncertain unless the operation-specific capture policy can establish acceptable safety; omit uncertain content with a reason. Do not promise perfect generic PII detection, add a paid/external sanitizer, or transmit text to another provider to sanitize it.

Capture actual serialized application request/response bodies where permitted, not reconstructed requests. Store safe interpreted snapshots separately. Streaming records the assembled response, first-chunk latency, total duration and complete/interrupted state, not every chunk. Capture failure or unsupported safety policy produces an explicit omitted artifact, not a raw fallback. No payloads in application logs, OpenTelemetry attributes/events, metric labels, or external backends.

The current API is local and unauthenticated, and the workspace has no user/owner identity or per-run ACL. NetworkBindingGuard limits the API's binding boundary. Artifact routes therefore use the same trusted-workspace boundary as existing run and report routes; do not add a pretend owner permission check or a new identity system. This means anyone already able to access this trusted workspace can inspect recorded artifacts. Document this boundary in the capture disclosure. If account-level or shared public access is introduced, add explicit payload authorization before retaining this access model. No dedicated access-view audit system.

Validated implementation defaults: a 1 MiB sanitized artifact maximum, 100 spans per API page, 3-second summary/elapsed-time polling, and 15-second full span-list polling while a run is active. The UI automatically follows cursors and refreshes the full list once when a live run becomes terminal. Re-fetching all historical pages every 15 seconds remains a large-trace performance risk; keep explicit partial/omitted states. Select final values with representative fixtures before shipping.

## Current implementation status

The current implementation includes durable run-linked spans and sanitized artifacts, cursor-based read routes, queue/retry timing, capture controls, safe artifact inspection, and the responsive Execution Trace view. Desktop inspection keeps the selected-operation pane sticky; smaller layouts place it below the trace. The UI fetches all cursor pages automatically, supports the five canonical stage filters, and formats dates in the client time zone. Summary timing polls every 3 seconds; all span pages refresh every 15 seconds while active and once at run termination.

The following items remain open before claiming complete instrumentation coverage:

- Content-free OpenTelemetry diagnostics are not implemented.
- Verification provider calls are represented by broader operations; per-passage transport spans and distinct aggregation/persistence timing still need coverage.
- TEI transformation and some source persistence work remain inside broader spans.
- Repeated polling of all cursor pages for a live, very large trace may be expensive and needs measurement or an incremental refresh design.

## Instrumentation and timing

Use a focused run execution recorder coordinating span lifecycle and sanitized artifact persistence. Feature code names meaningful operations; adapters capture transport bodies; services capture typed input/output snapshots. Compose OpenTelemetry behind the same boundary instead of scattering independent writers. Never serialize all object fields automatically.

Keep business `analysisRunId` and `correlationId`; propagate standard trace context through event envelopes, outbox and Redis. Each worker delivery/attempt gets a distinct span; idempotent processing must not duplicate committed results. Use links for reused/shared work or multiple causes. Make clear which operations ran now versus earlier reuse.

Persist span start before long operations when storage is available, then finish with safe state. Within a process use a monotonic clock for elapsed duration and wall timestamps for placement; cross-process timing is approximate if clocks differ. Queue/backoff intervals must come from recorded timestamps/schedules, not guesses. Recovery marks abandoned spans Interrupted; trace repair never calls providers. Bounded persistence retries and bounded buffers only. Trace failure does not fail analysis or alter its retry policy. Mark completeness best-effort rather than promising detection of every missing record.

## Planned API surface

Proposals below are not separately maintained OpenAPI specifications. Implement through existing controller/service conventions and generated Springdoc contracts. The JSON contract below keeps the UI/API seam fixed:

- Execution summary: `{ analysisRunId, captureRequested, captureEnabled, recordingState, completeness, startedAt, finishedAt, totalDurationMillis, gapReason }`. `captureRequested` is the immutable start choice (null when no execution record exists); `captureEnabled` reports whether payload capture remains enabled, while `recordingState` indicates whether the run can still record operations. `recordingState` is `RECORDING`, `STOPPED`, or `NOT_RECORDED`; completeness is `COMPLETE`, `INCOMPLETE`, `RECORDING`, or `NOT_RECORDED`. `gapReason` is the first safe trace-gap reason code, or null when no reason was recorded. A legacy run returns `NOT_RECORDED` with null timing and gap reason, never fabricated history.
- Span page: `{ items, nextCursor }`, sorted by start time then ID ascending. A span includes `{ id, parentSpanId, operationId, stageId, kind, name, startedAt, endedAt, durationMillis, status, attempt, providerId, modelId, httpStatus, safeErrorCode, attributes, artifactRoles }`. Parent/operation IDs connect pages. Bound page size and return opaque `nextCursor`.
- Span detail: one span with safe metadata and artifact descriptors `{ id, role, fidelity, reason, mediaType, sizeBytes }`. Artifact content is fetched only on explicit selection.
- Artifact response: `{ id, spanId, role, fidelity, reason, mediaType, content, schemaVersion, captureVersion, sanitizerVersion, sizeBytes }`. `content` is sanitized text/JSON; deleted or unavailable bodies have no content and a truthful state.
- New-run `captureExecution` defaults to true when omitted. Capture stop applies to future operations only.

- `GET /api/v1/analysis-runs/{id}/execution`: recording/capture state, completeness, elapsed/interval summary.
- `GET /api/v1/analysis-runs/{id}/execution/spans`: bounded cursor page with parent/context loading and safe metadata.
- `GET /api/v1/analysis-runs/{id}/execution/spans/{spanId}`: selected span and artifact descriptors/domain links.
- `GET /api/v1/analysis-runs/{id}/execution/artifacts/{artifactId}`: separately authorized sanitized artifact; prevent browser/proxy caching (`Cache-Control: no-store`). Never log the response body.
- `PATCH /api/v1/analysis-runs/{id}/execution/capture`: stop prospective capture; no implicit deletion or historical backfill.
- `DELETE /api/v1/analysis-runs/{id}/execution/artifacts/{artifactId}`: remove artifact content with association tombstones.
- Extend new-run configuration with an explicit capture choice, default enabled; creation disclosure is separate from external-provider consent.

Define pagination, stable ordering, filter semantics, concurrency and authorization in implementation. Unknown run/resource yields existing not-found semantics; legacy execution yields an explicit unavailable state. Document validation, access-denied and unavailable/removed-body responses as applicable to the real access model. Update Springdoc annotations and `OpenApiDocumentationTest.kt` together; `/v3/api-docs` remains the contract.

## Vertical delivery slices

1. **One observable source operation:** migration, recorder, source-stage and GROBID call instrumentation, protected request/response/result artifacts where safety permits, execution read APIs, minimal Execution Trace tab/waterfall/inspector. Include opt-out, sanitizer omission and deletion behavior immediately—not later privacy hardening. Verify a complete input → call → response → mapped result chain.
2. **Source sub-operations and async attempts:** validation, TEI transformation, claim analysis/provider calls, persistence batches, queue wait, retries, capture overhead, context propagation and interruption states. Ensure live queries stop when the run is terminal and don't re-fetch large artifacts each poll.
3. **All remaining meaningful operations:** reference lookup/matching, acquisition and language gating, Docling/chunking/embedding/retrieval, System One calls and aggregation. Preserve domain links, reuse and diagnostic Evidence Passage Span relationships without conflating the two span types.
4. **Complete inspectability:** metadata filtering, ancestor context, bounded branch loading, URL span selection, responsive layout, stop/remove controls, all empty/permission/fidelity states and content-free OpenTelemetry diagnostics. No backend required for product inspection.

Each slice includes its API annotations, behavior verification and usable UI; this is incremental delivery, not approval to ship a partially instrumented system as complete. After every meaningful operation is covered, label only genuinely unknown gaps as incomplete.

## Acceptance and verification

- A user follows a run from stage to operation to sanitized input/response/result and understands what happened and how long it took.
- Use independently defined fixture timelines: two overlapping 10-second children inside a 12-second parent display 12 seconds total, not 20. Queue wait and backoff remain distinct.
- Failed retry attempts survive successful completion; reuse has no invented historical duration; unknown/interrupted spans are not marked succeeded.
- A fixture credential, private email and participant identifier never enter capture storage/logs/exports. Allowed public attribution survives. Uncertain text and sanitizer failures omit bodies without failing analysis. These tests establish specific safety behavior, not universal PII guarantees.
- Verify opt-out, stopping in flight, artifact removal including deduplicated associations, run/document tombstones and no late recreation. Verify cross-run resource IDs cannot access another run's artifacts.
- Malformed responses remain inspectable only if safely captured; raw response and mapping failure are distinct. Bound response/capture size and flag partial data truthfully.
- Validate the public behavior seam before writing tests; use vertical behavioral slices, not tests pinning internal helper structure. Run API contract/integration tests, web tests/lint/typecheck/build, and desktop/mobile browser plus keyboard/accessibility checks when implemented.
- Confirm no payloads or high-cardinality run/claim IDs enter metrics labels. Optional OTel exports are content-free. Include an incomplete-recording/storage-failure fixture.

## Implementation checkpoints, not another interview

Before capturing a body, use the existing trusted-workspace boundary, per-operation sanitization policy and bounded size limit. If a body cannot be safely captured, ship an honest omitted state rather than claiming complete visibility. These are concrete implementation checks, not reasons to add a generic privacy platform.
