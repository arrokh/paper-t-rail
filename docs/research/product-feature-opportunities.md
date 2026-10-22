# Research: Product feature opportunities for Paper T-Rail

**Research date:** 2026-10-22
**Purpose:** Brainstorm user-facing improvements, grounded in the current product and primary external sources. This is not an approved roadmap or implementation issue.

## Recommendation

Start with **cited-paper Evidence Passage page locators**, then build **read-only Analysis Run comparison**. The first makes Paper T-Rail's central evidence trail faster to inspect; the second helps researchers see what changed after revising a draft. Next, consider a separate **correction/retraction notice panel** for resolved Cited Papers, with strong coverage caveats.

## Current product and boundaries

Paper T-Rail already parses citation-backed Atomic Claims, retrieves candidate passages from the exact Cited Paper Asset, reports provenance, and records Human Reviews separately from immutable Analysis Runs. The report is a triage aid; machine judgements and statuses remain uncalibrated.

The code and design documents identify several real gaps:

- [ADR 0013](../adr/0013-use-docling-for-stage-04-cited-paper-parsing.md) says Docling's richer page/layout provenance is not persisted in Evidence Passage records. The current parser requests Markdown and JSON and uses JSON provenance for bibliography metadata candidates, but its normalized evidence text does not retain page locations.
- [The feature matrix](../pipeline-feature-matrix.md) calls user-facing Analysis Run comparison a planned extension with no dedicated issue.
- Stage 04 tables are searchable as Markdown, but preserving table structure and page provenance for review is separate future work ([ADR 0013](../adr/0013-use-docling-for-stage-04-cited-paper-parsing.md)).
- The live GitHub issue list has one open issue, #15. The feature matrix still calls #55 and #56 open, but both are closed; treat those status entries as stale.

Respect the current scope: English text-based Source Documents; legal acquisition; exact asset and provider provenance; explicit per-run consent for external calls; immutable results; no public sharing or unauthenticated access outside a trusted private network. OCR, non-English analysis, multi-user/authentication, cross-paper discovery, grading, and truth certification are explicitly outside V1 ([README](../../README.md), [tech design §62](../paper-t-rail-tech-design.md)).

## Ranked ideas

### 1. Open each Evidence Passage in the exact cited-paper PDF

**Status:** Direct product gap; page/layout provenance is not persisted. **Value:** Very high. **Effort:** Medium–high.

For each Evidence Passage, show its page number and, where reliable, a highlight or bounding box in the exact Cited Paper Asset. Keep the Asset hash and parser version attached to the locator. If mapping is uncertain, show the section or passage text without inventing a page number. A first slice can add page links before attempting precise text highlighting.

The current Paper Review can find the Atomic Claim and citation in the user's Source Document, but cited-paper page provenance is not retained in the evidence result. Current Docling docs describe page numbers and bounding boxes in structured provenance. The deployed parser is pinned to `docling-serve-cpu:v1.30.0`; verify that exact response contract and text-to-page mapping before promising locators. The API already requests both Markdown and JSON, but the existing mapping from Markdown into Evidence Passages does not keep those locations.

**Small acceptance shape:** A researcher opens a Claim–Paper Verification, selects an Evidence Passage, and jumps to its page in the exact cited PDF when the location is known. Missing or ambiguous provenance stays explicitly unavailable. No new provider calls; no status or Analysis Run is changed.

### 2. Compare two immutable Analysis Runs

**Status:** Planned in the technical design, not implemented as a user-facing feature. **Value:** High. **Effort:** Medium.

For runs of the same Source Document, show added, removed, changed, and unchanged claims; changes to Citation Targets, resolution/access, Claim–Paper Verification, Evidence Passages, warnings, Human Reviews, and pinned configuration. Align by stable source identity when possible and show unmatched items rather than forcing a match.

This supports draft iteration while keeping both runs intact. Do not present a newer status as proof the scholarship improved, calculate a paper-quality score, or copy a Human Review onto a different machine result. The feature matrix already records the gap; the technical design describes comparison and possible metrics in §§47–48. No dedicated open issue was found.

### 3. Add a dated correction/retraction notice panel

**Status:** New metadata sidecar. **Value:** High. **Effort:** Medium.

For resolved DOI-backed Cited Papers, show a registry-reported correction, retraction, expression of concern, withdrawal, or reinstatement with source, notice link/DOI, type, and check time. Keep this separate from the Claim–Paper Verification: a notice does not automatically prove that this claim is false.

Crossref's REST API exposes update relationships and Retraction Watch data. Crossref says Retraction Watch updates are refreshed each working day, but corrections and expressions of concern are less comprehensive than retractions. Crossmark participation is incomplete, and absence of a notice is not proof that no update exists. PubMed also exposes linked retraction notices for biomedical records, but is not a general cross-discipline source.

Use wording such as **“No matching update found in the sources checked as of [time]”**, never **“not retracted”** or **“verified current.”** Pin the status-source result and time; require the existing per-run consent/provenance controls for any external lookup. Do not change machine evidence statuses.

### 4. Preserve table context in Evidence Passages

**Status:** Planned future work. **Value:** High for claims grounded in numeric results. **Effort:** High.

Retain table captions, row/column headers, units, and cell relationships so a passage such as “0.42” is not detached from its outcome, population, or unit. Link it to the exact page/table when possible. The current searchable Markdown representation is useful for retrieval but does not provide a structured table review experience. Docling's current document model describes structured table cells and page provenance; verify behavior against the pinned service version and representative PDFs.

Treat table extraction as a candidate-evidence presentation aid, not a semantic verdict. Misread rows, units, or table continuation across pages must remain visible as uncertainty.

### 5. Prototype a focused Evidence Review Mode

**Status:** UI enhancement on existing report data. **Value:** High. **Effort:** Low–medium for a prototype.

The current UI already focuses on a selected Claim–Paper Verification and displays its ranked passages. A small prototype could present the claim, Citation Context, cited reference, status, and first-ranked passage in a compact review view, then reveal the remaining passages in recorded order. Every passage—including partial or contradictory evidence—must remain accessible. Retrieval rank is not evidence strength.

Alt et al. (EACL 2026) report that incremental evidence ranking reduced user reading effort and improved verification compared with showing selected evidence all at once. This is support for testing the interaction, not proof it will help Paper T-Rail users: the study evaluates attribution/fact-verification tasks, not expert review of academic claims against their exact cited full text. Do not change retrieval or aggregation policy based on this UI experiment.

### 6. Export a local, traceable review packet

**Status:** New. **Value:** Medium. **Effort:** Medium.

Allow a deliberate local download (for example HTML plus CSV/JSON) with the selected run's claims, citation links, statuses and definitions, processing warnings, Human Reviews, and enough provider/asset provenance to audit the report. Default to metadata and citations; require an explicit choice before including Source Document-derived claim text, Evidence Passages, or reviewer notes. Include the triage disclaimer and uncalibrated labels. Do not create public share links or send the packet to an external provider.

Zotero and PubMed document common export/handoff workflows, but that establishes workflow precedent—not demand for this particular report. A direct Zotero note integration could be a later option; it needs deliberate credential/write authorization and item matching, so start with a local file.

## Defer or test later

- A worklist for unresolved/inaccessible Cited References could make existing DOI Assistance and recovery actions easier to find. First verify that the current recovery workspace does not already meet this need; never imply that an uploaded PDF proves identity, rights, or evidence support.
- Make work/version lineage more visible only after checking the current resolution and recovery UI. The model already distinguishes Canonical Papers from exact Cited Paper Assets and has version-aware recovery decisions. A future view could show registry-reported preprint/published/update relations beside the selected Asset, but must not silently substitute or merge versions. Crossref relationships are deposited metadata, not a completeness guarantee ([Crossref relationships](https://www.crossref.org/documentation/schema-library/markup-guide-metadata-segments/relationships/)).
- A citation-intent lens (background/method/result comparison) is distinct from the Evidence Role and from support/contradiction. Validate researcher demand before adding another inferred label.
- Later-published supporting/contrasting citation-context signals may be useful leads, but they concern other papers and must not be merged with evidence from the exact Cited Paper. Cross-paper discovery is explicitly outside current scope; any future signal needs a separately approved source, coverage disclosure, consent, and provenance.

## External evidence and caveats

- Alt et al., [“User-Centric Evidence Ranking for Attribution and Fact Verification” (EACL 2026)](https://aclanthology.org/2026.eacl-long.340/) — the abstract reports reduced reading effort and improved verification in a controlled user study. It supports a UI experiment, not Paper T-Rail accuracy or direct transfer to academic full-text review.
- [Docling Document reference](https://docling-project.github.io/docling/reference/docling_document/) — current model docs describe page number/bounding-box provenance and structured tables. They do not by themselves prove the installed `docling-serve` version exposes the exact mapping Paper T-Rail needs.
- [Crossref Retraction Watch documentation](https://www.crossref.org/documentation/retrieve-metadata/retraction-watch/), [REST API filters](https://www.crossref.org/documentation/retrieve-metadata/rest-api/rest-api-filters/), and [Crossmark](https://www.crossref.org/services/crossmark/) — official metadata routes and explicit coverage limitations.
- [PubMed User Guide](https://pubmed.ncbi.nlm.nih.gov/help/) — linked `Retraction in` / `Retraction of` records and query filters for biomedical citations.
- Nicholson et al., [“Scite: A smart citation index…” (Quantitative Science Studies, 2021)](https://pmc.ncbi.nlm.nih.gov/articles/PMC8608186/) — describes supporting/contrasting/mentioning citation signals and cautions that corpus coverage is incomplete and citation classification is not a verdict.
- [Zotero export documentation](https://www.zotero.org/support/kb/exporting) and [PubMed citation-manager export](https://pubmed.ncbi.nlm.nih.gov/help/) — examples of portable reference handoff; not evidence of demand for Paper T-Rail report export.

Crossref's automated source check returned **unclear** for the status-coverage claim; the official Crossref documentation was fetched and manually inspected. External research supports capabilities and caveats, not feature demand. Validate the top ideas with researchers before treating them as roadmap commitments.
