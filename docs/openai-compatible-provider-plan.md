# Implementation Plan: OpenAI-Compatible Claim Analysis

**Status:** Implemented; local Spring and Compose defaults select `openai-compatible-chat` with model `google/gemma-4-e2b` and a 131072-token context window. Fresh runs probe the configured `/v1/models` endpoint before reading or parsing the Source Document. Contract tests use local HTTP fixtures; they do not establish model accuracy. See [implementation and verification status](#implementation-and-verification-status).

## Goal

Add an optional OpenAI-compatible chat provider to Pipeline 01 that processes one Source Document's GROBID-produced citation structure, extracts its Atomic Claims, and selects the Citation Targets associated with each claim. Claim extraction and target selection are one model operation. A shared HTTP/protocol client keeps the integration extensible, while each future pipeline use case must have its own domain adapter and output contract.

## Scope

### Included

- Pipeline 01 only: document-level claim analysis using an OpenAI-compatible Chat Completions endpoint.
- An optional, deployment-configured provider alongside the heuristic provider. Local defaults select OpenAI-compatible claim analysis; heuristic remains explicitly selectable.
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
4. The deployment-configured claim-analysis default is pinned to each Analysis Run. Local defaults select the OpenAI-compatible provider; heuristic remains explicitly selectable and retains its all-target behavior.
5. If all contexts fit, send one chat request for the document; if there are no Citation Contexts, return an empty result without a chat-completion request. The selected provider's content-free availability probe still runs before a fresh Analysis Run reads or parses its source. If contexts do not fit, batch whole Citation Contexts in source order using a deterministic per-deployment size estimator that includes the prompt/schema overhead and reserves the configured response budget. Configure that input budget against the selected model's context window and enforce a serialized request-byte ceiling. Never split, truncate, or omit a Citation Context. If one context cannot fit by itself, fail with a stable reason.
6. Validate and combine every batch before persisting parsed output. A malformed or invalid result fails the selected provider path; it never silently switches to heuristic. Retryable transport failures use worker retries; permanent configuration, consent, and response-contract failures are dead-lettered without redelivery.
7. Pipeline 05 is only a future possible consumer of the shared chat transport, not an implementation deliverable here.

## Implementation outcome

- `AnalysisRunProcessingService` invokes `ClaimAnalysisService` once per parsed document before any parsed rows are persisted. The service selects a run-pinned `ClaimAnalysisProvider` and validates exact context coverage, source-span bounds, same-context target membership, and duplicate/conflicting outputs.
- `HeuristicClaimAnalysisProvider` adapts the existing deterministic extractor and links each extracted claim to every target in its context. `OpenAiCompatibleClaimAnalysisProvider` submits the document's Citation Contexts together and returns claims plus selected target keys in one operation; the provider is exposed only when server-side configuration makes it selectable.
- `OpenAiCompatibleEndpointSettings` owns the shared connection/trust profile; `OpenAiCompatibleChatClient` depends only on that generic profile and accepts the model and completion budget from its caller. It implements JSON-mode non-streaming Chat Completions, server-side bearer authentication, no redirects, explicit timeout, bounded request/response bytes, and sanitized retryable/permanent errors. Structured transport logs report only provider/operation, status or error type, byte counts, retryability, and duration; the claim adapter logs model and batch/result counts, never payload text. The client does not know claim-analysis settings or prompts.
- Whole Citation Contexts are packed deterministically under configured token/byte budgets. A context that cannot fit fails before any provider call; batches are all validated before persistence.
- `ParsedDocumentRepository` maps only validated selected target keys to same-context target IDs. `ClaimCitationPairCounter` counts selected links; claims with no selected target remain persisted and create no Claim–Paper Verification.
- Provider selection, endpoint fingerprint, target-selection policy, prompt/output-mapping versions, trust boundary, and retention disclosure are pinned in the Analysis Run snapshot. External endpoints disclose known or unknown retention and require exact per-run consent for `citation_context` and `bibliographic_metadata`; provider-terms review is not an enablement prerequisite.
- The existing provider directory and upload configuration UI expose the provider, select the configured local default when available, and require its declared consent categories for external endpoints. The UI keeps unlinked claims visible as “No Citation Targets”; they are not converted into unresolved references or verification outcomes.
- Invalid configuration, consent, or response-contract failures are non-retryable and dead-letter immediately. Timeouts, network failures, HTTP 408/429, and 5xx remain retryable. Neither path silently substitutes the heuristic.
- Before retrieving or parsing a fresh run's Source Document, the worker sends a content-free `GET /v1/models` request through the provider-call gate. A 2xx response confirms endpoint availability; the response body is discarded. Network failures, timeouts, 408/429, and 5xx retry; other non-2xx responses fail without switching providers.

## Proposed architecture

```text
AnalysisRunProcessingService
  └─ document-level ClaimAnalysisService
       └─ ClaimAnalysisProvider
            ├─ HeuristicClaimAnalysisProvider
            └─ OpenAiCompatibleClaimAnalysisProvider (claim prompt/schema/model profile)
                 └─ OpenAiCompatibleChatClient (shared HTTP/protocol transport)
                      └─ OpenAiCompatibleEndpointSettings (shared endpoint/trust profile)

Future SystemOneProvider adapter
  └─ OpenAiCompatibleChatClient + its own model/prompt/output profile
```

- Evolve the claim-analysis boundary to accept all structured Citation Context inputs for one Analysis Run and return context-grouped claims plus selected target keys. The exact type names may follow repository conventions, but do not add a separate LLM call for target linking.
- The heuristic adapter maps its existing extraction results to all GROBID target keys in each Citation Context, preserving its established heuristic behavior.
- `OpenAiCompatibleEndpointSettings` owns one deployment-level endpoint profile: base URL, server-side credentials, trust hosts, retention disclosure, request timeout, and byte ceilings. `OpenAiCompatibleChatClient` depends only on this generic profile; its HTTP/protocol implementation is shared by composed adapters, not inherited by them.
- `OpenAiCompatibleClaimAnalysisSettings` owns only the claim-extractor model and context/completion budgets. It accepts any compatible model ID supported by the selected endpoint; the local default is `google/gemma-4-e2b` with a 131072-token context window. `OpenAiCompatibleClaimAnalysisProvider` owns the claim prompt, JSON response mapping, source-span checks, target-key validation, and `ClaimAnalysisProvider` contract.
- Keep the base URL and credentials in server-side endpoint configuration and the model profile under its provider role; do not accept endpoint settings from the browser or store secrets in run snapshots. Only the `claimExtractor` role is registered by this plan. A future System One adapter may compose the same transport while implementing `SystemOneProvider` with an independent model/output contract.

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

Use Chat Completions (`POST /v1/chat/completions`) with JSON mode for the initial compatibility contract. A configured base URL may be the service root (`http://127.0.0.1:1234`) or include `/v1`; the client adds `/v1` only when no API version segment is present. Before a fresh run reads the PDF, issue `GET /v1/models` with no run payload, discard the response body, and require a successful status. Explicitly instruct the model to return a JSON object and validate the complete schema in the application: JSON mode guarantees JSON syntax, not conformance to Paper T-Rail's schema. Do not require provider-specific strict JSON Schema support in the first implementation. Do not use streaming or tools.

## Provider, consent, and provenance

- The shared transport is unavailable unless the deployment endpoint profile is selectable; each role-specific adapter is unavailable unless its model profile is valid. The API key is server-side only.
- Classify the configured endpoint as `LOCAL` only when it is within the trusted deployment boundary; otherwise classify it as `EXTERNAL`. OpenAI compatibility does not determine trust.
- For external endpoints, require HTTPS classification and disclosure of known or unknown retention. The claim request sends Citation Context text and minimum referenced-entry metadata, so it declares `citation_context` and `bibliographic_metadata` and requires exact per-provider, per-run consent for both before the request and availability probe.
- Route outbound requests through `ProviderCallGate`. Extend the claim-extraction path so external calls cannot bypass provider selection, trust classification, configuration-fingerprint checks, payload-category checks, or consent.
- Pin the selected provider ID/version/model, target-selection policy version, prompt/output-mapping version, trust boundary, and non-secret endpoint fingerprint in the run snapshot. Do not persist API keys or log contexts, bibliography payloads, prompts, or model responses.
- Keep the provider configurable: local defaults select the OpenAI-compatible analyzer, while heuristic remains an explicit selectable option. An unavailable configured provider fails closed and never silently falls back.

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
- Integrate analysis and content-free availability probes with `ProviderCallGate`; update `RunConfigurationFactory` and run snapshot pinning. Keep heuristic explicitly selectable.
- Persist only the returned target links; retain `INFERRED_PROVISIONAL`. Update `ClaimCitationPairCounter` and expected-pair initialization to use those links.
- Confirm current composite database constraints and report projections handle zero/subset links. No migration is expected if the existing same-context constraints remain sufficient; add one only if verification shows the schema cannot represent the accepted behavior.
- Ensure unlinked claims remain visible as “No Citation Targets” and are not emitted as unresolved-reference or Claim–Paper Verification outcomes.
- Verify that the existing provider configuration UI presents the API directory option and collects its declared external categories. Only change public API/OpenAPI schemas if implementation changes their shape.

**Done when:** an Analysis Run pins the selected analyzer and policy; an external request is impossible without exact consent; only selected links generate downstream pairs; no provider failure silently changes the pinned analyzer; historical runs remain unchanged.

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
- External consent does not establish provider suitability or retract already transmitted content. Disclose known or unknown provider retention terms; terms review is not an enablement prerequisite.
- This plan does not add a Stage 05 implementation or enable final evidence aggregation for a new model.

## Implementation and verification status

| Outcome | Evidence |
|---|---|
| Document-level provider seam, heuristic compatibility, selected/empty target links, and pre-persistence validation | `ClaimAnalysisServiceTest`, `HeuristicClaimAnalysisProvider`, and `AnalysisRunQueueIntegrationTest` |
| Generic endpoint trust/URL/transport settings, shared Chat Completions protocol client, and role-specific model/budget profile | `OpenAiCompatibleEndpointSettingsTest`, `OpenAiCompatibleClaimAnalysisSettingsTest`, and `OpenAiCompatibleClaimAnalysisProviderContractTest` |
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
- [ADR 0010: configured local default and availability preflight](./adr/0010-default-local-claim-analysis-provider-and-preflight.md)
- [ADR 0011: shared OpenAI-compatible transport](./adr/0011-shared-openai-compatible-chat-transport.md)
- [ADR 0012: selected claim-to-target associations](./adr/0012-select-citation-targets-per-claim.md)
- [OpenAI Chat Completions API reference](https://platform.openai.com/docs/api-reference/chat/create)
- [OpenAI structured model outputs](https://developers.openai.com/api/docs/guides/structured-outputs)
