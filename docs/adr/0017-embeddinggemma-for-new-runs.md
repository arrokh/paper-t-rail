# Use EmbeddingGemma 2 for New Analysis Runs

**Status:** Accepted

New Analysis Runs use Ollama `embeddinggemma-2:270m` when trusted and available; the new-run provider catalog must not offer Nomic or label another configured model as EmbeddingGemma 2. If the configured Ollama model is not EmbeddingGemma 2, leave the provider unavailable for new runs and use the existing feature-hash fallback. Continue to process existing runs pinned to provider `ollama` and model `nomic-embed-text:v1.5` through a hidden compatibility registration.

Give EmbeddingGemma 2 a distinct provider ID (`ollama-embeddinggemma-2`). Although both models produce 768-dimensional vectors, their embedding spaces are not interchangeable. A distinct ID ensures new snapshots, profile hashes, and persisted vectors cannot be mistaken for Nomic vectors. Existing snapshots remain immutable and require no data migration.

Local Compose pulls only EmbeddingGemma 2. New-run UI and API selection expose only EmbeddingGemma 2 among Ollama choices. The feature-hash fallback remains available when Ollama is not selectable. The hidden Nomic compatibility provider remains wired for historical snapshots, but a retry/reprocessing call works only if Nomic is already in the Ollama data volume; operators can pull `nomic-embed-text:v1.5` manually when needed. Existing run snapshots and stored results remain readable without that model. External Ollama endpoints retain the existing explicit per-run consent behavior.

This changes the preferred model; it does not claim EmbeddingGemma 2 improves retrieval quality. Benchmark retrieval quality and latency before making such a claim. The compatibility path can be removed only after no retained Analysis Runs require the Nomic provider/model profile.
