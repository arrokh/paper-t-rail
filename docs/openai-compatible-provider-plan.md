# Implementation Plan: OpenAI-Compatible Claim Analysis

**Status:** Implemented; local verification passed. The adapter is opt-in and disabled by default; no live external endpoint or model-accuracy claim is included. See [implementation and verification status](#implementation-and-verification-status).

## Goal

Add an optional OpenAI-compatible chat provider to Pipeline 01 that processes one Source Document's GROBID-produced citation structure, extracts its Atomic Claims, and selects the Citation Targets associated with each claim. Claim extraction and target selection are one model operation. A shared HTTP/protocol client keeps the integration extensible, while each future pipeline use case must have its own domain adapter and output contract.

## Scope

### Included

- Pipeline 01 only: document-level claim analysis using an OpenAI-compatible Chat Completions endpoint.
- An optional provider alongside the existing heuristic provider; heuristic remains the default.
- One model response containing claims, source spans, and selected target keys for all submitted Citation Contexts.
- One request when the document input fits; deterministic batching at Citation Context boundaries when it does not.
- Local and externally hosted compatible endpoints, classified by their actual configured trust boundary.
- Per-run provider/model/provenance, external-provider consent, and clear failures without silent provider substitution.
- Update the accepted domain decision, plan, and implementation traceability.

### Not included

- A Stage 05 System One adapter or changes to evidence judgement or aggregation.
- A Stage 04 embeddings adapter. Embeddings use a distinct API operation and would need a separate adapter.
- LLM-based scholarly identity resolution, Open Access discovery, legal-access decisions, or replacement of GROBID parsing/citation identity.
- Sending raw GROBID TEI, the full Source Document body, or non-citation-bearing document text to the model.
- A new user-configurable endpoint URL or API secret in the web app.

## Agreed behavior

1. A document-level claim-analysis operation receives the GROBID-produced Citation Contexts, Citation Occurrences, allowed target keys, and only the bibliography metadata needed for target selection. The raw TEI and full document body are not sent.
2. The model returns Atomic Claims and each claim's selected target keys together. Target selection is limited to GROBID-provided targets in that claim's own Citation Context. The model cannot invent targets or link across contexts.
3. Associations remain inferred/provisional, not author-confirmed. A selected subset—including an empty set—is allowed. Empty selection leaves the claim persisted without a Claim–Paper Verification; it does not fall back to all context targets.
4. The heuristic provider remains the default and retains its current all-target behavior. Selecting the LLM provider is explicit and pinned to that Analysis Run.
5. If all contexts fit, send one chat request for the document; if there are no Citation Contexts, return an empty result without an outbound model call. If they do not fit, batch whole Citation Contexts in source order using a deterministic per-deployment size estimator that includes the prompt/schema overhead and reserves the configured response budget. Configure that input budget against the selected model's context window and enforce a serialized request-byte ceiling. Never split, truncate, or omit a Citation Context. If one context cannot fit by itself, fail with a stable reason.
6. Validate and combine every batch before persisting parsed output. A malformed or invalid result fails the selected provider path; it never silently switches to heuristic. Retryable transport failures use worker retries; permanent configuration, consent, and response-contract failures are dead-lettered without redelivery.
7. Pipeline 05 is only a future possible consumer of the shared chat transport, not an implementation deliverable here.

## Implementation outcome

- `AnalysisRunProcessingService` invokes `ClaimAnalysisService` once per parsed document before any parsed rows are persisted. The service selects a run-pinned `ClaimAnalysisProvider` and validates exact context coverage, source-span bounds, same-context target membership, and duplicate/conflicting outputs.
- `HeuristicClaimAnalysisProvider` adapts the existing deterministic extractor and links each extracted claim to every target in its context. `OpenAiCompatibleClaimAnalysisProvider` submits the document's Citation Contexts together and returns claims plus selected target keys in one operation; the optional implementation is exposed only when server-side configuration makes it selectable.
- `OpenAiCompatibleChatClient` uses JSON-mode non-streaming Chat Completions, server-side bearer authentication, no redirects, explicit total-request timeout, bounded response bytes, and sanitized retryable/permanent errors. It rechecks that endpoint settings are selectable before network access and sends no raw TEI or non-citation Source Document text. The prompt treats source fields as untrusted data rather than instructions, preserves meaning-bearing qualifiers, and prohibits unsupported implications.
- Whole Citation Contexts are packed deterministically under configured token/byte budgets. A context that cannot fit fails before any provider call; batches are all validated before persistence.
- `ParsedDocumentRepository` maps only validated selected target keys to same-context target IDs. `ClaimCitationPairCounter` counts selected links; claims with no selected target remain persisted and create no Claim–Paper Verification.
- Provider selection, endpoint fingerprint, target-selection policy, prompt/output-mapping versions, trust boundary, and retention disclosure are pinned in the Analysis Run snapshot. External endpoints require reviewed enablement, an explicit retention disclosure, and exact per-run consent for `citation_context` and `bibliographic_metadata`.
- The existing provider directory and upload configuration UI expose the opt-in provider and require its declared consent categories. The UI keeps unlinked claims visible as “No Citation Targets”; they are not converted into unresolved references or verification outcomes.
- Invalid configuration, consent, or response-contract failures are non-retryable and dead-letter immediately. Timeouts, network failures, HTTP 408/429, and 5xx remain retryable. Neither path silently substitutes the heuristic.

## Proposed architecture

```text
AnalysisRunProcessingService
  └─ document-level ClaimAnalysisService
       └─ ClaimAnalysisProvider
            ├─ HeuristicClaimAnalysisProvider
            └─ OpenAiCompatibleClaimAnalysisProvider
                 └─ OpenAiCompatibleChatClient (HTTP/protocol only)
```

- Evolve the claim-analysis boundary to accept all structured Citation Context inputs for one Analysis Run and return context-grouped claims plus selected target keys. The exact type names may follow repository conventions, but do not add a separate LLM call for target linking.
- The heuristic adapter maps its existing extraction results to all GROBID target keys in each Citation Context, preserving current default behavior.
- `OpenAiCompatibleChatClient` owns endpoint transport, server-side authentication, bounded response reading, timeouts, and Chat Completions request/response DTOs. It must not own claim prompts, citation semantics, persistence, or Evidence Judgement mapping.
- `OpenAiCompatibleClaimAnalysisProvider` owns the claim-analysis prompt, JSON response mapping, source-span checks, and target-key validation. A future System One provider may reuse the chat transport while implementing `SystemOneProvider`; it will need its own contract and is outside this plan.
- Configure one endpoint profile per deployment, with a model ID per provider role. Keep the base URL and credential in server-side configuration; do not accept them from the browser or store secrets in run snapshots. Only the `claimExtractor` role is registered by this plan.

## Document request and response contract

The request contains every normalized Citation Context and its absolute source offsets, its Citation Occurrences, each GROBID-derived Citation Target, and minimum parsed bibliography fields (for example title, authors, year, and DOI) for the referenced entries. A request key identifies one exact Citation Target by its context-local occurrence ordinal and GROBID local bibliography key; persistence maps that key to the generated Citation Target ID. The request contains no raw TEI and no unrelated Source Document text.

The JSON response should identify each input context and return its claims, for example:

```json
{
  "contexts": [
    {
      "contextStartOffset": 120,
      "contextEndOffset": 184,
      "claims": [
        {
          "text": "The extracted proposition with its material qualifiers",
          "sourceStartOffset": 135,
          "sourceEndOffset": 163,
          "citationTargetKeys": ["occurrence-0:ref12"]
        }
      ]
    }
  ]
}
```

Contract rules:

- Return every requested Citation Context exactly once, including an empty `claims` list when no Atomic Claim is extracted.
- Source spans are zero-based, end-exclusive UTF-16 offsets into normalized Source Document text, consistent with existing code. Each span must be non-empty and contained by its Citation Context; preserve qualifiers that affect claim meaning. Claim text may include a shared subject/qualifier and therefore need not equal the span substring.
- Every target key must identify an exact GROBID-derived Citation Target in that context, using the context-local occurrence ordinal plus its local bibliography key. This distinguishes repeated markers that point to the same Bibliography Entry. Reject unknown, duplicate, or cross-context target keys. An empty target list is valid.
- Reject missing/duplicate contexts, blank claims, malformed JSON, invalid spans, and invalid targets before any parsed document rows are persisted.
- Count expected claim-citation pairs from the selected target links, not from all GROBID targets. Enforce the existing configured pair limit before persistence.

Use Chat Completions (`POST /v1/chat/completions`) with JSON mode for the initial compatibility contract. Explicitly instruct the model to return a JSON object and validate the complete schema in the application: JSON mode guarantees JSON syntax, not conformance to Paper T-Rail's schema. Do not require provider-specific strict JSON Schema support in the first implementation. Do not use streaming or tools.

## Provider, consent, and provenance

- The provider is unavailable unless the deployment has configured a valid base URL, model, and credentials when required. The API key is server-side only.
- Classify the configured endpoint as `LOCAL` only when it is within the trusted deployment boundary; otherwise classify it as `EXTERNAL`. OpenAI compatibility does not determine trust.
- For external endpoints, require reviewed enablement and a deployment-specific retention disclosure. The request sends Citation Context text and minimum referenced-entry metadata, so it declares `citation_context` and `bibliographic_metadata` and requires exact per-provider, per-run consent for both before sending.
- Route outbound requests through `ProviderCallGate`. Extend the claim-extraction path so external calls cannot bypass provider selection, trust classification, configuration-fingerprint checks, payload-category checks, or consent.
- Pin the selected provider ID/version/model, target-selection policy version, prompt/output-mapping version, trust boundary, and non-secret endpoint fingerprint in the run snapshot. Do not persist API keys or log contexts, bibliography payloads, prompts, or model responses.
- Keep provider defaults safe: heuristic remains selected by default; a configured LLM appears as an explicit selectable option. An unavailable explicitly selected LLM fails closed.

## Implementation slices

### 1. Establish the domain and result contract

- Add behavior tests for document-level input/output, context isolation, exact target-key identity across repeated markers, selected-target subsets, empty target selection, source-span bounds, and pair counting.
- Introduce the context-grouped claim-analysis result and adapt the heuristic provider without changing its behavior.
- Add a run-pinned target-selection policy version and preserve immutable results for existing Analysis Runs.
- Record the domain-policy change in the accepted ADR and keep `CONTEXT.md` terminology aligned.

**Done when:** existing heuristic runs still associate claims with all targets in their context; the new result contract can represent a subset or no targets; invalid cross-context links are rejected.

### 2. Add the shared compatible chat transport

- Implement the server-side Chat Completions client with configured endpoint/model, authentication, timeout, bounded response size, no redirects, and safe error mapping.
- Keep endpoint configuration out of request payloads and UI; fingerprint non-secret settings for run provenance.
- Add HTTP contract tests using a local stub server for request shape, JSON mode, authentication, timeout, oversized/malformed responses, and status errors.

**Done when:** transport tests prove the client sends only the intended structured request and never logs content or exposes credentials.

### 3. Add the document-level LLM claim analyzer

- Implement one structured request for all contexts when within the configured budget; otherwise pack complete contexts into deterministic batches.
- Fail a single over-budget Citation Context without splitting or truncating it. Combine and validate all batch results before returning any persisted result.
- Implement the prompt and JSON mapper; validate spans, context coverage, and candidate target membership. Instruct the model to preserve meaning-bearing qualifiers and inspect that behavior with the reviewed fixtures.
- Make permanent output-contract failures explicit. Use the existing worker retry policy only for retryable transport failures; never fall back to heuristic.

**Done when:** deterministic provider fixtures demonstrate one document-level result, batching without cross-context target leakage, and explicit failure for invalid/oversized input.

### 4. Wire selection, consent, persistence, and reporting

- Register the adapter in `ProviderCatalog` with exact role, categories, trust boundary, model/version, and disclosure configuration.
- Integrate the claim-analysis path with `ProviderCallGate`; update `RunConfigurationFactory` and run snapshot pinning while preserving `heuristic` as the default.
- Persist only the returned target links; retain `INFERRED_PROVISIONAL`. Update `ClaimCitationPairCounter` and expected-pair initialization to use those links.
- Confirm current composite database constraints and report projections handle zero/subset links. No migration is expected if the existing same-context constraints remain sufficient; add one only if verification shows the schema cannot represent the accepted behavior.
- Ensure unlinked claims remain visible as “No Citation Targets” and are not emitted as unresolved-reference or Claim–Paper Verification outcomes.
- Verify that the existing provider configuration UI presents the API directory option and collects its declared external categories. Only change public API/OpenAPI schemas if implementation changes their shape.

**Done when:** an Analysis Run pins the selected analyzer and policy; an external request is impossible without exact consent; only selected links generate downstream pairs; heuristic defaults and historical runs are unchanged.

### 5. End-to-end verification and docs

- Add queue integration coverage for a document with multiple Citation Contexts, multiple target candidates, selected subsets, and an unlinked claim.
- Add a small human-reviewed fixture set comparing expected claim decomposition and target selection. Use it as review evidence, not as a calibrated accuracy claim or numeric release threshold.
- Update this plan's linked feature-traceability entry when implementation begins and again when behavioral checks have actually passed. Keep planned, implemented, and verified status distinct.

**Done when:** tests and the fixture review show source-traceable claims, context-bounded targets, correct downstream pair counts, and visible unlinked claims; no accuracy/calibration claim is made.

## Non-goals and risks

- A shared transport is not a universal LLM abstraction. Future consumers must implement their existing domain ports and validate their own outputs.
- One document request can be large; context-boundary batching bounds the request without allowing claims or targets to cross Citation Contexts. A single oversized context fails rather than being silently split.
- Model-selected links can omit a relevant Citation Target. Keep links visibly inferred/provisional and retain the heuristic provider as an explicit alternative.
- A generic compatible endpoint may differ in optional features. Contract tests must target the configured compatibility baseline and reject unsupported or malformed responses rather than guessing.
- External consent does not establish provider suitability or retract already transmitted content. Deployment-specific terms and retention disclosures remain required.
- This plan does not add a Stage 05 implementation or enable final evidence aggregation for a new model.

## Implementation and verification status

| Outcome | Evidence |
|---|---|
| Document-level provider seam, heuristic compatibility, selected/empty target links, and pre-persistence validation | `ClaimAnalysisServiceTest`, `HeuristicClaimAnalysisProvider`, and `AnalysisRunQueueIntegrationTest` |
| Configured Chat Completions transport, request/response limits, batching, authentication, consent, and failure classification | `OpenAiCompatibleClaimAnalysisProviderContractTest` and `OpenAiCompatibleClaimAnalysisSettingsTest` |
| Run-pinned provider selection and non-secret endpoint fingerprint | `RunConfigurationFactoryTest` |
| Selected-link persistence and no verification for an unlinked claim; immediate dead-letter for permanent response-contract failure | `AnalysisRunQueueIntegrationTest` |
| Hand-authored deterministic review fixtures for claim decomposition, selected/empty targets, malformed responses, and context isolation | `ClaimAnalysisServiceTest`, `OpenAiCompatibleClaimAnalysisProviderContractTest`, and `AnalysisRunQueueIntegrationTest`; these verify the contract, not live model accuracy or calibration |
| Full API/web checks, Compose validation, link checks, and final diff audit | `mise exec -- make test`, Compose config validation, local Markdown link-target check, and diff audit passed after the last implementation edit. No live external endpoint was called; local HTTP fixtures verify transport contracts only. |

## References

- [Pipeline implementation and feature traceability matrix](./pipeline-feature-matrix.md)
- [Claim Extraction, tech design §14](./paper-t-rail-tech-design.md#14-claim-extraction)
- [Shared LLM Transport and Role-Specific Adapters, tech design §15](./paper-t-rail-tech-design.md#15-shared-llm-transport-and-role-specific-adapters)
- [ADR 0002: version-pinned analysis provenance](./adr/0002-version-pinned-analysis-provenance.md)
- [ADR 0003: provider consent and deletion](./adr/0003-explicit-provider-consent-and-data-retention.md)
- [ADR 0004: run-scoped parsed structure](./adr/0004-run-scoped-parsed-document-structure.md)
- [ADR 0009: selected claim-to-target associations](./adr/0009-select-citation-targets-per-claim.md)
- [OpenAI Chat Completions API reference](https://platform.openai.com/docs/api-reference/chat/create)
- [OpenAI structured model outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
