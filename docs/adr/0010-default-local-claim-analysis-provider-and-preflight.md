---
status: accepted
---
# Default New Runs to the Configured Local Claim Analyzer

Local Spring and Compose defaults select the OpenAI-compatible Pipeline 01 claim analyzer at `http://127.0.0.1:1234` (Compose reaches the host through `host.docker.internal`) with model `microsoft/phi-4-mini-reasoning`. The heuristic remains explicitly selectable. The selected provider and model remain pinned to each Analysis Run; availability or inference failure must never trigger a silent provider substitution.

Before a fresh Analysis Run retrieves or parses its Source Document, the worker sends a content-free `GET /v1/models` request through `ProviderCallGate`, discards the response body, and requires a successful HTTP response. Network, timeout, throttling, and server errors follow the queue retry policy; other non-success responses fail the selected provider path. This check verifies endpoint availability only; it makes no claim that a model is accurate or that a deployment is suitable.

The preflight does not send Source Document content. If the endpoint is external, it remains unavailable until the exact deployment is reviewed and its retention disclosure configured; the run must also contain consent for the provider's declared payload categories before either the availability probe or a content-bearing request. Stage 04 embeddings and Stage 05 verification are unchanged.
