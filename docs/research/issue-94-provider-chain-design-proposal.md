# Issue #94 — Versioned Provider-Chain Design Proposal

**Status:** Nonbinding proposal for maintainer review. No provider set/order, numeric budget, legal decision, or integration is approved by this document. Final coverage priorities remain gated by the #86 adjudicated baseline. This proposal uses the provider facts summarized in [the source research](issue-94-provider-chain-research.md), current code, and accepted provider-consent ADRs.

## Scope and current implementation

The current `AnalysisConfigurationSnapshot` pins one scholarly-metadata provider in `ReferenceResolutionSnapshot.provider`, plus its score policy, threshold, and configuration fingerprint. `RunConfigurationFactory` snapshots the selected provider and exact per-provider consent; `ProviderCallGate` checks the run's provider selection, declared data categories, configuration, and consent before sending a request. `ReferenceResolutionService` creates one lookup adapter and runs `ConservativeReferenceResolver`. No scholarly-metadata response cache was found in this path. See:

- [`AnalysisConfigurationSnapshot`](../../api/src/main/kotlin/com/papertrail/api/analysis/configuration/AnalysisConfigurationSnapshot.kt)
- [`ReferenceResolutionSnapshot`](../../api/src/main/kotlin/com/papertrail/api/analysis/configuration/ReferenceResolutionSnapshot.kt)
- [`RunConfigurationFactory`](../../api/src/main/kotlin/com/papertrail/api/analysis/configuration/RunConfigurationFactory.kt)
- [`ProviderCallGate`](../../api/src/main/kotlin/com/papertrail/api/infrastructure/providers/ProviderCallGate.kt)
- [`ReferenceResolutionService`](../../api/src/main/kotlin/com/papertrail/api/scholarly/references/service/ReferenceResolutionService.kt)
- [Issue #95](https://github.com/arrokh/paper-t-rail/issues/95), which owns later provider-chain implementation.

## Proposed design for approval

### 1. One immutable ordered chain snapshot

Add a versioned chain selection under `ReferenceResolutionSnapshot`, rather than reconstructing the chain from the live provider directory during processing. The snapshot should contain:

- a chain-schema and chain-policy version;
- an immutable, ordered list of provider selections, each with provider ID, adapter version, trust boundary, allowed data-category IDs, payload/configuration fingerprint, and the exact retention disclosure shown for consent;
- the matcher/identity policy version and existing confidence threshold;
- a versioned budget-policy identifier and the accepted budget values, once approved;
- a cache-policy identifier only if a cache is approved.

The ordered list records the run's requested chain. Runtime catalog changes must not silently reorder it or substitute another provider. Configuration drift, disabled providers, and unavailable adapters produce explicit outcomes; they do not rewrite the historical snapshot.

### 2. Keep one deep orchestration seam

Keep `ReferenceResolutionService` responsible for loading the run and pending entry, checking source/run activity, and persisting the final run-local decision. Put ordered lookup, fallback, candidate attribution, and budget accounting behind one chain-resolver interface. Its result should include the domain resolution decision plus bounded per-provider attempt summaries. Provider adapters remain narrow lookup implementations; they do not choose chain order, matcher thresholds, or consent behavior.

The resolver must preserve the existing conservative matching policy until a separate approved policy changes it. It must not treat one provider's metadata as proof solely because that provider returned it. Candidate identity, confidence, ambiguity, and exact work/version remain separate decisions. Any change to match thresholds or candidate-combination semantics requires its own evidence and approval.

### 3. Consent and provenance

- Retain the existing explicit per-Analysis-Run consent model from [ADR 0009](../adr/0009-configured-external-providers-with-per-run-consent.md). Every selected external provider needs a server-resolved disclosure and explicit approval of its exact declared payload categories before its request is sent. Consent is deduplicated by provider ID when the same external provider serves multiple roles.
- Check authorization before any outbound request **and before returning a cached external result**. Consent rejection is not scholarly absence and must not be cached as `not found`.
- Preserve the actual provider, adapter/configuration fingerprint, and request outcome on every resolved or unresolved path. Reuse or fallback must not make an earlier provider appear to have produced the final result.
- Apply [ADR 0020](../adr/0020-require-matching-consent-for-provider-output-reuse.md) to successor reuse: a historical external-provider output is reusable only when the successor's per-provider consent snapshot exactly matches the originating snapshot, including provider identity, authorized categories, and recorded retention disclosure. Missing or changed consent forbids reuse; new outbound work requires explicit successor consent. This match is necessary but not sufficient; all stage-specific compatibility rules still apply.
- Keep logs and metrics content-free under [§49](../paper-t-rail-tech-design.md#49-observability). Do not log bibliography text, queries, payloads, credentials, or response bodies. Execution capture, if enabled and sanitized, remains separate from operational logs.

### 4. Ordered fallback and outcomes

**Nonbinding option to evaluate:** execute selected providers in the snapshotted order. Stop when the existing matcher yields an accepted, unambiguous identity; continue after a provider is unavailable or has no usable candidate, subject to the approved budget. Preserve candidates and provenance from earlier attempts so a later provider cannot erase a useful result. If sources disagree or no provider yields a sufficiently strong identity, return an explicit unresolved/ambiguous outcome rather than silently selecting the latest candidate.

Before implementation, the maintainer must decide whether to keep matching independently per provider or compare a deduplicated candidate set across providers. The second option may improve candidate coverage but changes the resolver's evidence and needs evaluation against #86; no matcher threshold or “strong identity” rule is proposed here.

The report and persisted result should distinguish at least: resolved, unresolved/no candidate, ambiguous/conflicting candidates, consent required/rejected, provider unavailable, transient provider failure, search exhausted, and budget exhausted. These are distinct outcomes, not aliases for `not found`. Persist bounded per-provider attempt provenance independently of optional execution capture—for example, run/entry-local records containing attempt order, provider/version/configuration fingerprint, and a safe outcome code. Do not persist query text or raw response bodies as routine attempt metadata. Exact storage shape, public status names, and error mapping need schema/OpenAPI review in #95; attempt records must follow run deletion and retention rules.

### 5. Budget and retry accounting

Use a versioned budget snapshot, with dimensions considered for approval rather than values selected here:

- maximum provider calls per reference and per run;
- maximum candidates retained/compared and maximum response bytes;
- per-call timeout and total chain wall-clock budget;
- retry count and cumulative backoff/wait budget;
- maximum active requests/concurrency.

Count work across provider fallback, adapter retries, queue redelivery, and worker retry so redelivery cannot reset an exhausted per-reference/run budget. Honor a provider's `Retry-After` only within the approved cumulative wait and run deadline. Do not translate a provider's published quota into a product budget; #86 workload evidence and explicit maintainer approval are required before numeric values are recorded.

### 6. Cache semantics (proposal, not implementation approval)

There is no current scholarly-metadata cache in the inspected path. Keep cross-run response caching out of the first integration increment unless the maintainer explicitly approves its privacy, retention, and deletion behavior. If a cache is later approved:

- a candidate key should include provider ID/version, provider configuration fingerprint, request kind, query-normalization version, and a hash of the normalized lookup fields; do not expose the fields or key in logs;
- every hit must pass the current Analysis Run's provider selection/configuration and consent checks before the cached content is returned;
- cache only provider outcomes whose semantics and lifetime are approved. Never store consent rejection, transient failure, provider unavailability, or budget exhaustion as scholarly absence. Distinguish authoritative `not found` from all of those states;
- keep cached-output provenance, expiry, deletion responsibility, and source-provider attribution explicit; a cache hit is not a new provider call;
- apply ADR 0020 to historical external-provider output reuse across successor runs. A cache must not become a bypass around that rule.

Cache-key details, negative-cache behavior, TTL/retention, and whether any shared cache is permitted remain maintainer decisions.

### 7. Legacy snapshots and rollout

Do not rewrite old Analysis Run configuration JSON or pending events. Keep the current scalar-provider reader path unchanged for snapshots without the new chain field: no inferred chain, provider substitution, or new fallback behavior. New chain-capable code takes a separate path only when it sees a supported, explicitly versioned chain snapshot.

Do not dual-write the first chain provider into the legacy scalar field unless a compatibility test proves old workers cannot silently process a partial chain. Prefer reader-first deployment and an explicit producer/feature gate: enable chain snapshots only after every worker that can receive their events understands the chain version. Preserve queued legacy single-provider runs and report their actual legacy configuration. Add schema/contract, serialization, mixed-version, queue-redelivery, consent-rejection, fallback, budget-exhaustion, and provider-provenance tests before enabling new producers.

## Decisions still needed

1. **Baseline and initial providers:** wait for #86's adjudicated subgroup baseline; then choose the initial provider set and order, with optional integrations clearly separated.
2. **Candidate policy:** choose independent per-provider matching or cross-provider candidate comparison and define conflict handling without inventing thresholds.
3. **Provider authorization:** approve the exact provider trust/data categories, query minimization, retention/legal disclosures, and license fit. Provider docs do not make these product decisions.
4. **Budget:** approve numeric per-reference/run call, response, candidate, timeout, retry, concurrency, and cost limits.
5. **Cache:** decide whether shared caching is allowed, cache key inputs, negative-cache semantics, retention/expiry, and deletion behavior.
6. **Compatibility:** approve snapshot and event versioning/rollout gates and the public status/error contract before #95 implementation.

Until those decisions are approved, this proposal is for review only. It does not authorize provider credentials, paid services, external requests, or transfer of Source Documents, claims, or evidence. It does not close #94 or unblock #95.
