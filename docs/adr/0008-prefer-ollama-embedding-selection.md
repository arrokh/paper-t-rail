# Prefer Ollama Embeddings for New Analysis Runs

**Status:** Superseded for the new-run model choice by [ADR 0017](0017-embeddinggemma-for-new-runs.md). The feature-hash fallback, external-endpoint consent, and immutable run-snapshot principles remain active.

At adoption, local Compose started Ollama with `nomic-embed-text:v1.5`, so new-run selection preferred Ollama when selectable; feature-hash remained the safe fallback. External Ollama endpoints required per-run consent, and each run pinned its selected provider and embedding profile. This decision superseded the default-selection portion of [ADR 0007](0007-deterministic-local-hybrid-evidence-retrieval.md), not its deterministic retrieval design.
