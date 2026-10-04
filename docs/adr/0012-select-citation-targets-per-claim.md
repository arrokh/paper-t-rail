---
status: accepted
---
# Select Citation Targets Per Atomic Claim

GROBID defines the available Citation Targets and their Citation Contexts, but associating every Atomic Claim with every target in a context can create irrelevant Claim–Paper Verifications. An Analysis Run may therefore associate a claim only with a subset—including none—of the GROBID-provided targets in that same Citation Context; the association remains inferred/provisional, never author-confirmed. The current heuristic continues to select all targets in its context, while the optional document-level LLM claim analyzer may select a narrower subset. Pin the selected provider and target-selection policy to the Analysis Run, reject invented or cross-context targets, and do not fall back to all targets when the model returns an empty selection. This supersedes only the all-target association rule in [ADR 0004](0004-run-scoped-parsed-document-structure.md); run-scoped parsing, source spans, immutability, and context-boundary constraints remain in force.
