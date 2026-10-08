# Issue #92: Successor Analysis Run design proposal

**Status: proposal for human review — not approved, and not an implementation authorization.**

## Decision requested

For the first supplied-PDF reassessment increment, approve or reject this recommended shape:

1. Materialize compatible results into fresh rows owned by the successor Analysis Run. Keep all result-graph foreign keys within that run.
2. Reuse immutable object bytes by their exact hash and existing object key where policy permits; do not copy the PDF merely to make a new run. Keep the object until no run references it.
3. Record predecessor lineage and per-result reuse provenance separately from the result graph. Never make a report or worker follow a result pointer into another run.
4. Reuse only results whose stage-specific compatibility fingerprint matches. Re-run only affected branches. Preserve the actual originating run/provider/configuration for reused outputs.

The alternative is a shared artifact model in which multiple runs point to normalized shared parse, claim, chunk, and verification records. That could reduce database duplication, but would require a broad redesign of existing same-run foreign keys, report queries, deletion, and Human Review semantics. It is not recommended for the first recovery increment.

## Existing constraints that shape the design

- `analysis_runs.configuration_snapshot` and source provenance are immutable. Run status transitions are monotonic. `analysis_run_pipeline_items` is keyed by run.
- Parsed source structures, Bibliography Entries, Citation Targets, Atomic Claims, Claim–Citation Target links, resolutions, cited-paper access, retrieval outputs, and evidence decisions are immutable or run-scoped. Composite foreign keys deliberately ensure that an output's related rows belong to the same run.
- `Canonical Papers` are shared identities, not run outputs. A successor may retain a `canonical_paper_id` when its identity decision is unchanged.
- `Human Reviews` point to one run's `Claim–Paper Verification`; they must remain attached to that original assessment.
- Recovery batches currently belong to a predecessor run. They do not yet model a frozen submission, batch revision, successor, or selection history.
- `FULL_TEXT_AVAILABLE` currently requires `source_url`, `license_identifier`, and language-detector provenance. A user-supplied PDF cannot be represented truthfully by fabricating an automatic provider, URL, or license. The asset-origin/rights schema must support supplied assets explicitly.
- The current document-analysis handler skips both source parsing and claim extraction when a parse row exists and its source hash/parser identity pass checks. It does not independently check the claim-extractor configuration on that path. A successor must validate claim compatibility before carrying claims forward; otherwise it needs a separate claim-analysis seam or must deliberately rerun the coupled source stage. The handler also schedules reference work for every Bibliography Entry. Reference resolution is a no-op when a resolution row already exists. Recovery must still prevent a selected supplied asset from falling through to automatic acquisition, and must prove that copied access rows do not trigger new provider calls.

These observations come from `core_documents.sql`, `parsed_citation_structure.sql`, `atomic_claims.sql`, `conservative_reference_resolution.sql`, `cited_paper_access.sql`, `traceable_evidence_retrieval.sql`, `human_reviews.sql`, `recovery_pdf_staging.sql`, `AnalysisRunProcessingService`, and `ReferenceResolutionService` in the current mainline schema/code.

## Proposed lineage and submission contract

A batch submission freezes one immutable selection snapshot, then creates exactly one successor for that batch. Use a client-supplied idempotency key and a unique database constraint so a retry returns the same successor.

In one database transaction:

1. Lock and recheck the active Source Document, predecessor run, batch, and batch revision.
2. Validate selected uploads and assisted identity decisions; reject stale revisions and ineligible selections.
3. Persist the frozen selection/configuration/rights/provider-consent snapshot.
4. Create the successor run with immutable parent/run lineage and its own configuration snapshot.
5. Materialize compatible unchanged results with successor-owned IDs; create new access and pending work for selected assets; initialize all expected pipeline pairs and truthful progress states.
6. Insert the successor's outbox event.

No network or provider call belongs inside this transaction. Workers recheck deletion tombstones before calls and commits. Replayed submissions and queue deliveries must be idempotent. A later explicit retry is a new batch and successor, not a mutation of a terminal run.

Lineage should have a restrictive run-to-run shape: one predecessor per successor and one submission per batch. The batch remains tied to the predecessor. The successor stores the predecessor relationship in a dedicated lineage record rather than changing immutable predecessor rows.

## Run-local ID mapping and provenance

Every copied row receives a new ID where its table is keyed by row ID. The successor's actual result graph references only successor-owned rows, satisfying its existing composite foreign keys. Required mappings include:

- parsed sections, citation contexts, citation occurrences, Bibliography Entries, and Citation Targets;
- Atomic Claims and their provisional Citation Target links;
- per-entry resolution and access outcomes;
- cited-paper parse, indexing, Evidence Passage chunks and embeddings;
- Claim–Paper Verifications, Evidence Candidates, Evidence Judgements, and any dependent passage-span results.

The copy transaction builds each old-to-new mapping before inserting dependent rows. Natural keys such as `local_reference_key`, entry order, source offsets, chunk order, and profile hash can validate correspondence; they do not replace new run-local row IDs or same-run foreign keys.

Keep provenance out of optional execution captures. Add durable lineage/reuse records that distinguish:

- the immediate predecessor row copied from;
- the actual originating run/result that made the provider decision (which may be older than the predecessor);
- result kind, compatibility fingerprint, and whether the successor reused or recomputed it.

Those IDs are audit provenance only. Queries and report relationships must use the successor's materialized rows, never cross-run result pointers. The migration should either enforce typed target references or prove source/target ownership in the transaction and integration tests; a generic, unchecked polymorphic pointer table is not sufficient.

Existing immutable acquired bytes may be shared by exact hash/key only if retention, rights, and deletion checks permit it. The selected upload starts as a staged/finalized object, so submission must persist accepted-asset ownership and fence cleanup before the staging expiry worker can delete it. If promotion to a durable asset key is required, make the copy idempotent and verify its actual bytes/hash before committing the successor. A new run-local access row may then refer to eligible immutable bytes. Deleting a document removes its runs and all their references; object cleanup must verify that no legitimate run references the key. Do not rewrite or delete the predecessor's object or access record.

## Compatibility matrix

| Result group | Reuse only when | Selected/changed branch |
|---|---|---|
| Source parse, bibliography, citation graph, claims | Same source hash and compatible source-parser, normalization, claim-extraction, and relevant validation fingerprints | Source-document edits require a full new analysis; recovery does not edit the source. |
| Reference identity | Same preserved Bibliography Entry inputs and compatible identity policy/provider configuration; no new lookup is required | A supplied identifier or assisted identity change reruns identity validation for that entry. A changed global resolver configuration invalidates its dependent identity results. |
| Access and acquisition | Same resolved identity, asset/rights provenance, and compatible acquisition policy; no new outbound request is implied | A selected PDF creates a truthful user-supplied asset outcome. It must not enqueue automatic acquisition for that entry. |
| Cited-paper parse and indexing | Same exact asset SHA-256, parser/options/language policy, normalization, and retrieval/embedding profiles | A different selected version is reparsed and reindexed; the predecessor's asset and outputs remain unchanged. |
| Claim–Paper Verification and evidence outputs | Same mapped claim/context/target, Canonical Paper, exact asset hash, retrieval profile, assessment provider/configuration, and aggregation policy | Reassess every affected Claim–Reference pair for the selected entry. Incompatible global changes invalidate all dependent pairs. |
| Human Reviews | Never copied as review of a new assessment | Keep each review attached to its original verification. The successor may link to the prior run for navigation, not inherit its review. |

Consent for new outbound work is checked against the successor snapshot, including cache-backed work. Reuse does not masquerade as a new call: the original provider and configuration remain inspectable. Do not copy execution spans as if a provider call happened in the successor; durable reuse provenance is separate from optional trace capture. Whether prior provider outputs may be reused under a changed consent state is a product/policy decision that must be made explicit before implementation.

## Current pipeline implications

A persisted parse lets the current document worker skip source parsing and claim extraction, but its compatibility checks cover source hash and parser identity/version—not a changed claim-extractor configuration. Reusing claims under a different extractor would be unsafe. The implementation needs either a separate claim-analysis seam or a deliberate rerun of the coupled source stage when its inputs are incompatible. The worker also reads and hash-checks the source object and schedules reference events for every entry. The successor therefore needs an explicit work manifest or equivalent dispatch rule that distinguishes reused, selected, and invalidated entries. It must not infer correctness from event deduplication alone.

For every entry, the new run must account for expected work exactly once. Reused terminal outputs count as completed only after their successor rows and provenance are committed. Selected entries restart acquisition/preparation/assessment using the pinned supplied asset. An assisted unresolved identity restarts resolution and then only its dependent stages. Completion waits for all expected reused/new pairs; aggregates are recomputed for the successor. Partial failures remain visible without reopening predecessor results.

The current access table cannot yet represent a supplied PDF without fake automatic-origin fields. The implementation design must add a distinct origin/rights representation before the first selected asset can enter assessment. Issue #91 must also establish the versioned Docling identity-metadata contract and mismatch/confirmation behavior before supplied assets are marked ready.

## Alternatives and trade-offs

### A. Materialize run-local outputs — recommended for the first increment

**Benefits:** preserves current same-run foreign-key guarantees; isolates immutable reports; keeps Human Reviews bound to their original assessments; lets existing report queries remain run-local; allows a small, auditable provenance extension.

**Costs:** duplicates relational rows and vector embeddings; needs tested old-to-new mappings; copies must be atomic and idempotent; object storage needs reference-aware cleanup.

### B. Shared normalized artifacts across runs

**Benefits:** less database duplication; reusable parsing/indexing artifacts can be addressed once.

**Costs:** changes ownership and deletion semantics across most output tables; forces new cross-run report/query behavior; complicates immutable snapshots, per-run access/consent attribution, and Human Reviews; increases migration and mixed-version risk. Defer unless measured materialization cost makes it necessary.

### C. Direct cross-run pointers from successor results

**Rejected.** Existing same-run foreign keys prohibit this shape, and it makes the successor's apparent completeness depend on the predecessor remaining queryable. It also risks treating old assessments or reviews as if they were made for the successor.

## Required tests before the implementation can be called ready

- same-run foreign-key integrity for every copied output family and a complete, one-to-one old/new mapping;
- predecessor immutability and correct lineage across more than one successor generation;
- exact asset hash and origin attribution, including user-supplied rights and no automatic-provider fallback;
- compatible reuse and incompatible invalidation for every row in the matrix;
- changed global configuration shown before submission, with no hidden mixed-configuration report;
- duplicate batch submission, stale revision, concurrent submission, queue redelivery, source deletion, and late-upload races;
- expected-pair accounting, partial success, terminal retry as a new successor, aggregate recomputation, and Human Reviews remaining on the predecessor;
- report/API navigation that distinguishes recomputed, reused, and incomplete results without exposing sensitive content in logs.

## Human decision needed before implementation

Please approve or change the recommended materialized-run-local model, including these linked choices:

1. Fresh successor-owned rows for all reused outputs; no direct cross-run result pointers.
2. Immutable object bytes may be shared by exact hash/key when rights and reference-aware deletion allow; relational rows and embeddings are copied.
3. A durable per-result provenance map records both immediate source and actual originating run/provider/configuration.
4. Stage-specific compatibility fingerprints govern reuse; global config changes invalidate dependent outputs and are disclosed before submission.
5. Human Reviews never transfer to successor assessments.

Provider selection, numerical budgets, and #86 evaluation targets are not decided here. This proposal does not bypass those separate gates or authorize implementation before the #89/#91 dependencies and required evidence/design approvals are met.
