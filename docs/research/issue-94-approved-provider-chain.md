# Approved initial scholarly provider chain — issue #94

**Decision:** 2026-10-13, under the operator's explicitly delegated design/provider-policy authority recorded on [#85](https://github.com/arrokh/paper-t-rail/issues/85#issuecomment-6136657169). This resolves engineering choices, not human adjudication, measured coverage, accuracy targets, production rollout, or permission to send private documents. Existing matcher thresholds remain unchanged.

## Initial selection and legal/data boundaries

- Keep **Crossref** as the first broad metadata provider. Add **DataCite** as an optional exact-DOI registry fallback, not a second fuzzy-title index. Both are public, no-new-key routes. The initial chain is opt-in and feature-gated; enabling a provider does not select it or authorize a request.
- With a valid DOI, query Crossref first. On not-found or unavailable/unusable provider output, DataCite may confirm that same DOI within the shared budget. A permanent Crossref HTTP failure is never retried, but it may proceed once to the exact-DOI DataCite fallback because Crossref supplied no usable metadata; if DataCite also misses, report provider-unavailable rather than a confirmed absence. A returned identifier or bibliographic conflict stops the branch: never override it with another provider or a vague title match. Distinguish registry-unconfirmed identity, no search candidates, provider failure, consent rejection, and budget exhaustion.
- Without a DOI, use only bounded Crossref title-focused search and, when needed, an author/year refinement. Do not broadcast bibliography text to DataCite. Stop on sufficiently strong identity; same-work equivalence requires evidence, not topic similarity. Book/chapter/version ambiguity remains subject to the existing identity policy and capability blockers.
- DataCite requests contain only normalized DOI bibliographic metadata, not source text, claims, evidence, PDFs or contact email. Its public API requires no authentication, and its deposited metadata is CC0. These facts do not license linked full text. The API query-log retention is **unknown**: disclose that explicitly, not as zero. Known trust/payload boundaries are required; unknown retention alone is not an enablement veto.

**Issue #95 implementation boundary:** the frozen initial contract deliberately narrows the broader no-DOI discovery design above to one bounded Crossref title-only lookup. Author/year refinement is deferred; it is not silently added to this first implementation.
- Crossref retains its existing bibliographic/contact categories and disclosures. Do not silently send an operator contact outside its declared consent. Metadata reuse terms are not full-text rights; abstracts may have separate copyright.
- OpenAlex, Semantic Scholar and additional specialized integrations are not selected for this increment. OpenAlex's current terms/disclosure observations need clarification before a concrete integration decision. This is not a claim that those providers are unusable or that two registries cover every discipline.

## Immutable configuration and legacy behavior

Add a versioned ordered-chain extension to new Analysis Run snapshots. Pin each selection's provider ID/version, configuration fingerprint, declared payload categories and exact retention disclosure, plus the effective chain limits and mapping/policy versions. Keep the existing scalar single-provider field and absence/default deserialization for historical and queued snapshots; absent chain means the existing single-provider execution, not an implicit extra request.

Validate all enabled selections and fresh per-provider consent server-side before accepting a new run or successor. Fingerprint equality must compare the actual originating output's provider/configuration, authorized categories and disclosure, not only its immediate predecessor. Preserve the originating attribution for reused/cached output. An unchanged input without consent is not cache-authorized.

Deploy compatible readers before new producers. Keep new-chain production disabled until reader/worker/contract verification; tests and a controlled local opt-in may enable it. Disabling the chain must not reinterpret or mutate already-pinned runs or prevent deletion/cleanup.

## Bounded resource policy

These are conservative **operational ceilings**, not measured accuracy targets or vendor guarantees. Persist them in each chain snapshot; tune later only from permitted operational evidence.

| Resource | Initial ceiling / behavior |
|---|---|
| Logical lookups | At most 2 per reference: Crossref then DataCite for DOI, or up to 2 Crossref query variants without DOI |
| Wire attempts | At most 3 total, including at most 1 retry; reservations persist across queue redelivery, so worker and client retries cannot multiply them |
| Time | 80 seconds total from the first persisted resolution attempt, including throttle/backoff and later queue attempts |
| Shared egress rate | At most 1 request/second and 1 concurrent request per provider across workers, not a per-worker allowance |
| Candidates | Crossref at most 10 per query, at most 20 collected before evidence-based deduplication; exact DataCite DOI at most 1 record; persisted candidate comparisons remain bounded |
| Response | At most 1 MiB decoded JSON per response; reject oversize/unsupported encoding, never silently truncate and parse |
| Retry | Only 429, transient 5xx or network/timeout failure; jittered backoff starts at 250 ms and caps at 2 s; honor valid Retry-After only within the same deadline/attempt budget |
| Terminal non-retry | Consent/config rejection, bibliographic conflict, ordinary not-found, unsafe transport, invalid permanent request |

The 80-second cap accommodates current Crossref 5-second connect / 20-second read limits and at most three bounded attempts; it does not promise provider latency. Crossref's published public query quota is 1 request/second with concurrency 1, and DataCite's anonymous limit is 500 requests per five minutes/IP, so the shared 1/second ceiling is deliberately conservative. Persist safe request counts, bytes, duration and reason codes without source/claim/evidence text, bodies, query strings, credential headers or signed URLs.

## Cache decision

Keep legacy single-provider cache serialization/TTL behavior readable; do not destructively migrate historical entries. For **new chains**, use a versioned consent-aware cache namespace keyed by provider fingerprint, effective query/configuration/mapping version and consent/disclosure fingerprint. Gate authorization before every cache read. Store origin run/provider/configuration and exact consent/disclosure attribution with the mapped response; a hit still undergoes the normal identity policy. Entries lacking this attribution or matching consent are misses, not proof of absence.

Crossref's existing positive 30-day / not-found 1-hour TTLs are the initial ceilings for compatible new-chain entries. Never cache transient/provider/budget failures or consent rejection as scholarly not-found. DataCite shared caching is disabled for the first adapter increment; it may be added only with the same authorization/provenance contract, not by bypassing it. Response-size limits apply before parsing/caching.

The cache envelope retains its originating run UUID. A consuming run's durable history and report expose the selected provider and `CACHE` source, not another run's UUID; keep cross-run identifiers out of the public report while preserving provider attribution.

## Acquisition boundary for #96

DataCite metadata is not a new automatic PDF acquisition route. Keep the existing approved Unpaywall discovery and bounded PDF-location policy; implement verified alternative repository/version locations and honest attribution within that route. Preserve CC-BY/CC0/public-domain automatic rights, safe public-host/redirect/SSRF rules, per-successor content-host consent, byte bounds and post-acquisition exact-work validation. Pin one exact hash/version per reference and do not merge passages across versions. A location, `is_oa`, DOI, or metadata rights statement alone is not an acceptable full-text asset. Landing-page traversal, query-bearing URL expansion and HTML/XML remain separately scoped, not prerequisites here.

## Verification and remaining evidence

Contract-test registry success/not-found/failure, stop-on-conflict, no-DOI query bounds, consent-before-network/cache, exact cached origin compatibility, response-size rejection, shared throttling, persisted attempts across queue redelivery, legacy snapshots/events, and API/UI disclosures. Use permitted local/mock technical fixtures; keep broad #86 evaluation and numerical quality targets separate. No representative coverage, false-match rate or exact-asset accuracy has been established by this decision. Source/fixture rights and private-data consent remain independent gates.

## Primary-source evidence reviewed 2026-10-13

- [Crossref REST](https://www.crossref.org/documentation/retrieve-metadata/rest-api/), [metadata terms](https://www.crossref.org/documentation/retrieve-metadata/), [privacy](https://www.crossref.org/operations-and-sustainability/privacy/), [published rate-limit change](https://www.production.crossref.org/blog/announcing-changes-to-rest-api-rate-limits/).
- [DataCite REST API](https://support.datacite.org/docs/rest-api), [queries](https://support.datacite.org/docs/queries), [metadata harvesting/CC0](https://support.datacite.org/docs/harvesting-datacite-doi-metadata), [versioning](https://support.datacite.org/docs/versioning), [privacy](https://datacite.org/privacy-policy/). Website retention is not proof of API query-log retention.
- Alternatives and uncertainty remain in [the preliminary comparison](issue-94-provider-chain-research.md). The earlier [provider-neutral proposal](issue-94-provider-chain-design-proposal.md) is historical design context, not the selected policy. Its claim that no scholarly metadata cache exists is superseded: current Crossref has a consent-gated Redis response cache, but its legacy entries lack the new-chain origin envelope.
