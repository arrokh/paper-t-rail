# Use Docling for Stage 04 Cited Paper PDF Parsing

**Status:** Accepted

Use the self-hosted Docling service to parse newly acquired Cited Paper PDFs in Stage 04 (Prepare evidence). Keep GROBID unchanged for Stage 01 (Read the PDF), where its TEI citation callout/reference links feed Citation Occurrences, Citation Contexts, Bibliography Entries, and inferred claim-to-target links. Plain-text Cited Papers continue to use the existing `plain-text` parser.

Stage 04 pins its Cited Paper parser identity and version separately from the Source Document parser in each new Analysis Run. The default is `docling` version `1.30.0`, aligned with the pinned Docling Serve CPU image. Docling Serve remains on the private Compose network; the API requests Markdown from its file-conversion endpoint, maps Markdown headings, paragraphs, lists, and tables into normalized sections, and rejects partial/failed conversions. Parser failure must not silently fall back to GROBID or another parser.

`cited_paper_parses` persists the exact Cited Paper asset hash, parser identity/version, and normalized extracted text. Existing Analysis Runs created before this decision do not have a separate Stage 04 parser selection; for those legacy snapshots, use their pinned `sourceParser` selection so pending GROBID indexing work remains retryable. This fallback applies only when the new Stage 04 field is absent; newly created runs explicitly pin Docling.

For newly processed PDF assets, Stage 04 also maps Docling JSON text provenance to normalized Markdown only when a whitespace-normalized text item has one unique match. Multi-page text items use the parser's zero-based character spans when present; unmappable or ambiguous spans remain unavailable. The resulting one-based physical PDF pages are persisted per Evidence Passage chunk under its exact Cited Paper parse, asset hash, and parser identity/version. Paper Review can open that same run-scoped stored asset at the first recorded page; historical runs are not reparsed or backfilled. The API access response is short-lived and no-store.

Page-coordinate highlighting and structured table retrieval remain separate future work. Keep Docling container/model versions pinned, enforce response and extracted-character limits, and compare representative extraction/retrieval quality and runtime costs before upgrading the parser version.
