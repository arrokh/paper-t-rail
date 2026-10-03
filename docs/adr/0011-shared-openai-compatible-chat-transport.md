---
status: accepted
---
# Share the OpenAI-Compatible Transport Across Use-Case Adapters

Treat OpenAI-compatible Chat Completions as a shared transport/provider capability, not as a claim-analysis implementation. Keep its endpoint, credentials, trust classification, retention review, request timeout, and byte limits in one server-side connection profile. Keep the selected model and generation/context budgets in each use-case profile.

Each use case composes the shared chat client through its existing domain port and owns its own prompt, input/output contract, validation, payload categories, and run provenance. The claim-analysis adapter is the first such adapter; a future System One adapter may use the same transport but must implement `SystemOneProvider` and its independent contract. Prefer composition; do not create an inheritance-based universal LLM framework or move domain semantics into the transport.

The generic provider identifier remains `openai-compatible-chat`, while `ProviderCatalog` registers it separately for each supported role. A run pins the role-specific model and configuration fingerprint, and each adapter keeps its domain-specific consent categories and validation. Preserve the existing claim-analysis v1 fingerprint formula while extracting shared endpoint settings so already queued runs remain processable when their configuration is unchanged; future roles can version their own fingerprints. Local defaults continue using the explicitly selected `microsoft/phi-4-mini-reasoning` claim-analysis profile; other OpenAI-compatible model IDs (for example `google/gemma-4-e2b`) are configuration values, not separate provider implementations or claims of compatibility/accuracy.
