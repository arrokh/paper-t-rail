# Pipeline Implementation and Feature Traceability Matrix

**Snapshot:** 2026-10-04

**Scope:** current repository implementation compared with [`paper-t-rail-tech-design.md`](./paper-t-rail-tech-design.md), especially sections 3, 14–16, 30, 57–59, and the accepted ADRs. This is a source review; it does not prove that a deployed environment is configured or that a full-system run has passed.

## Current readout

The five persisted pipeline stages have corresponding code paths and progress records. The core V1 path is present, with run-level configuration determining whether the final assessment stage executes. Stage 01 supports document-level claim analysis: local Spring and Compose defaults select the deployment-configured OpenAI-compatible provider, while the heuristic remains explicitly selectable. The provider extracts Atomic Claims and selects same-context Citation Targets in one operation; fresh runs preflight its `/v1/models` endpoint before reading or parsing the Source Document. It remains unavailable unless server-side settings make it selectable. The [implementation plan](./openai-compatible-provider-plan.md) records its contract and verification; the disabled Google Gemini catalog entry remains non-callable.

“LLM integration” refers to different jobs in the design. Paper T-Rail has local Ollama embeddings in Stage 04 and a local Laya System One provider for narrow Evidence Judgements in Stage 05. Neither is a generic chat-generation integration. The implemented OpenAI-compatible adapter serves Stage 01 only; Stage 04 embeddings and Stage 05 System One remain future consumers, not part of this implementation.

## Current five-stage flow and effective configuration

The diagram below separates the five persisted worker stages from the work inside each stage. Provider names in this diagram are the defaults for a new Analysis Run; an enabled option in the provider catalog is not automatically selected.

```mermaid
flowchart LR
    PDF["Source Document PDF"] --> S1
    S1["01 · source / parse-document<br/>PDF preflight: PDFBox 3.0.5<br/>Scientific parse: GROBID 0.9.1-crf<br/>TEI → Citation Contexts + Bibliography Entries<br/>Claims: OpenAI-compatible local default; heuristic selectable"] --> S2
    S2["02 · references / resolve-entry<br/>Default: recorded-fixtures v1<br/>Optional: Crossref v1 + run consent"] --> S3
    S3["03 · access / acquire-source<br/>Default: recorded-fixtures v1<br/>Optional: Unpaywall v2 + run consent<br/>Acquire eligible legal full text"] --> S4
    S4["04 · evidence / prepare-evidence<br/>Compose: Ollama nomic-embed-text:v1.5, 768d<br/>Selection fallback: feature-hash-384-v1<br/>Postgres FTS + pgvector + RRF"] --> S5
    S5["05 · verification / assess-and-aggregate<br/>Run default: Laya when selectable; else mock v1<br/>Experimental aggregation: Laya only<br/>Judgements/statuses are uncalibrated"] --> REPORT["Evidence Coverage Report<br/>read projection"]
    REPORT -. separate append-only assessment .-> REVIEW["Human Review"]
```

Stage 01 produces run-scoped parsed structure, claims, and inferred links from each claim to the Citation Targets in its own Citation Context. Stages 02–03 work per Bibliography Entry; Stages 04–05 work on eligible claim × cited-reference evidence. Human Review is outside these five worker stages and does not rewrite machine outcomes.

| Pipeline stage / persisted ID | Current behavior | Configuration in this checkout | Selection and limits |
|---|---|---|---|
| **01 — Read the PDF** `source / parse-document` | Validate the uploaded PDF, call GROBID, parse TEI into sections, Citation Occurrences, Citation Contexts, and Bibliography Entries (excluding uncited heading-only bibliography artifacts), then analyze all contexts for Atomic Claims and same-context target selections. | Validation parser: PDFBox `3.0.5`; language detector: Optimaize `0.6`. Analysis parser: `grobid`, version `0.9.1-crf`; Compose URL `http://grobid:8070` (non-Compose fallback `http://127.0.0.1:8070`); response limit `64 MiB`. GROBID request explicitly sets `consolidateHeader=0` and `consolidateCitations=0`. Local default: `openai-compatible-chat`, model `microsoft/phi-4-mini-reasoning`, base URL `http://127.0.0.1:1234` (`host.docker.internal:1234` in Compose); the client adds `/v1` if absent. Fresh runs perform a content-free `GET /v1/models` preflight before PDF retrieval/parsing and discard its response body. | The heuristic remains explicitly selectable and preserves bounded coordination/verb splitting. The Chat Completions adapter returns claims and selected target keys together, batches only at Citation Context boundaries, and sends only Citation Contexts plus minimal bibliography metadata. GROBID remains responsible for structure and citation-marker target identity; provider failure never silently falls back. |
| **02 — Resolve references** `references / resolve-entry` | Match Bibliography Entries to Canonical Papers and record unresolved outcomes under a run-pinned policy. | New-run default: `recorded-fixtures` `v1`. Configured confidence threshold: `0.9`. Spring and local Compose defaults enable Crossref as optional `v1`, with a local test contact value unless overridden. | Crossref is used only if selected for the run and consented to; configuration enablement alone does not select it. `ProviderCatalog.safeDefaults` itself defaults external providers to disabled; Spring/Compose explicitly override this for local consent-flow testing. Deployment operators must review the supplied enablement/disclosure/contact values. No Semantic Scholar adapter is present. |
| **03 — Acquire cited sources** `access / acquire-source` | Discover accessible locations, enforce acquisition policies, and bind each acquired Cited Paper Asset to an immutable source/hash and provenance. | New-run default: `recorded-fixtures` `v1`. Spring and local Compose defaults enable Unpaywall as optional `v2`, with a local test contact value unless overridden. | Unpaywall is used only if selected for the run and consented to. Only eligible legal full text continues to semantic evidence work; abstract-only and unsupported-language sources are not semantically judged under V1. Deployment operators must review the enablement/disclosure/contact values. |
| **04 — Prepare evidence** `evidence / prepare-evidence` | Parse exact acquired assets, chunk them, embed chunks and claims, then retrieve ranked candidate Evidence Passages. | In Compose, Ollama is enabled and preferred when selectable/local: `nomic-embed-text:v1.5`, dimension `768`, endpoint `http://ollama:11434`, timeout `60,000 ms`. If Ollama is not selectable/local, new runs select local `feature-hash-384-v1`, dimension `384`. Retrieval profile: `postgres-hybrid-rrf-v1`; vector candidates `10`; lexical candidates `10`; final candidates `5`; RRF constant `60`. | Base `application.yml` leaves Ollama disabled with an empty URL/model, so its non-Compose effective default is feature-hash. The feature-hash vectorizer is lexical word/bigram hashing, not semantic embeddings. Ollama external to trusted hosts requires reviewed enablement, retention disclosure, and per-run consent. No OpenAI-compatible embedding adapter exists. |
| **05 — Assess evidence** `verification / assess-and-aggregate` | Ask System One for a typed Evidence Judgement per eligible claim/passage pair, then optionally apply deterministic policy to aggregate Claim–Paper Verification. | Default provider ID: `laya`; Compose endpoint `http://laya:8000`, timeout `120,000 ms`; pinned Laya model/runtime and output mapping are recorded in provider provenance. `LAYA_ENABLED=true`, but it is selectable only with a valid API key and trusted local host. When omitted from a run request, unavailable Laya falls back to `mock` `v1`; an explicitly requested unavailable provider fails closed. Aggregation defaults on with direct support `0.80`, partial support `0.70`, contradiction `0.80`, comparability margin `0.08`. | Aggregation is applied only to Laya runs while the local Laya aggregation option is enabled; otherwise its snapshot can be `NOT_RUN`. Neither raw Laya judgements nor aggregated statuses are calibrated. Provider/runtime failure is not a silent switch to a different model. |

**Why Stage 05 is uncalibrated:** the pinned Laya checkpoint is documented as trained for unrelated synthetic workflows, and its probability/confidence outputs need refitting for this evidence domain ([evaluation notes](./laya-evaluation.md)). The repository has no legally usable, human-reviewed in-domain calibration dataset; software smoke fixtures do not supply that evidence. The configured aggregation thresholds are deterministic policy values, not calibration. Issue #45 records calibration as not planned and not a release requirement, so results must continue to be labeled uncalibrated.

**How to read the configuration:** `application.yml` supplies Spring defaults; `infra/docker-compose.yml` overrides several for the local stack; Analysis Run request defaults then choose providers and snapshot the selected configuration. Therefore, “Crossref enabled” or “Laya enabled” describes availability, while “selected provider” describes the actual run. The Crossref/Unpaywall contacts and review flags in these defaults are documented as local consent-flow test settings, not proof of a reviewed production setup. Production deployments may override environment values; this source review does not prove their live configuration.

## Component-level plugability plan

The target model is **pipeline → subpipeline/component → method/port → provider choice**. Each leaf should say whether it is already selectable, only has an implementation interface, or is currently a fixed internal method. That keeps provider selection scoped to the operation it performs.

| Pipeline | Component / subpipeline | Current method and provider slot | Plugability plan |
|---|---|---|---|
| **01 — Read the PDF** | PDF preflight | `PdfDocumentValidator` uses PDFBox `3.0.5`; source hash, type, size, page count, and text checks are deterministic. | Keep as a local validation method. It is not an LLM provider slot; add an alternative only if there is a concrete parser/validation requirement. |
| **01 — Read the PDF** | Language detection | `DocumentLanguageDetector` interface; current implementation is Optimaize `0.6`. | An interface exists, but it is not a run-selectable provider role. Add provider selection only if another detector is needed; pin detector/version if selected. |
| **01 — Read the PDF** | Scientific document parsing | `ScientificDocumentParser` interface; current runtime implementation is GROBID `0.9.1-crf`, selected through Spring configuration and pinned in the run. | An interface exists, but only GROBID is wired. A future parser can implement this boundary; it does not belong behind the OpenAI-compatible LLM transport. |
| **01 — Read the PDF** | TEI parsing and Citation Context segmentation | `GrobidTeiParser` and `CitationContextSegmenter` convert GROBID TEI/callouts into source-traceable contexts and targets. Uncited entries containing only a known bibliography heading and no meaningful bibliographic fields are excluded from the structured bibliography; original TEI remains preserved. | Keep deterministic and local by default. GROBID remains responsible for document structure and citation-marker target identity. Do not filter all `UNSUPPORTED_REFERENCE_TYPE` entries: that resolution status also applies to valid books. |
| **01 — Read the PDF** | Atomic Claim extraction and Citation Target selection | `ClaimAnalysisProvider`; local configuration defaults to `openai-compatible-chat`, while `heuristic` `v1` remains selectable. `OpenAiCompatibleClaimAnalysisProvider` uses `OpenAiCompatibleChatClient`; claim payloads and content-free availability probes pass through `ProviderCallGate`. | The adapter returns document-level claims and target keys together. It validates exact context coverage, non-empty in-context source spans, and target membership; bounded deterministic batching does not split contexts. Availability is checked before fresh document processing; Stage 04/05 adapters remain future work. |
| **01 — Read the PDF** | Claim-to-Citation-Target association | Heuristic links each claim to all targets in its Citation Context; the model provider may select a subset or none. All links are inferred/provisional. | [ADR 0009](./adr/0009-select-citation-targets-per-claim.md) governs run-pinned same-context selections. `ParsedDocumentRepository` persists only selected links; `ClaimCitationPairCounter` counts them. Unlinked claims remain visible as “No Citation Targets” and produce no Claim–Paper Verification. ADR 0004 remains active for run-scoped structure and provenance. |
| **02 — Resolve references** | Scholarly metadata lookup | `ScholarlyMetadataLookupFactory`; new-run default `recorded-fixtures`, with Crossref selectable when configured and consented. | This provider role is already selectable per run (`scholarlyMetadataProvider`). Add metadata services such as Semantic Scholar as adapters to this port. |
| **02 — Resolve references** | Reference matching policy | `ConservativeReferenceResolver` applies deterministic matching; confidence threshold defaults to `0.9` and is snapshotted. | Provider supplies metadata; matching policy decides whether the Bibliography Entry resolves. Keep these as separate slots. A future matching algorithm needs its own strategy/config version, not an LLM silently resolving identity. |
| **03 — Acquire cited sources** | Open-access discovery and fetch | `OpenAccessProvider` exposes `discover` and `fetch`; new-run default is `recorded-fixtures`, with Unpaywall selectable when configured and consented. | This provider role is already selectable per run (`openAccessProvider`). Add another OA adapter here; legal-location, host, and network checks remain enforced by application policy. |
| **03 — Acquire cited sources** | Eligibility and access policy | `CitedPaperAccessService` applies access, language, and acquisition rules; eligible downloads become hash-pinned Cited Paper Assets. | Keep legal and safety decisions deterministic. Do not use generated text as proof of rights or access eligibility. |
| **04 — Prepare evidence** | Cited Paper parsing | `DefaultCitedPaperParser`: PDF uses `ScientificDocumentParser`/GROBID; plain text uses `plain-text` `v1`. | A parser interface exists, but parser choice is not a run-selectable provider role. New parsers can implement the boundary; pin the exact parser/version per asset/run. |
| **04 — Prepare evidence** | Chunking | `SectionAwareEvidenceChunker`, section-aware with target `700` words and maximum `900`. | Current deterministic method, not a provider. Make the chunker a strategy only if an alternative chunking method is required; include its version/limits in retrieval provenance. |
| **04 — Prepare evidence** | Embedding | `EmbeddingProvider`; local `feature-hash-384-v1` and Ollama `nomic-embed-text:v1.5` (`768d`) are implemented. New runs prefer trusted/local Ollama when selectable, otherwise feature-hash. | Already selectable per run (`embeddingProvider`). Add a role-specific OpenAI-compatible embeddings adapter. Require model and dimension for that endpoint; fingerprint provider/model/dimension because vector spaces cannot be mixed. |
| **04 — Prepare evidence** | Retrieval and ranking | `PostgresHybridEvidenceRetriever`: PostgreSQL FTS + pgvector, fused by RRF. Current profile `postgres-hybrid-rrf-v1`, candidates `10/10/5`, RRF constant `60`. | Parameters/profile are configurable and snapshotted; the retriever algorithm itself is currently concrete. A replacement retrieval method needs an `EvidenceRetriever` strategy boundary and its own profile/version. It is separate from the embeddings provider. |
| **05 — Assess evidence** | Evidence Judgement | `SystemOneProvider`; `mock` `v1` and local Laya are implemented. Jev appears as a disabled catalog entry with no runtime adapter. | Already selectable per run (`systemOneProvider`). Add a Jev adapter and, separately, an OpenAI-compatible System One adapter. Both must map into the same typed Evidence Judgement contract; transport may be shared, provider/model configuration stays role-specific. |
| **05 — Assess evidence** | Claim–Paper aggregation | `EvidenceAggregationPolicy` applies deterministic thresholds; current experimental aggregation is Laya-specific and configurable. | Keep aggregation separate from System One provider choice. Changing its method requires a policy/version change; it is not another model call. |

### Example input → process → output per component

All examples below use a **fictional** Source Document sentence, `Program X reduced outcome Y [12, 13].`, and fictional bibliography entries `[12]` and `[13]`. They illustrate the shape of data at each boundary; they are not captured outputs from a live run. Offsets and hashes are abbreviated.

#### Pipeline 01 — Read the PDF

- **PDF preflight**
  - Input: uploaded `draft.pdf` bytes, content type, filename, and source SHA-256.
  - Process: verify PDF signature/type and configured size/page/text limits; PDFBox `3.0.5` parses the file and extracts text for validation.
  - Output: a `ValidatedPdf` with sanitized filename, source hash, page count, detected language, and extracted-character count; invalid input returns a validation error code.
- **Language detection**
  - Input: text extracted from the PDF.
  - Process: `OptimaizeDocumentLanguageDetector` estimates language and confidence.
  - Output: `LanguageDetection(language="en", confidence=c)`; the validator checks `c` against the configured minimum.
- **Scientific parsing — GROBID**
  - Input: original PDF bytes.
  - Process: POST to `/api/processFulltextDocument`, with `consolidateHeader=0` and `consolidateCitations=0`; the local adapter receives TEI XML.
  - Output: the GROBID service returns TEI XML to the adapter. `GrobidScientificDocumentParser` passes that response into the next parsing substep; its public `ScientificDocumentParser.parse` result is the combined parsed structure. Parser identity/version is pinned as `grobid` / `0.9.1-crf`.
- **TEI parsing and Citation Context segmentation**
  - Input: TEI containing body text, a callout such as `[12, 13]`, and its bibliography targets.
  - Process: `GrobidTeiParser` normalizes section text and offsets; `CitationContextSegmenter` finds the citation-bearing clause or sentence fallback.
  - Output: a `ParsedScientificDocument` containing normalized text, sections, a Citation Context for `Program X reduced outcome Y [12, 13].`, an occurrence targeting keys `ref-12` and `ref-13`, and both parsed Bibliography Entries. Offsets are zero-based, end-exclusive UTF-16 offsets.
- **Atomic Claim extraction**
  - Input: one document-level `ClaimAnalysisRequest` containing all Citation Contexts, their occurrences, context-local target keys, and the referenced bibliography fields needed for selection.
  - Process: selected `ClaimAnalysisProvider` returns claims and selected target keys together. The local default is `openai-compatible-chat`; heuristic remains selectable and preserves all-target behavior. A fresh run checks `GET /v1/models` before reading/parsing the Source Document, then submits the full input once when it fits or packs whole contexts into deterministic batches. Shared service and adapter validation reject missing/duplicate contexts, invalid spans, and unknown/cross-context/duplicate target keys.
  - Output: one `CitationContextClaims` group per input context, including empty claims and claims with an empty selected-target list. Absolute source spans stay tied to the normalized source text.
- **Claim-to-Citation-Target association**
  - Input: each claim and exact target keys from its own Citation Context (`ref-12`, `ref-13`).
  - Process: heuristic selects all target keys; the explicit model provider may select a subset or none under ADR 0009. `ParsedDocumentRepository` persists only selected links as inferred/provisional, and the pair counter counts these links.
  - Output: claims remain visible with “No Citation Targets” when the selected set is empty; only persisted selected links create Claim–Paper Verification work. Such claims are not treated as unresolved bibliography matches.

#### Pipeline 02 — Resolve references

- **Scholarly metadata lookup**
  - Input: DOI when present, otherwise parsed reference fields such as title, authors, and year.
  - Process: selected `ScholarlyMetadataLookup` calls `byDoi(doi)` or `search(reference)`. Current run default is `recorded-fixtures`; Crossref is an optional selected provider with consent.
  - Output: zero or more `ScholarlyWork` candidates containing DOI, title, authors, and year.
- **Reference matching policy**
  - Input: for example `BibliographyReference(doi="<doi-12>")` and `ScholarlyWork(doi="<doi-12>", title="Study of Program X", ...)` from lookup.
  - Process: `ConservativeReferenceResolver` confirms a normalized exact DOI when available; otherwise `ScholarlyMetadataMatcher` evaluates metadata candidates against the pinned policy and threshold `0.9`.
  - Output: `ReferenceResolutionDecision`, such as `RESOLVED / DOI_CONFIRMED / score=1.0`, or `UNRESOLVED` with a reason code. A lookup result alone does not resolve the entry.

#### Pipeline 03 — Acquire cited sources

- **Open-access discovery**
  - Input: resolved reference metadata, for example `title="Study of Program X"`, authors/year, and `doi="<doi-12>"`.
  - Process: selected `OpenAccessProvider.discover(reference)`; current default is `recorded-fixtures`, with Unpaywall as an optional provider.
  - Output: `OpenAccessDiscovery` with metadata/abstract availability and candidate `OpenAccessLocation` values (URL, license, version, host type).
- **Legal-location filtering**
  - Input: discovered `OpenAccessLocation` candidates, such as a repository URL with its declared license/version/host type.
  - Process: `LegalOpenAccessLocationPolicy` removes locations that do not meet the application's usable-location rules.
  - Output: the ordered permitted locations to try, or an empty list.
- **Full-text fetch**
  - Input: one permitted `OpenAccessLocation`.
  - Process: the selected `OpenAccessProvider.fetch(location)` retrieves bytes; the service extracts text and may try the next permitted location after a fetch/extraction failure.
  - Output: `AcquiredFullText(bytes, mediaType, location)` plus extracted text for language detection, or no acquired full text after permitted attempts fail.
- **Language eligibility and access decision**
  - Input: discovery availability flags, whether full text was acquired, extracted text, and its detected language/confidence.
  - Process: `CitedPaperAccessPolicy` applies the V1 access/supported-language rules; only a language result meeting the configured confidence threshold is passed as supported language.
  - Output: for the English example, `FULL_TEXT_AVAILABLE` with `FULL_TEXT` scope and a persisted exact-asset hash/location/language. Other paths record outcomes such as `ABSTRACT_ONLY`, `METADATA_ONLY`, `UNAVAILABLE`, or full text with unsupported language and `INSUFFICIENT_EVIDENCE`.

#### Pipeline 04 — Prepare evidence

- **Cited Paper parsing**
  - Input: bytes from the exact acquired Cited Paper Asset plus media type.
  - Process: `DefaultCitedPaperParser` sends PDFs through `ScientificDocumentParser`/GROBID and normalizes plain text with `plain-text v1`.
  - Output: `ParsedScientificDocument` with parser provenance and sections, for example a `Results` section; the surrounding evidence record remains associated with the run's exact asset/hash.
- **Chunking**
  - Input: parsed sections and paragraphs.
  - Process: `SectionAwareEvidenceChunker` groups text by section toward 700 words, splitting at a maximum of 900 words.
  - Output: ordered `EvidenceChunk` values, for example chunk `0` from `Results`, paragraph `1`, with its text and section/paragraph coordinates; these are candidate passages, not judgements.
- **Embedding**
  - Input: each chunk as `cited_paper_chunks` and each Atomic Claim as `atomic_claims`, with the run's pinned embedding configuration.
  - Process: selected `EmbeddingProvider` computes vectors. In local Compose this is normally Ollama `nomic-embed-text:v1.5`; the local feature-hash provider is the selection fallback.
  - Output: a `FloatArray` vector per input. The retrieval service checks finiteness and dimension; the run snapshot/profile records provider/model/version/dimension. Example vector length is `768` for the Compose Ollama profile or `384` for feature-hash; a future compatible model must use its own configured dimension/profile.
- **Retrieval and ranking**
  - Input: Analysis Run ID, Bibliography Entry ID, claim text/vector, exact asset chunks, and pinned retrieval configuration.
  - Process: `PostgresHybridEvidenceRetriever` finds vector and English FTS candidates within that run/reference/profile, then RRF fuses them (`10` vector, `10` lexical, final `5`, constant `60`).
  - Output: ranked Evidence Passage candidates, for example `chunk-0(vectorRank=1, lexicalRank=2, fusedRank=1, fusionScore=...)`. Ranking does not decide support or contradiction.

#### Pipeline 05 — Assess evidence

- **Evidence Judgement**
  - Input: `SemanticJudgementRequest` containing claim text `Program X reduced outcome Y` and one or more retrieved Evidence Passages from its resolved cited work.
  - Process: selected `SystemOneProvider` evaluates each claim/passage pair. Current options are `mock` and Laya; future Jev and OpenAI-compatible adapters must map to the same domain result.
  - Output: `SemanticJudgementResult` with an `EvidenceJudgement` per candidate, for example `DIRECT_SUPPORT` with role `PRIMARY_FINDING` and rubric scores. Other allowed judgements are `PARTIAL_SUPPORT`, `CONTRADICTS`, `UNRELATED`, and `INSUFFICIENT`. Laya results remain uncalibrated.
- **Claim–Paper aggregation**
  - Input: judgements for one Atomic Claim × Cited Reference plus the run's aggregation thresholds.
  - Process: `EvidenceAggregationPolicy` compares rubric strength for support and contradiction; comparable conflict within margin `0.08` yields `INSUFFICIENT_EVIDENCE` with `evidenceConflict=true`.
  - Output: `EvidenceAggregationDecision` with final status, conflict flag, and strongest support/contradiction values; for example, direct support above `0.80` with no comparable contradiction can yield `SUPPORTED`. This is a deterministic policy result, not a model judgement or calibration result.

### Shared OpenAI-compatible service

The first implementation is Pipeline 01 only. Pipeline 04 and Pipeline 05 below are future extension points, not deliverables in the current plan.

```mermaid
flowchart TD
    P1["01 Claim analysis"] --> T["Shared Chat Completions"]
    P4["Future 04 Embeddings"] -.-> T
    P5["Future 05 System One"] -.-> T
    T --> API["Configured compatible endpoint"]
```

- **Input:** the Stage 01 adapter submits all structured Citation Contexts, their GROBID-derived candidate targets, and minimum bibliography fields needed for selection. It does not submit raw TEI or the full Source Document body. External calls require consent for the actual categories sent.
- **Process:** the shared chat transport applies the configured base URL, server-side secret, timeout, and bounded HTTP behavior, then returns the provider response. Stage-specific adapters own prompts, schemas, domain validation, and mapping; the transport does not normalize all use cases into one domain contract.
- **Output:** the Stage 01 adapter returns context-grouped Atomic Claims and selected same-context target keys. Future adapters must map into their own application types; embeddings require a separate compatible embeddings operation.

The local Stage 01 default is the configured OpenAI-compatible provider using `microsoft/phi-4-mini-reasoning` at `http://127.0.0.1:1234` (Compose uses `host.docker.internal:1234`). Pin provider/model, non-secret endpoint fingerprint, target-selection policy, prompt/output mapping, trust boundary, and retention disclosure in the Analysis Run; keep credentials server-side. Before fetching or parsing a fresh run's PDF, the worker performs a content-free `/v1/models` availability check through `ProviderCallGate`. Whole-context batches are bounded by the configured token estimate, byte ceiling, response-byte limit, and total request timeout. Transient network/timeouts/HTTP 408/429/5xx are retryable; invalid configuration, consent, or output contracts fail permanently and are dead-lettered without provider substitution. External Stage 01 requests declare `citation_context` and `bibliographic_metadata` and require matching per-run consent before both the probe and content-bearing requests. Stage 04 and Stage 05 adapters/settings remain future, role-specific work.

## Status vocabulary

| Status | Meaning |
|---|---|
| **Implemented** | A code path and its persisted or user-visible boundary exist in this checkout. This does not imply a successful deployed run. |
| **Implemented with limits** | The core path exists, but the row names a planned provider, scope, or operational condition that is absent or bounded. |
| **Design only** | The design describes the feature, but no matching runtime implementation was found. |
| **Verification gap** | The behavior exists at code level, but the identified system-level evidence is still open or has not been run in this review. |
| **Out of V1 scope** | The design explicitly defers or excludes the capability; do not count it as a V1 defect. |

## Five-stage feature matrix

| Stage and worker boundary | Current implementation evidence | Status and implementation review |
|---|---|---|
| **01 — Read the PDF**: verify the stored Source Document, parse structure, analyze claims, and persist parsed output. | [`AnalysisRunProcessingService`](../api/src/main/kotlin/com/papertrail/api/analysis/service/AnalysisRunProcessingService.kt), [`ClaimAnalysisService`](../api/src/main/kotlin/com/papertrail/api/citation/claims/service/ClaimAnalysisService.kt), [`OpenAiCompatibleClaimAnalysisProvider`](../api/src/main/kotlin/com/papertrail/api/citation/claims/provider/OpenAiCompatibleClaimAnalysisProvider.kt), [`OpenAiCompatibleChatClient`](../api/src/main/kotlin/com/papertrail/api/citation/claims/provider/OpenAiCompatibleChatClient.kt), [`ParsedDocumentRepository`](../api/src/main/kotlin/com/papertrail/api/citation/parsing/ParsedDocumentRepository.kt), [`ClaimCitationPairCounter`](../api/src/main/kotlin/com/papertrail/api/citation/claims/service/ClaimCitationPairCounter.kt), [`ProviderCatalog`](../api/src/main/kotlin/com/papertrail/api/infrastructure/providers/ProviderCatalog.kt), [ADR 0009](./adr/0009-select-citation-targets-per-claim.md), and [ADR 0010](./adr/0010-default-local-claim-analysis-provider-and-preflight.md). | **Implemented with limits.** Local defaults select the server-configured OpenAI-compatible path; heuristic remains selectable. Fresh runs perform a content-free, consent-gated when external, run-pinned availability preflight before source retrieval/parsing, then validate all batches before persistence. No live model endpoint or full deployed upload-to-report smoke was run. |
| **02 — Resolve references**: resolve supported Bibliography Entries to Canonical Papers under the run-pinned policy. | [`ReferenceResolutionService`](../api/src/main/kotlin/com/papertrail/api/scholarly/references/service/ReferenceResolutionService.kt), [`ConservativeReferenceResolver`](../api/src/main/kotlin/com/papertrail/api/scholarly/references/resolver/ConservativeReferenceResolver.kt), Crossref and recorded-fixture metadata adapters under `api/src/main/kotlin/com/papertrail/api/scholarly/references/`. | **Implemented with limits.** Conservative resolution, unresolved outcomes, and run-pinned policy are present. The planned Semantic Scholar enrichment is not implemented. |
| **03 — Acquire cited sources**: discover legal locations, acquire eligible full text, and persist access/language outcomes. | [`CitedPaperAccessService`](../api/src/main/kotlin/com/papertrail/api/scholarly/acquisition/service/CitedPaperAccessService.kt), [`CitedPaperAcquisitionRequestedHandler`](../api/src/main/kotlin/com/papertrail/api/scholarly/acquisition/queue/CitedPaperAcquisitionRequestedHandler.kt), Unpaywall and recorded-fixture adapters under `api/src/main/kotlin/com/papertrail/api/scholarly/acquisition/`. | **Implemented with limits.** Access decisions enforce legal-location and network policies, and record outcomes. Acquisition is limited to legally accessible sources; abstract-only and unsupported-language access do not proceed to semantic verification under V1. |
| **04 — Prepare evidence**: parse eligible Cited Paper Assets, chunk, embed, and retrieve ranked Evidence Passages. | [`CitedPaperIndexingRequestedHandler`](../api/src/main/kotlin/com/papertrail/api/evidence/queue/CitedPaperIndexingRequestedHandler.kt), [`OllamaEmbeddingProvider`](../api/src/main/kotlin/com/papertrail/api/evidence/embedding/OllamaEmbeddingProvider.kt), [`FeatureHashEmbeddingProvider`](../api/src/main/kotlin/com/papertrail/api/evidence/embedding/FeatureHashEmbeddingProvider.kt), [`PostgresHybridEvidenceRetriever`](../api/src/main/kotlin/com/papertrail/api/evidence/retrieval/PostgresHybridEvidenceRetriever.kt), [ADR 0007](./adr/0007-deterministic-local-hybrid-evidence-retrieval.md), [ADR 0008](./adr/0008-prefer-ollama-embedding-selection.md). | **Implemented with limits.** Exact-asset hybrid retrieval, FTS + pgvector, rank fusion, and run-pinned retrieval provenance exist. Local Compose prefers Ollama when selectable; `feature-hash-384-v1` is the lexical fallback, not a trained semantic embedding. Google embeddings are not implemented. Retrieval produces candidate passages, not a semantic judgement. |
| **05 — Assess evidence**: persist System One Evidence Judgements and, when configured, aggregate Claim–Paper Verification outcomes. | [`EvidenceVerificationService`](../api/src/main/kotlin/com/papertrail/api/evidence/verification/service/EvidenceVerificationService.kt), [`LayaSystemOneProvider`](../api/src/main/kotlin/com/papertrail/api/evidence/verification/provider/LayaSystemOneProvider.kt), [`EvidenceAggregationPolicy`](../api/src/main/kotlin/com/papertrail/api/evidence/verification/domain/EvidenceAggregationPolicy.kt), [`AnalysisRunStageCompletionService`](../api/src/main/kotlin/com/papertrail/api/analysis/service/AnalysisRunStageCompletionService.kt), [Laya evaluation status](./laya-evaluation.md). | **Implemented with limits.** Mock and Laya System One paths, typed Evidence Judgements, deterministic aggregation, and incomplete-pair handling exist. Laya judgements and aggregated statuses remain explicitly **uncalibrated**; no accuracy claim follows from the implementation. Verification is conditional on the run configuration. A run without final aggregation may remain `PARSED`, and `PARSED` must not be reported as a completed Evidence Coverage Report. |

Progress is persisted per stage and work item in [`AnalysisRunPipelineProgressRepository`](../api/src/main/kotlin/com/papertrail/api/analysis/service/AnalysisRunPipelineProgressRepository.kt) and displayed through the Analysis Run detail UI in [`pipeline.ts`](../web/features/analysis-runs/pipeline.ts) and [`analysis-run-stage-results.tsx`](../web/features/analysis-runs/components/analysis-run-stage-results.tsx). These five entries are worker stages; the Evidence Coverage Report is a read projection. Human Review is a separate, append-only assessment in [`HumanReviewService`](../api/src/main/kotlin/com/papertrail/api/review/service/HumanReviewService.kt) and does not rewrite machine outcomes ([ADR 0001](./adr/0001-conservative-evidence-triage.md)).

## Where the model integrations are

| Role | Stage | Present in this checkout? | What it does and does not mean |
|---|---:|---|---|
| OpenAI-compatible chat transport → claim-analysis adapter | 01 | **Yes — implemented** | `OpenAiCompatibleChatClient` provides bounded, timed Chat Completions transport and a content-free `/v1/models` availability probe; `OpenAiCompatibleClaimAnalysisProvider` implements the Pipeline 01 contract. Local Spring/Compose defaults select it; deployments can configure another default or disable it. No generic cross-role provider abstraction is planned. |
| Ollama `EmbeddingProvider` | 04 | **Yes** | Produces vectors for retrieval. It is an embedding integration, not claim rewriting or Evidence Judgement. |
| Laya `SystemOneProvider` | 05 | **Yes** | Produces narrow, structured passage-level judgements. Results are uncalibrated and separate from deterministic aggregation. |
| Google / other hosted LLM or embedding providers | 01, 04, or 05 depending on role | **No callable adapter; catalog entries are disabled** | The provider catalog contains disabled Google claim-extraction and embedding options and a disabled Jev System One option. The [provider matrix](./agents/provider-matrix.md) classifies external/unreviewed options and requires exact-product review and per-run consent before use. A catalog entry is not an executable adapter or provider approval. |

The design locations are [Claim Extraction, section 14](./paper-t-rail-tech-design.md#14-claim-extraction), [Shared LLM Transport and Role-Specific Adapters, section 15](./paper-t-rail-tech-design.md#15-shared-llm-transport-and-role-specific-adapters), the [implementation plan](./openai-compatible-provider-plan.md), and [Implementation Plan, phases 4 and 11](./paper-t-rail-tech-design.md#58-implementation-plan). Local configuration selects the implemented provider by default; heuristic remains selectable. This traceability record does not establish live endpoint availability, external deployment approval, or model accuracy.

## Planned capabilities not found in the five-stage runtime

| Capability | Design location | Current state | GitHub issue status | Classification |
|---|---|---|---|---|
| OpenAI-compatible Pipeline 01 claim analysis and target selection | Sections 14–15, phases 4 and 11; [implementation plan](./openai-compatible-provider-plan.md) | Implemented by the shared Chat Completions client and document-level `ClaimAnalysisProvider`; local configuration selects it by default and heuristic remains selectable. | The accepted implementation plan and ADRs 0009–0010 define the behavior. Full suite and availability contract tests cover the code; no live endpoint call, deployment review, or model accuracy evaluation is included. | **Implemented; no live endpoint run** |
| Semantic Scholar enrichment | Phase 5 | Crossref and recorded fixtures are present; no Semantic Scholar adapter found. | #2 documents its provider boundary; no adapter implementation issue found. | **Optional planned extension** |
| Google embeddings | Phases 7 and 11 | Local Ollama and feature-hash implementations are present; no Google embedding adapter found. | No dedicated implementation issue found; provider catalog entry is disabled. | **Optional external-provider extension** |
| Jev System One provider | Phases 9 and 11 | A disabled Jev catalog entry exists in [`ProviderCatalog`](../api/src/main/kotlin/com/papertrail/api/infrastructure/providers/ProviderCatalog.kt), but there is no callable `JevSystemOneProvider` adapter; Laya and mock are the runtime implementations. | #21 implements local Laya through a Jev-compatible `laya-serve` API; #2 documents Jev's boundary. Neither implements hosted Jev as a selectable provider. No dedicated Jev implementation issue found. | **Optional provider extension** |
| OpenAI-compatible Stage 04/05 adapters | Sections 14–16, 18; phases 7 and 11 | The shared Chat Completions transport is implemented for Pipeline 01 only. No OpenAI-compatible embeddings or System One adapter exists; these require role-specific contracts and consent/provenance. Google Gemini's disabled catalog records are not OpenAI-compatible runtime support. | These roles remain outside the accepted Pipeline 01 scope; revisit only with their own reviewed issue/contract. See the [implementation plan](./openai-compatible-provider-plan.md). | **Future extension; out of current implementation scope** |
| Analysis Run comparison and comparison metrics | Phases 11 and sections 47–48 | Provider/runtime evaluation artifacts exist, but no user-facing run-comparison feature was found in this review. | No dedicated implementation issue found. | **Planned product extension** |
| OCR, Indonesian, authentication/multi-user, non-scholarly sources, cross-paper discovery, learned reranking | Section 62 | Not present as V1 capabilities. | Not tracked as V1 work; design explicitly defers these. | **Out of V1 scope** |

The absence of an optional provider is not by itself a defect. Before moving one into V1, capture its user outcome, trust boundary, payload categories, consent behavior, run snapshot/provenance, failure policy, and observable acceptance criteria in an issue and the provider matrix.

## What an OpenAI-compatible integration can cover

“OpenAI-compatible” describes an HTTP/API shape, not a universal Paper T-Rail provider. The agreed first delivery is the Pipeline 01 document-level claim-analysis adapter. Later role-specific adapters may reuse the shared transport where the endpoint operation fits; each must retain its own domain contract:

| Pipeline stage | Paper T-Rail port | Potential compatible operation | Boundary that still needs its own design |
|---|---|---|---|
| 01 Read the PDF | Document-level `ClaimAnalysisProvider` (`HeuristicClaimAnalysisProvider` and optional `OpenAiCompatibleClaimAnalysisProvider`) | Chat Completions for Atomic Claims plus selected same-context Citation Targets | Implemented with exact context coverage, source-span and target-key checks, two-category external consent, run-pinned provider/model/policy, deterministic whole-context batching, and fail-closed behavior. This supplements GROBID parsing; it does not replace GROBID's TEI structure or target identity. |
| 02 Resolve references | `ScholarlyMetadataLookup` | No safe drop-in chat operation | Canonical identity should continue to depend on traceable metadata records and conservative matching. A model suggestion alone must not silently resolve a Bibliography Entry. |
| 03 Acquire cited sources | `OpenAccessProvider` | No safe drop-in chat operation | OA discovery, rights/location decisions, URL/network safety, and acquisition need provider-specific evidence; generated text cannot establish that a copy is legally accessible. |
| 04 Prepare evidence | `EmbeddingProvider` | Embeddings endpoint for cited-paper chunks and claim queries | Separate embedding adapter/profile, dimensions, model fingerprint, consent categories, and retrieval compatibility. This is the most direct use for Stage 04. |
| 05 Assess evidence | `SystemOneProvider` | Chat/text generation only if it can meet the typed judgement contract | A separate adapter must validate the judgement fields, scores, token/context limits, errors, data categories, and uncalibrated labeling. It is not interchangeable with the Pipeline 01 claim-analysis adapter. |

Stage 04 embeddings and Stage 05 System One are possible later adapters, but are explicitly excluded from the first implementation plan. Stages 02–03 and GROBID parsing remain specialized provider boundaries; making them “LLM-enabled” would require a separate, evidence-constrained product decision. Split future implementation issues by port/role, even if they share the same base URL and authentication configuration.

## Open verification and product-spec work

- [Issue #15 — Specify V1 Researcher-Facing Evidence Coverage](https://github.com/arrokh/paper-t-rail/issues/15) remains open as the parent V1 specification.
- [Issue #55 — Cover the full upload-to-report application path](https://github.com/arrokh/paper-t-rail/issues/55) is open. This change adds API queue integration and web component coverage, and the full repository suite passes; it does not launch the web app and exercise a real upload through queued processing to the report in one system-level run.
- [Issue #56 — Verify PDF page-count limit behavior](https://github.com/arrokh/paper-t-rail/issues/56) is open for the configured page-limit boundary test. This is a verification gap, not evidence that the guard itself is absent.
- Issue [#45](https://github.com/arrokh/paper-t-rail/issues/45) closed calibration research as not planned. Calibration is not a product or release gate; keep current outputs labeled uncalibrated.

## Standard for future process and feature tracing

Keep the plan, implementation, and evidence distinct. Use the tech design and ADRs for intended product/architecture behavior; GitHub Issues for actionable acceptance criteria and decisions; code, schema, configuration, API, and UI as implementation evidence; and executed behavioral/system checks as verification evidence. An existing test file is evidence of test coverage in source, not proof that the test passed in the current environment.

Add or update one row for each independently observable feature outcome. Use this record:

| Field | What to record |
|---|---|
| Feature / user outcome | The domain result the user should observe; avoid implementation-only labels. |
| Pipeline stage | One of the five persisted worker stages, or explicitly mark it cross-stage/outside the worker pipeline. |
| Plan and decision | Tech design section, ADR, and issue that establish scope and acceptance. |
| Implementation evidence | Entry point → service/policy/provider → persistence → API/UI paths; include relevant configuration and migration when material. |
| Provider and data boundary | Provider identity, local/external trust boundary, payload categories, consent, retention/deletion constraints. |
| Provenance and failure behavior | Run-pinned versions/configuration, stable outcome/reason codes, retries/abstention, and how incomplete work differs from domain outcomes. |
| Verification evidence | Behavioral or system test path, last executed result/date if actually run, plus known coverage gaps. |
| Status and gap | One status from the vocabulary above, with the specific missing behavior or evidence named. |

On each implementation change, update the issue acceptance status and affected matrix row in the same review. Do not promote a row to **Implemented** because an interface, design sketch, or test fixture exists alone; require the user-visible/persisted behavior and its relevant boundaries to be present. Do not call an item **Verified** unless the relevant check was executed and its result recorded.
