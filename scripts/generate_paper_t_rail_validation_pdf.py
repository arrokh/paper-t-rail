#!/usr/bin/env python3
"""Generate the Paper T-Rail V1 validation workbook (PDF + editable Markdown).

Uses only the Python standard library so the QA artifact can be rebuilt offline.
"""

from __future__ import annotations

from pathlib import Path
import textwrap
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
OUTPUT_DIR = ROOT / "docs" / "qa"
PDF_PATH = OUTPUT_DIR / "paper-t-rail-validation-checklist.pdf"
MARKDOWN_PATH = OUTPUT_DIR / "paper-t-rail-validation-checklist.md"

PAGE_WIDTH = 612
PAGE_HEIGHT = 792
LEFT = 48
RIGHT = 48
CONTENT_WIDTH = PAGE_WIDTH - LEFT - RIGHT
CONTENT_TOP = 706
CONTENT_BOTTOM = 92

PAGES = [
    {
        "title": "V1 Validation Workbook",
        "subtitle": "A practical test plan for Paper T-Rail | Repository snapshot: 2026-10-04",
        "footer": "The claim oracle on each page states the expected system behavior. It is not a certification of a research claim.",
        "blocks": [
            {"kind": "notice", "label": "PURPOSE", "text": "Use this workbook to validate the documented V1 user journey and its edge cases: upload an academic PDF, follow its Analysis Run, inspect citation-backed Atomic Claims and Claim-Paper Verifications, record a separate Human Review, re-analyze, and delete."},
            {"kind": "section", "text": "How to run the checks"},
            {"kind": "body", "text": "Run the end-to-end path first with recorded/local providers. For deterministic semantic outcomes, use a controlled provider fixture in an integration test. Live Laya output is explicitly uncalibrated; do not require it to match a gold status on each run."},
            {"kind": "body", "text": "For every case, mark PASS, FAIL, BLOCKED, or N/A. Record the build/commit, environment, Analysis Run ID (when one exists), case ID, expected and observed behavior, and a safe evidence link. Do not copy Source Document, claim, or Evidence Passage text into logs or issue comments."},
            {"kind": "section", "text": "V1 workflow and coverage"},
            {"kind": "body", "text": "01 Read the PDF -> 02 Resolve references -> 03 Acquire cited sources -> 04 Prepare evidence -> 05 Assess evidence -> Evidence Coverage Report. Human Review is a separate, append-only action; it does not rewrite machine results."},
            {"kind": "notice", "label": "CLAIM EXPECTATION FOOTER", "text": "Every page footer summarizes the expected claim/citation/result behavior for that page. Case-level expectations below are the detailed oracle. A Claim-Paper Verification is an inferred triage result for one Atomic Claim x Cited Reference, not proof that the claim is true."},
            {"kind": "section", "text": "Keep these distinctions"},
            {"kind": "body", "text": "Passage judgements (DIRECT_SUPPORT, PARTIAL_SUPPORT, CONTRADICTS, UNRELATED, INSUFFICIENT) are not final statuses. Final statuses are SUPPORTED, PARTIALLY_SUPPORTED, CONTRADICTED, INSUFFICIENT_EVIDENCE, INACCESSIBLE, UNRESOLVED, and UNSUPPORTED_REFERENCE_TYPE."},
            {"kind": "body", "text": "Processing failure is not INSUFFICIENT_EVIDENCE. PARSED is an intermediate state, not a completed Evidence Coverage Report. Any Laya judgement or aggregated status must remain labeled uncalibrated."},
            {"kind": "body", "text": "V1 scope: English, text-based academic Source Documents; citation-backed claims; scholarly cited works; legally accessible evidence. OCR, scanned Source Documents, non-English Source Documents, paywall bypass, and truth certification are not supported."},
        ],
    },
    {
        "title": "01 | Upload and Source Document",
        "subtitle": "Validate PDF preflight, clear failure behavior, and safe limits.",
        "footer": "CLAIM ORACLE: valid input creates a run; invalid input is rejected clearly; limits fail closed and never silently truncate.",
        "blocks": [
            {"kind": "case", "id": "U-01", "title": "Supported academic PDF", "action": "Upload an English, text-based journal article or thesis with selectable text, body citations, and a bibliography.", "expected": "The Source Document is accepted; a new Analysis Run starts. The run pins the source hash, page count, validation/parser provenance, and configuration."},
            {"kind": "case", "id": "U-02", "title": "Empty, wrong type, wrong filename, or invalid signature", "action": "Try a zero-byte file, a non-PDF MIME type, a filename without .pdf, and non-PDF bytes renamed .pdf.", "expected": "Each request is rejected with a clear validation error (for example EMPTY_UPLOAD, UNSUPPORTED_CONTENT_TYPE, PDF_FILENAME_REQUIRED, INVALID_PDF_SIGNATURE). No partial document or run is created."},
            {"kind": "case", "id": "U-03", "title": "Corrupt or encrypted PDF", "action": "Upload a damaged PDF and a password-protected PDF.", "expected": "Reject with a clear parse/password error; do not queue a run or retain partial parse output."},
            {"kind": "case", "id": "U-04", "title": "Scanned pages and insufficient text", "action": "Compare a scan-only PDF with a text PDF below the configured minimum extracted-text threshold.", "expected": "The scan is rejected as SCANNED_PDF_UNSUPPORTED (OCR is out of scope). Other low-text PDFs fail with PDF_HAS_INSUFFICIENT_TEXT."},
            {"kind": "case", "id": "U-05", "title": "Unsupported or uncertain source language", "action": "Upload a non-English document and an English-looking document whose language confidence is below the configured minimum.", "expected": "Both are rejected with UNSUPPORTED_LANGUAGE. No Analysis Run is queued."},
            {"kind": "case", "id": "U-06", "title": "Byte, page, and extracted-text limits", "action": "Check exactly-at and one-over boundaries for the configured byte, page, total-character, and per-page-character limits. Defaults: 50 MiB, 500 pages, 5,000,000 total chars, 100,000 chars/page.", "expected": "At-limit inputs are accepted when otherwise valid. Over-limit inputs fail with the corresponding error (UPLOAD_TOO_LARGE, PDF_TOO_MANY_PAGES, PDF_TEXT_TOO_LARGE); no content is truncated."},
            {"kind": "case", "id": "U-07", "title": "Claim-Citation pair cap", "action": "Exercise exactly 5,000 claim-to-target links, then 5,001 (or use a lower test configuration).", "expected": "The exact cap succeeds. Above the cap, the run fails with CLAIM_CITATION_PAIR_LIMIT_EXCEEDED before parsed output is persisted; there is no partial report."},
            {"kind": "case", "id": "U-08", "title": "PDF geometry and text fidelity", "action": "Use a multi-page PDF with columns, ligatures, Unicode punctuation, repeated headers/footers, and citations near line/page breaks. Open each extracted claim with Show in PDF.", "expected": "Displayed claim text and citation marker point to the correct source span/page. Page geometry does not shift the highlight to neighboring citations."},
        ],
    },
    {
        "title": "02 | Citation Contexts and Atomic Claims",
        "subtitle": "Check extracted claim text, source spans, context boundaries, and inferred links.",
        "footer": "CLAIM ORACLE: C-02 splits into two with qualifiers; ambiguous negation/scope stays together; targets never cross Citation Contexts.",
        "blocks": [
            {"kind": "case", "id": "C-01", "title": "Single citation-backed proposition", "action": "Use: 'The intervention improved mobility [1].' Inspect the claim and source offsets.", "expected": "One Atomic Claim is extracted; the marker is not part of its claim text. Its source span maps back to the exact proposition."},
            {"kind": "case", "id": "C-02", "title": "Independent coordinated predicates with a shared qualifier", "action": "Use: 'Among older adults, treatment reduced pain and improved mobility [1, 2].'", "expected": "Two Atomic Claims are extracted. Both retain 'Among older adults'; each claim links to both targets in this context. The links are labeled inferred/provisional."},
            {"kind": "case", "id": "C-03", "title": "Ambiguous negation", "action": "Use: 'Treatment did not improve symptoms and reduce dropout [1].'", "expected": "Keep one conservative claim because splitting could change the scope of 'not'. Preserve the negation; do not silently rewrite it."},
            {"kind": "case", "id": "C-04", "title": "Ambiguous trailing qualifier", "action": "Use: 'Treatment reduced pain in older adults and improved mobility [1].'", "expected": "Keep the coordination together when the population qualifier could scope over only one predicate. Do not guess which predicate it modifies."},
            {"kind": "case", "id": "C-05", "title": "Multiple markers in one context", "action": "Use: 'Prior work supports this method [1] and reports similar outcomes [2].'", "expected": "Markers share the same Citation Context under the current conservative segmentation rule. Claims in that context link to all its targets; the report discloses that the association is inferred."},
            {"kind": "case", "id": "C-06", "title": "Separate clauses and targets", "action": "Use: 'The treatment improved symptoms [1]; however, controls found no effect [2].' Repeat with 'whereas' or 'although'.", "expected": "Separate citation-bearing clauses become separate Citation Contexts. The first claim links to [1] only; the second links to [2] only."},
            {"kind": "case", "id": "C-07", "title": "Ambiguous clause boundary", "action": "Use: 'Prior work supports the method [1], which remains under discussion [2].'", "expected": "Use the containing sentence as SENTENCE_FALLBACK when the clause boundary is not reliable. Do not infer a narrower target assignment."},
            {"kind": "case", "id": "C-08", "title": "Duplicate wording and uncited text", "action": "Repeat an identical cited sentence at two distinct source locations; include an uncited claim elsewhere.", "expected": "The two distinct spans remain separate claims; only a duplicate with the same source span is deduplicated within a run. The uncited proposition is not included in citation-backed verification."},
            {"kind": "case", "id": "C-09", "title": "Citation marker / bibliography edge cases", "action": "Try grouped numeric citations, author-date citations, an unmatched callout, a repeated marker, and an unused bibliography heading.", "expected": "Persist only parser-observed occurrences and target links. Never invent a target; preserve source spans and exclude heading-only bibliography artifacts from structured entries."},
        ],
    },
    {
        "title": "03 | Reference Resolution",
        "subtitle": "Verify conservative identity matching and separate outcomes per Cited Reference.",
        "footer": "CLAIM ORACLE: exact DOI can resolve; mismatch or ambiguity stays UNRESOLVED; each claim-reference pair keeps its own outcome.",
        "blocks": [
            {"kind": "case", "id": "R-01", "title": "Exact DOI match", "action": "Resolve a bibliography entry with a valid DOI against metadata carrying that exact normalized DOI.", "expected": "The entry resolves to that Canonical Paper. A DOI lookup returning a different DOI must not resolve it."},
            {"kind": "case", "id": "R-02", "title": "Ambiguous or below-threshold metadata", "action": "Use a title-only reference with close competing matches, missing fields, or a score below the run-pinned threshold.", "expected": "Record UNRESOLVED rather than selecting the highest-scoring guess. Preserve the resolution reason and policy/threshold snapshot."},
            {"kind": "case", "id": "R-03", "title": "Unsupported reference type", "action": "Cite a WEBSITE/OTHER entry (for example, a website, standard, or news story) with no supported scholarly type.", "expected": "The entry is UNSUPPORTED_REFERENCE_TYPE before metadata lookup; no cited-source acquisition or System One work follows."},
            {"kind": "case", "id": "R-04", "title": "One marker with multiple targets", "action": "Link a claim to one resolved and one unresolved Cited Reference in the same Citation Context.", "expected": "Create a separate outcome for each Atomic Claim x Cited Reference. One resolved source does not hide, replace, or resolve the other."},
        ],
    },
    {
        "title": "04 | Cited-Paper Access",
        "subtitle": "Verify legal acquisition, language gating, and conservative abstention.",
        "footer": "CLAIM ORACLE: abstract or unsupported language -> INSUFFICIENT_EVIDENCE; no legal full text or abstract -> INACCESSIBLE; unsafe sources are not fetched.",
        "blocks": [
            {"kind": "case", "id": "A-01", "title": "Legal full text and exact asset", "action": "Acquire an allowed HTTPS PDF or text location with an explicit CC0, CC BY, or public-domain license.", "expected": "Persist legal-location/access provenance and the acquired asset hash. Stage 04 uses that exact run-pinned asset, parser, and language provenance."},
            {"kind": "case", "id": "A-02", "title": "Unsafe or unlicensed locations", "action": "Supply an unknown/restrictive license, paywall, private host, redirect, URL credentials, query string, or fragment.", "expected": "Reject the location before fetching. Never infer permission from public reachability; never bypass a paywall."},
            {"kind": "case", "id": "A-03", "title": "Fetch failure and location fallback", "action": "Make the first permitted location fail, then make a later permitted location succeed; repeat with every fetch failing.", "expected": "Try the next legal location. If none works, preserve known metadata/abstract and expose FULL_TEXT_ACQUISITION_FAILED; do not erase known access."},
            {"kind": "case", "id": "A-04", "title": "Abstract-only access", "action": "Resolve a paper with an abstract but no legally accessible full text.", "expected": "Final status INSUFFICIENT_EVIDENCE, scope ABSTRACT_ONLY. No embedding, retrieval, or semantic-verifier call uses the abstract."},
            {"kind": "case", "id": "A-05", "title": "Metadata-only or unavailable", "action": "Resolve a paper with metadata but no abstract/full text, then a paper with no usable access at all.", "expected": "A resolved reference with neither legal full text nor abstract is INACCESSIBLE. Keep it distinct from UNRESOLVED."},
            {"kind": "case", "id": "A-06", "title": "Accessible non-English cited full text", "action": "Acquire a non-English Cited Paper while the Source Document remains English.", "expected": "INSUFFICIENT_EVIDENCE with LANGUAGE_UNSUPPORTED and verification scope NONE. Do not embed or send the text to System One."},
        ],
    },
    {
        "title": "05 | Evidence and Claim-Paper Outcomes",
        "subtitle": "Separate retrieval, passage judgement, aggregation, and operational failure.",
        "footer": "CLAIM ORACLE: direct -> SUPPORTED; partial -> PARTIALLY_SUPPORTED; contradiction -> CONTRADICTED; comparable conflict -> INSUFFICIENT + conflict; failures stay incomplete.",
        "blocks": [
            {"kind": "case", "id": "E-01", "title": "Retrieval stays inside the cited work", "action": "Give two cited papers distinct sentinel passages; query each claim-reference pair.", "expected": "Every Evidence Passage comes only from the exact Cited Paper Asset for that reference. No passage leaks across references, runs, or newer assets."},
            {"kind": "case", "id": "E-02", "title": "Candidate passage is not a judgement", "action": "Inspect the retrieved top passages and their ranks before and after System One runs.", "expected": "Retrieval exposes candidates and provenance only. Ranking, similarity, or a top result does not itself imply support or a final status."},
            {"kind": "case", "id": "E-03", "title": "Evidence role and source context", "action": "Test a paper's own finding, author synthesis/review, and a secondary report of another study.", "expected": "Expose Evidence Role and section. Do not present a secondary mention as direct evidence for the underlying primary study's result."},
            {"kind": "case", "id": "E-04", "title": "Strong direct support", "action": "Feed a controlled DIRECT_SUPPORT judgement with strong scope/directness and no comparable contradiction.", "expected": "The deterministic aggregation policy yields SUPPORTED. Show the exact passage and uncalibrated judgement/provenance."},
            {"kind": "case", "id": "E-05", "title": "Partial support or strong contradiction", "action": "In separate controlled cases, supply partial support without stronger contradiction, then strong contradiction without comparable support.", "expected": "Outcomes are PARTIALLY_SUPPORTED and CONTRADICTED respectively. Material population, condition, scope, or outcome mismatch remains visible."},
            {"kind": "case", "id": "E-06", "title": "Comparable support and contradiction", "action": "Provide comparable credible passages on both sides for the same claim and Cited Reference.", "expected": "Yield INSUFFICIENT_EVIDENCE with evidenceConflict=true and expose both sides. Do not let raw model confidence alone decide."},
            {"kind": "case", "id": "E-07", "title": "No qualifying evidence / unrelated passages", "action": "Use empty retrieval, unrelated passages, or controlled INSUFFICIENT judgements.", "expected": "A successfully completed assessment with no qualifying support is INSUFFICIENT_EVIDENCE. UNRELATED remains a passage judgement, not a final status."},
            {"kind": "case", "id": "E-08", "title": "Unresolved, unsupported, inaccessible, abstract, language", "action": "Exercise each access/resolution outcome from pages 3 and 4.", "expected": "Final status stays distinct: UNRESOLVED, UNSUPPORTED_REFERENCE_TYPE, INACCESSIBLE, or INSUFFICIENT_EVIDENCE with its scope/reason. Do not collapse them into one bucket."},
            {"kind": "case", "id": "E-09", "title": "Provider/parser/indexing failure", "action": "Force a transient timeout, malformed provider response, parser failure, and deterministic provider rejection.", "expected": "Record an incomplete/failed pair and stable reason; never fabricate a judgement or disguise a processing failure as INSUFFICIENT_EVIDENCE. Partial success may complete with warnings."},
            {"kind": "case", "id": "E-10", "title": "System One context limit", "action": "Use an over-limit passage, including one with an unfit single sentence.", "expected": "Do not truncate. Eligible passages use diagnostic child spans under the unchanged parent; failed/missing spans remain incomplete and cannot create a separate final verification."},
        ],
    },
    {
        "title": "06 | Report, Human Review, and Lifecycle",
        "subtitle": "Validate the visible report and keep human assessment separate from machine output.",
        "footer": "CLAIM ORACLE: report traces each Atomic Claim x Cited Reference; Human Review is append-only and never mutates the machine finalStatus.",
        "blocks": [
            {"kind": "case", "id": "H-01", "title": "Coverage summary and drill-down", "action": "Open a completed Analysis Run with multiple claims, references, outcomes, and one incomplete pair.", "expected": "Summary counts use the seven domain statuses and distinguish completed/incomplete/conflict work. Drill-down traces claim -> Citation Context/Marker -> Cited Reference -> exact Evidence Passage and provenance."},
            {"kind": "case", "id": "H-02", "title": "Source-document highlight", "action": "Select a claim and citation; use Show in PDF on pages with duplicate wording, columns, and nearby unrelated markers.", "expected": "Highlight the selected source span and marker only; page navigation and PDF search retain the correct position."},
            {"kind": "case", "id": "H-03", "title": "Pipeline progress and PARSED state", "action": "Inspect a run with final verification disabled and another with the full pipeline enabled.", "expected": "Stage summaries match persisted work items. PARSED is not presented as a completed Evidence Coverage Report; skipped, failed, warning, and completed states remain distinct."},
            {"kind": "case", "id": "H-04", "title": "Triage disclaimer and calibration language", "action": "Inspect summary, passage judgements, final statuses, and provider details.", "expected": "Report says triage aid, not truth certification or whole-paper grade. Laya judgements/statuses are visibly uncalibrated; no accuracy claim is implied."},
            {"kind": "case", "id": "H-05", "title": "Agree and disagree review", "action": "Record AGREE and DISAGREE for a completed machine verification without an override status.", "expected": "Both reviews are accepted and appended. Machine finalStatus is unchanged; review action/history is shown separately."},
            {"kind": "case", "id": "H-06", "title": "Override review validation", "action": "Submit OVERRIDE with and without overrideStatus; submit AGREE/DISAGREE with an overrideStatus; test notes at 2,000 and 2,001 characters.", "expected": "OVERRIDE requires a valid separate status. AGREE/DISAGREE omit it. Overlong note is rejected. Only a completed verification with a machine status can be reviewed."},
            {"kind": "case", "id": "H-07", "title": "Review append-only behavior", "action": "Record a second review that disagrees with the first; reload the Analysis Run.", "expected": "Both records remain in history in order. Neither review rewrites nor hides the machine result; reviews are removed only by explicit document deletion."},
            {"kind": "case", "id": "H-08", "title": "Re-analysis creates a new run", "action": "Re-analyze the same Source Document with a changed provider or profile.", "expected": "A new immutable Analysis Run is created with its own pinned configuration/provenance. Earlier results and reviews remain unchanged."},
            {"kind": "case", "id": "H-09", "title": "Delete document during and after processing", "action": "Delete an idle document, then repeat while worker work is pending/in flight; inspect related runs and data.", "expected": "Deletion tombstones first, invalidates pending work, removes document-scoped source/derived data and Human Reviews, and prevents workers from resurrecting it. Shared assets remain only while referenced elsewhere."},
        ],
    },
    {
        "title": "07 | Providers, Privacy, and Reliability",
        "subtitle": "Check consent, provenance, idempotency, safe logging, and deployment boundaries.",
        "footer": "CLAIM ORACLE: external calls require fresh provider/run consent; historical runs keep their pins; deletion cannot retract data already sent externally.",
        "blocks": [
            {"kind": "case", "id": "P-01", "title": "Provider availability is not consent", "action": "With recorded fixtures selected, verify no remote scholarly/OA calls. Select Crossref or Unpaywall without consent, then with exact per-run consent.", "expected": "No unconsented external call occurs. Consent is provider- and run-specific and names the actual data categories; enabling a provider alone is not consent."},
            {"kind": "case", "id": "P-02", "title": "Minimum payload and category boundary", "action": "Inspect a safe test transport for Crossref, Unpaywall, external Ollama, or System One calls.", "expected": "Send only documented minimum categories. Never send the full Source Document unless a reviewed provider operation explicitly requires it; never put credentials in the browser or run snapshot."},
            {"kind": "case", "id": "P-03", "title": "Retention/deletion disclosure", "action": "Review consent UI and deletion copy for each selected external provider.", "expected": "Show provider-specific categories and retention/deletion limits. Explain that local deletion cannot retract content already sent to an external provider."},
            {"kind": "case", "id": "P-04", "title": "Run-pinned provenance", "action": "Inspect the Analysis Run snapshot and verification for source hash/parser, extraction and resolution policy, provider selections, retrieval/embedding profile, exact cited asset hash/parser, and language detector when used.", "expected": "The run records the configuration actually executed, with non-secret fingerprints. Later configuration changes do not rewrite this snapshot."},
            {"kind": "case", "id": "P-05", "title": "Provider failure does not silently fall back", "action": "Make a selected provider fail or become unavailable after run creation.", "expected": "The run exposes the failure/incomplete work. It does not silently switch to a different provider or model and misstate provenance."},
            {"kind": "case", "id": "P-06", "title": "Duplicate delivery and worker restart", "action": "Deliver one pipeline event twice and restart a worker with pending stream work.", "expected": "Idempotent persistence prevents duplicate/corrupt outcomes; pending work is reclaimed and acknowledged only after its durable transaction succeeds."},
            {"kind": "case", "id": "P-07", "title": "Outbox and shared acquisition/indexing", "action": "Exercise a database commit/message-publish failure and concurrent runs needing the same Cited Paper asset.", "expected": "The transactional outbox prevents committed work from disappearing. Locks reduce duplicate expensive work; exact asset/profile boundaries remain intact."},
            {"kind": "case", "id": "P-08", "title": "Safe observability", "action": "Cause representative request, provider, parser, and worker errors; inspect API, worker, and sidecar logs.", "expected": "Do not log Source Document, claim, or evidence text, request bodies, query strings, credentials, or access tokens. Log safe request/status/error metadata only."},
            {"kind": "case", "id": "P-09", "title": "Network exposure", "action": "Inspect Compose bindings and deployment listener addresses for the unauthenticated workspace/API.", "expected": "Keep services on loopback or a trusted private network. Do not expose the V1 unauthenticated workspace/API to an untrusted network."},
        ],
    },
    {
        "title": "08 | Boundaries, Sign-off, and Known Gaps",
        "subtitle": "Use this page to separate a V1 failure from an explicit non-goal or open verification gap.",
        "footer": "CLAIM ORACLE: test documented V1 behavior; mark non-goals N/A; record every failed or unverified outcome instead of assuming it passed.",
        "blocks": [
            {"kind": "section", "text": "V1 boundaries (not defects by themselves)"},
            {"kind": "body", "text": "Not supported: OCR/scanned Source Documents; non-English Source Documents; books, websites, standards, and other non-scholarly Cited References as evidence sources; manual Cited Paper upload; paywall bypass; generic web crawling; automated rewriting/grading; authentication/multi-user workflows."},
            {"kind": "body", "text": "Optional provider availability is not a feature guarantee. LLM-based claim extraction, Semantic Scholar enrichment, Google embeddings, and hosted Jev integrations are not implemented in this repository snapshot. The runtime uses heuristic claim extraction, Crossref/recorded metadata options, local Ollama/feature-hash embeddings, and mock/Laya System One."},
            {"kind": "notice", "label": "SPEC / IMPLEMENTATION TENSION", "text": "The tech design excludes books as verifiable V1 sources, while the current ConservativeReferenceResolver lists BOOK among accepted metadata-resolution types. Use WEBSITE/OTHER to test UNSUPPORTED_REFERENCE_TYPE. Confirm the intended end-to-end book behavior with the product owner before using it as a release oracle."},
            {"kind": "section", "text": "Known verification gaps in the repository documentation"},
            {"kind": "body", "text": "The Pipeline Implementation and Feature Traceability Matrix dated 2026-10-03 identifies issue #55 (full web upload -> queued processing -> report) and issue #56 (PDF page-count boundary) as open verification gaps at that snapshot. Check current GitHub issue state before treating these as current; run and record the checks rather than inferring coverage from source tests."},
            {"kind": "section", "text": "Release evidence record"},
            {"kind": "case", "id": "SIGN-OFF", "title": "Record one result per test case", "action": "For each case, record: case ID; build/commit; environment and provider selections; Analysis Run ID; PASS/FAIL/BLOCKED/N/A; expected vs observed result; safe evidence path; linked issue for any failure.", "expected": "All in-scope cases have an observed result. Every blocked or unrun case has a named dependency/owner. No unverified assumption is labeled PASS."},
            {"kind": "notice", "label": "CLAIM STATUS QUICK REFERENCE", "text": "SUPPORTED = strong direct full-text support; PARTIALLY_SUPPORTED = only part/narrower scope; CONTRADICTED = material conflict; INSUFFICIENT_EVIDENCE = no qualifying assessed support (also abstract-only/unsupported language); INACCESSIBLE = resolved but no legal full text or abstract; UNRESOLVED = identity not confidently matched; UNSUPPORTED_REFERENCE_TYPE = outside V1 source types."},
            {"kind": "body", "text": "Basis: CONTEXT.md; docs/paper-t-rail-tech-design.md; docs/adr/0001-0009; docs/pipeline-feature-matrix.md; docs/benchmarks/v1-runtime-matrix.md; docs/agents/provider-matrix.md. This checklist covers documented V1 product behavior, not every mathematically possible PDF or every scholarly domain."},
        ],
    },
]

SOURCE_FIXTURE_PATH = OUTPUT_DIR / "paper-t-rail-source-fixture.pdf"

SOURCE_PAGES = [
    {
        "heading": "A Controlled Test of Traceable Citation Evidence",
        "footer": "C-01: one Atomic Claim; R1 matches the checked-in DOI/full-text fixture. Final semantic status depends on provider selection; this PDF is not model-accuracy evidence.",
        "blocks": [
            ("byline", "Paper T-Rail QA Fixture Team | Synthetic manuscript for local validation"),
            ("notice", "TEST DOCUMENT - The scenarios below are synthetic. Use this Source Document only in a local/test environment. Footer text is the visible QA oracle, not manuscript content."),
            ("section", "Abstract"),
            ("paragraph", "This synthetic manuscript exercises citation parsing, claim boundaries, reference matching, and source-span tracing. It includes one exact match for the repository's recorded local scholarly-work fixture, one deliberately unmatched journal reference, and one website reference."),
            ("section", "1. Introduction"),
            ("caseheading", "Scenario C-01 | Simple cited proposition"),
            ("paragraph", "Conservative reference resolution prevents ambiguous bibliography entries from being assigned an unsupported paper identity [1]."),
            ("paragraph", "The final Evidence Coverage Report is a triage aid, not a truth certificate. This sentence is intentionally uncited and should not create a claim verification."),
        ],
    },
    {
        "heading": "2. Claim Boundaries and Qualifiers",
        "footer": "C-02: two claims with the shared qualifier and both targets; C-03/C-04: one conservative claim each, with negation and scope preserved.",
        "blocks": [
            ("section", "2.1 Shared population qualifier"),
            ("caseheading", "Scenario C-02 | Coordinated predicates"),
            ("paragraph", "Among older adults, treatment reduced pain and improved mobility [1, 2]."),
            ("section", "2.2 Ambiguous negation"),
            ("caseheading", "Scenario C-03 | Do not guess negation scope"),
            ("paragraph", "Treatment did not improve symptoms and reduce dropout [1]."),
            ("section", "2.3 Ambiguous trailing qualifier"),
            ("caseheading", "Scenario C-04 | Preserve uncertain qualifier scope"),
            ("paragraph", "Treatment reduced pain in older adults and improved mobility [1]."),
            ("paragraph", "The examples are fixtures for claim-shape behavior; they are not empirical findings about the cited paper."),
        ],
    },
    {
        "heading": "3. Citation Contexts and Source Spans",
        "footer": "C-05 shared targets; C-06 separate clauses; C-07 fallback; C-08 distinct spans; C-09 uncited; C-10 author-date if parsed. R1 exact DOI -> RESOLVED; R2 unmatched -> UNRESOLVED; inspect R3 type before unsupported outcome.",
        "blocks": [
            ("section", "3.1 Multiple markers in one context"),
            ("caseheading", "Scenario C-05 | Two markers, one context"),
            ("paragraph", "Prior work supports this method [1] and reports similar outcomes [2]."),
            ("section", "3.2 Citation-bearing clauses"),
            ("caseheading", "Scenario C-06 | Separate clauses"),
            ("paragraph", "The treatment improved symptoms [1]; however, controls found no effect [2]."),
            ("section", "3.3 Uncertain boundary and duplicate wording"),
            ("caseheading", "Scenario C-07 | Sentence fallback"),
            ("paragraph", "Prior work supports the method [1], which remains under discussion [2]."),
            ("caseheading", "Scenario C-08 | Same wording, different locations"),
            ("paragraph", "The intervention improved mobility [1]."),
            ("paragraph", "The intervention improved mobility [1]."),
            ("section", "3.4 Uncited and author-date examples"),
            ("caseheading", "Scenario C-09 | No citation"),
            ("paragraph", "A proposition without a citation appears here and should not be verified."),
            ("caseheading", "Scenario C-10 | Author-date marker"),
            ("paragraph", "The study describes a conservative reference-resolution method (Example & Researcher, 2024)."),
        ],
    },
    {
        "heading": "References",
        "footer": "",
        "blocks": [
            ("section", "References"),
            ("reference", "[1] Example, Riley, and Jordan Researcher. 2024. A fixture study of conservative scholarly reference resolution. Journal of Reference Resolution 1 (1): 1-2. DOI: 10.5555/papertrail.fixture.reference-resolution.2024."),
            ("reference", "[2] Sample, Morgan. 2026. Unmatched test reference for QA. Journal of Validation Cases 4 (2): 12-19. DOI: 10.5555/papertrail.qa.missing.2026."),
            ("reference", "[3] Test Automation Office. 2025. User Manual for the Synthetic Validation Toolkit. Web page: https://qa.example.test/manual."),
        ],
    },
]

# PDF drawing helpers. Coordinates use a bottom-left origin; all strings are
# normalized to WinAnsi-compatible ASCII for the built-in PDF fonts.

def ascii_text(value: str) -> str:
    replacements = {
        "–": "-", "—": "-", "−": "-", "×": " x ", "→": "->", "←": "<-",
        "≥": ">=", "≤": "<=", "≠": "!=", "•": "*", "·": " | ",
        "“": '"', "”": '"', "‘": "'", "’": "'", "…": "...", "œ": "oe", "Œ": "OE",
    }
    for source, target in replacements.items():
        value = value.replace(source, target)
    value = unicodedata.normalize("NFKD", value).encode("ascii", "ignore").decode("ascii")
    return " ".join(value.split())


def pdf_escape(value: str) -> bytes:
    raw = ascii_text(value).encode("ascii")
    return raw.replace(b"\\", b"\\\\").replace(b"(", b"\\(").replace(b")", b"\\)")


def wrap(value: str, max_chars: int) -> list[str]:
    value = ascii_text(value)
    if not value:
        return [""]
    return textwrap.wrap(value, width=max_chars, break_long_words=True, break_on_hyphens=False) or [""]


class Canvas:
    def __init__(self) -> None:
        self.parts: list[bytes] = []

    def command(self, value: str) -> None:
        self.parts.append(value.encode("ascii") + b"\n")

    def fill(self, color: tuple[float, float, float]) -> None:
        self.command(f"{color[0]:.3f} {color[1]:.3f} {color[2]:.3f} rg")

    def stroke(self, color: tuple[float, float, float]) -> None:
        self.command(f"{color[0]:.3f} {color[1]:.3f} {color[2]:.3f} RG")

    def rect(self, x: float, y: float, width: float, height: float, color: tuple[float, float, float]) -> None:
        self.fill(color)
        self.command(f"{x:.2f} {y:.2f} {width:.2f} {height:.2f} re f")

    def line(self, x1: float, y1: float, x2: float, y2: float, color: tuple[float, float, float], width: float = 0.7) -> None:
        self.stroke(color)
        self.command(f"{width:.2f} w {x1:.2f} {y1:.2f} m {x2:.2f} {y2:.2f} l S")

    def text(self, value: str, x: float, y: float, size: float, font: str, color: tuple[float, float, float]) -> None:
        self.fill(color)
        self.parts.append(b"BT\n")
        self.parts.append(f"/{font} {size:.2f} Tf\n".encode("ascii"))
        self.parts.append(f"1 0 0 1 {x:.2f} {y:.2f} Tm\n".encode("ascii"))
        self.parts.append(b"(" + pdf_escape(value) + b") Tj\nET\n")

    def bytes(self) -> bytes:
        return b"".join(self.parts)


INK = (0.031, 0.157, 0.231)
NAVY = (0.082, 0.231, 0.384)
TEAL = (0.071, 0.400, 0.420)
RED = (0.761, 0.231, 0.169)
MUTED = (0.322, 0.380, 0.416)
PAPER = (0.992, 0.980, 0.941)
WHITE = (1.000, 0.992, 0.965)
PALE_BLUE = (0.894, 0.929, 0.941)
PALE_RED = (0.984, 0.929, 0.902)
BORDER = (0.824, 0.780, 0.682)


def draw_wrapped(canvas: Canvas, value: str, x: float, y: float, width: float, size: float, leading: float, font: str, color: tuple[float, float, float], max_chars_override: int | None = None) -> tuple[float, int]:
    max_chars = max_chars_override or max(20, int(width / (size * (0.53 if font == "F1" else 0.56))))
    lines = wrap(value, max_chars)
    for line in lines:
        canvas.text(line, x, y, size, font, color)
        y -= leading
    return y, len(lines)


def draw_header(canvas: Canvas, page_number: int) -> None:
    canvas.rect(0, PAGE_HEIGHT - 50, PAGE_WIDTH, 50, NAVY)
    # A compact rail motif beside the product name.
    canvas.rect(LEFT, PAGE_HEIGHT - 31, 7, 7, RED)
    canvas.rect(LEFT + 10, PAGE_HEIGHT - 31, 7, 7, (0.941, 0.773, 0.463))
    canvas.line(LEFT, PAGE_HEIGHT - 36, LEFT + 17, PAGE_HEIGHT - 36, (0.804, 0.855, 0.863), 1.2)
    canvas.text("PAPER T-RAIL", LEFT + 25, PAGE_HEIGHT - 31, 9.0, "F2", WHITE)
    canvas.text("V1 VALIDATION WORKBOOK", PAGE_WIDTH - 186, PAGE_HEIGHT - 31, 7.2, "F4", (0.875, 0.910, 0.914))


def draw_footer(canvas: Canvas, footer: str, page_number: int, page_count: int) -> None:
    canvas.line(LEFT, 86, PAGE_WIDTH - RIGHT, 86, BORDER, 0.8)
    canvas.text("CLAIM EXPECTATION", LEFT, 72, 6.8, "F4", RED)
    footer_lines = wrap(footer, 118)
    y = 72
    for line in footer_lines[:2]:
        canvas.text(line, LEFT + 98, y, 7.0, "F1", INK)
        y -= 9
    canvas.text(f"{page_number:02d} / {page_count:02d}", PAGE_WIDTH - RIGHT - 45, 45, 7.0, "F4", MUTED)
    canvas.text("Expected behavior is not a truth certificate. Keep model outputs labeled uncalibrated.", LEFT, 45, 7.0, "F1", MUTED)


def draw_block(canvas: Canvas, block: dict, y: float) -> float:
    kind = block["kind"]
    if kind == "title":
        y, _ = draw_wrapped(canvas, block["text"], LEFT, y, CONTENT_WIDTH, 23, 27, "F3", NAVY)
        y -= 3
        y, _ = draw_wrapped(canvas, block.get("subtitle", ""), LEFT, y, CONTENT_WIDTH, 9.5, 13, "F1", MUTED)
        return y - 9

    if kind == "section":
        y -= 4
        y, _ = draw_wrapped(canvas, block["text"].upper(), LEFT, y, CONTENT_WIDTH, 10.1, 12, "F2", TEAL)
        canvas.line(LEFT, y + 1, PAGE_WIDTH - RIGHT, y + 1, BORDER, 0.55)
        return y - 6

    if kind == "body":
        y, _ = draw_wrapped(canvas, block["text"], LEFT, y, CONTENT_WIDTH, 8.8, 11.4, "F1", INK)
        return y - 4

    if kind == "notice":
        label = ascii_text(block["label"])
        text_lines = wrap(block["text"], 103)
        height = 18 + len(text_lines) * 11.2 + 8
        bottom = y - height
        canvas.rect(LEFT, bottom, CONTENT_WIDTH, height, PALE_BLUE)
        canvas.rect(LEFT, bottom, 4, height, TEAL)
        canvas.text(label, LEFT + 13, y - 15, 7.4, "F4", TEAL)
        line_y = y - 28
        for line in text_lines:
            canvas.text(line, LEFT + 13, line_y, 8.2, "F1", INK)
            line_y -= 11.2
        return bottom - 8

    if kind == "case":
        title = f"[ ] {block['id']}  {block['title']}"
        title_lines = wrap(title, 103)
        action_lines = wrap("ACTION: " + block["action"], 112)
        expected_lines = wrap("EXPECTED: " + block["expected"], 112)
        note_lines = wrap("NOTE: " + block["note"], 112) if block.get("note") else []
        line_count = len(title_lines) + len(action_lines) + len(expected_lines) + len(note_lines)
        height = 8 + line_count * 8.9 + (2 if note_lines else 0) + 4
        bottom = y - height
        canvas.rect(LEFT, bottom, CONTENT_WIDTH, height, WHITE)
        canvas.stroke(BORDER)
        canvas.command(f"0.55 w {LEFT:.2f} {bottom:.2f} {CONTENT_WIDTH:.2f} {height:.2f} re S")
        canvas.rect(LEFT, bottom, 3, height, RED)
        cursor = y - 11
        for line in title_lines:
            canvas.text(line, LEFT + 11, cursor, 8.2, "F2", NAVY)
            cursor -= 8.9
        for line in action_lines:
            canvas.text(line, LEFT + 11, cursor, 7.4, "F1", MUTED)
            cursor -= 8.9
        for line in expected_lines:
            canvas.text(line, LEFT + 11, cursor, 7.4, "F1", TEAL)
            cursor -= 8.9
        for line in note_lines:
            canvas.text(line, LEFT + 11, cursor, 7.2, "F1", RED)
            cursor -= 8.9
        return bottom - 4

    raise ValueError(f"Unknown block kind: {kind}")


def render_pdf() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    page_contents: list[bytes] = []
    for page_number, page in enumerate(PAGES, 1):
        canvas = Canvas()
        canvas.rect(0, 0, PAGE_WIDTH, PAGE_HEIGHT, PAPER)
        draw_header(canvas, page_number)
        y = CONTENT_TOP
        blocks = [{"kind": "title", "text": page["title"], "subtitle": page["subtitle"]}, *page["blocks"]]
        for block in blocks:
            y = draw_block(canvas, block, y)
        if y < CONTENT_BOTTOM:
            raise ValueError(f"Page {page_number} content overlaps footer: bottom y={y:.1f}pt")
        draw_footer(canvas, page["footer"], page_number, len(PAGES))
        page_contents.append(canvas.bytes())

    objects: list[bytes] = []
    def add_object(value: bytes) -> int:
        objects.append(value)
        return len(objects)

    catalog_id = add_object(b"")
    pages_id = add_object(b"")
    helvetica_id = add_object(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    bold_id = add_object(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
    times_id = add_object(b"<< /Type /Font /Subtype /Type1 /BaseFont /Times-Bold /Encoding /WinAnsiEncoding >>")
    courier_id = add_object(b"<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>")

    page_ids: list[int] = []
    for content in page_contents:
        content_id = add_object(b"<< /Length " + str(len(content)).encode("ascii") + b" >>\nstream\n" + content + b"endstream")
        page_id = add_object(
            f"<< /Type /Page /Parent {pages_id} 0 R /MediaBox [0 0 {PAGE_WIDTH} {PAGE_HEIGHT}] ".encode("ascii")
            + f"/Resources << /Font << /F1 {helvetica_id} 0 R /F2 {bold_id} 0 R /F3 {times_id} 0 R /F4 {courier_id} 0 R >> >> ".encode("ascii")
            + f"/Contents {content_id} 0 R >>".encode("ascii")
        )
        page_ids.append(page_id)

    objects[catalog_id - 1] = f"<< /Type /Catalog /Pages {pages_id} 0 R >>".encode("ascii")
    kids = " ".join(f"{page_id} 0 R" for page_id in page_ids)
    objects[pages_id - 1] = f"<< /Type /Pages /Kids [{kids}] /Count {len(page_ids)} >>".encode("ascii")
    info_id = add_object(b"<< /Title (Paper T-Rail V1 Validation Workbook) /Author (Paper T-Rail) /Subject (Manual and system QA checklist with claim expectations) >>")

    output = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(output))
        output.extend(f"{index} 0 obj\n".encode("ascii"))
        output.extend(obj)
        output.extend(b"\nendobj\n")
    xref_offset = len(output)
    output.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
    output.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        output.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    output.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root {catalog_id} 0 R /Info {info_id} 0 R >>\nstartxref\n{xref_offset}\n%%EOF\n".encode("ascii")
    )
    PDF_PATH.write_bytes(output)


def write_pdf_file(path: Path, page_contents: list[bytes], title: str, subject: str) -> None:
    objects: list[bytes] = []

    def add_object(value: bytes) -> int:
        objects.append(value)
        return len(objects)

    catalog_id = add_object(b"")
    pages_id = add_object(b"")
    helvetica_id = add_object(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
    bold_id = add_object(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
    times_id = add_object(b"<< /Type /Font /Subtype /Type1 /BaseFont /Times-Bold /Encoding /WinAnsiEncoding >>")
    courier_id = add_object(b"<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>")

    page_ids: list[int] = []
    for content in page_contents:
        content_id = add_object(b"<< /Length " + str(len(content)).encode("ascii") + b" >>\nstream\n" + content + b"endstream")
        page_id = add_object(
            f"<< /Type /Page /Parent {pages_id} 0 R /MediaBox [0 0 {PAGE_WIDTH} {PAGE_HEIGHT}] ".encode("ascii")
            + f"/Resources << /Font << /F1 {helvetica_id} 0 R /F2 {bold_id} 0 R /F3 {times_id} 0 R /F4 {courier_id} 0 R >> >> ".encode("ascii")
            + f"/Contents {content_id} 0 R >>".encode("ascii")
        )
        page_ids.append(page_id)

    objects[catalog_id - 1] = f"<< /Type /Catalog /Pages {pages_id} 0 R >>".encode("ascii")
    kids = " ".join(f"{page_id} 0 R" for page_id in page_ids)
    objects[pages_id - 1] = f"<< /Type /Pages /Kids [{kids}] /Count {len(page_ids)} >>".encode("ascii")
    info_id = add_object(
        b"<< /Title (" + pdf_escape(title) + b") /Author (Paper T-Rail) /Subject (" + pdf_escape(subject) + b") >>"
    )

    output = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(output))
        output.extend(f"{index} 0 obj\n".encode("ascii"))
        output.extend(obj)
        output.extend(b"\nendobj\n")
    xref_offset = len(output)
    output.extend(f"xref\n0 {len(objects) + 1}\n".encode("ascii"))
    output.extend(b"0000000000 65535 f \n")
    for offset in offsets[1:]:
        output.extend(f"{offset:010d} 00000 n \n".encode("ascii"))
    output.extend(
        f"trailer\n<< /Size {len(objects) + 1} /Root {catalog_id} 0 R /Info {info_id} 0 R >>\nstartxref\n{xref_offset}\n%%EOF\n".encode("ascii")
    )
    path.write_bytes(output)


def draw_source_header(canvas: Canvas) -> None:
    canvas.rect(0, PAGE_HEIGHT - 50, PAGE_WIDTH, 50, NAVY)
    canvas.rect(LEFT, PAGE_HEIGHT - 31, 7, 7, RED)
    canvas.rect(LEFT + 10, PAGE_HEIGHT - 31, 7, 7, (0.941, 0.773, 0.463))
    canvas.line(LEFT, PAGE_HEIGHT - 36, LEFT + 17, PAGE_HEIGHT - 36, (0.804, 0.855, 0.863), 1.2)
    canvas.text("PAPER T-RAIL", LEFT + 25, PAGE_HEIGHT - 31, 9.0, "F2", WHITE)
    canvas.text("SYNTHETIC QA SOURCE DOCUMENT", PAGE_WIDTH - 204, PAGE_HEIGHT - 31, 7.0, "F4", (0.875, 0.910, 0.914))


def draw_source_footer(canvas: Canvas, footer: str, page_number: int, page_count: int) -> None:
    canvas.line(LEFT, 86, PAGE_WIDTH - RIGHT, 86, BORDER, 0.8)
    if footer:
        canvas.text("CLAIM EXPECTATION", LEFT, 73, 6.6, "F4", RED)
        footer_lines = wrap(footer, 92)
        y = 73
        for line in footer_lines[:3]:
            canvas.text(line, LEFT + 98, y, 6.8, "F1", INK)
            y -= 8
    canvas.text(f"{page_number:02d} / {page_count:02d}", PAGE_WIDTH - RIGHT - 45, 44, 7.0, "F4", MUTED)
    canvas.text("QA oracle only | semantic model outcomes remain uncalibrated", LEFT, 44, 7.0, "F1", MUTED)


def draw_source_block(canvas: Canvas, block: tuple[str, str], y: float) -> float:
    kind, value = block
    if kind == "title":
        y, _ = draw_wrapped(canvas, value, LEFT, y, CONTENT_WIDTH, 20, 24, "F3", NAVY)
        return y - 8
    if kind == "byline":
        y, _ = draw_wrapped(canvas, value, LEFT, y, CONTENT_WIDTH, 8.4, 11, "F1", MUTED)
        return y - 10
    if kind == "section":
        y -= 2
        y, _ = draw_wrapped(canvas, value.upper(), LEFT, y, CONTENT_WIDTH, 10.4, 13, "F2", TEAL)
        canvas.line(LEFT, y + 2, PAGE_WIDTH - RIGHT, y + 2, BORDER, 0.55)
        return y - 8
    if kind == "caseheading":
        y, _ = draw_wrapped(canvas, value.upper(), LEFT, y, CONTENT_WIDTH, 7.4, 10, "F4", RED)
        return y - 3
    if kind in {"paragraph", "reference"}:
        size = 9.4 if kind == "paragraph" else 8.8
        leading = 13.2 if kind == "paragraph" else 12
        y, _ = draw_wrapped(canvas, value, LEFT, y, CONTENT_WIDTH, size, leading, "F1", INK)
        return y - (7 if kind == "paragraph" else 9)
    if kind == "notice":
        lines = wrap(value, 100)
        height = 17 + len(lines) * 11 + 8
        bottom = y - height
        canvas.rect(LEFT, bottom, CONTENT_WIDTH, height, PALE_RED)
        canvas.rect(LEFT, bottom, 4, height, RED)
        cursor = y - 15
        for line in lines:
            canvas.text(line, LEFT + 12, cursor, 8.0, "F1", INK)
            cursor -= 11
        return bottom - 9
    raise ValueError(f"Unknown source-document block kind: {kind}")


def render_source_fixture() -> None:
    page_contents: list[bytes] = []
    markdown = ["# Paper T-Rail Synthetic Source Document Fixture", "", "Upload this PDF to validate source parsing and Citation Context behavior. Page footers are the visible QA oracle.", ""]
    for page_number, page in enumerate(SOURCE_PAGES, 1):
        canvas = Canvas()
        canvas.rect(0, 0, PAGE_WIDTH, PAGE_HEIGHT, PAPER)
        draw_source_header(canvas)
        y = CONTENT_TOP
        y = draw_source_block(canvas, ("title", page["heading"]), y)
        for kind, text_value in page["blocks"]:
            y = draw_source_block(canvas, (kind, text_value), y)
            if y < CONTENT_BOTTOM:
                raise ValueError(f"Source fixture page {page_number} content overlaps footer: bottom y={y:.1f}pt")
        draw_source_footer(canvas, page["footer"], page_number, len(SOURCE_PAGES))
        page_contents.append(canvas.bytes())

        markdown.extend([f"## Page {page_number}: {page['heading']}", ""])
        for kind, value in page["blocks"]:
            markdown.append(f"### {kind.title()}" if kind in {"section", "caseheading"} else value)
            if kind in {"section", "caseheading"}:
                markdown.append(value)
            markdown.append("")
        if page["footer"]:
            markdown.extend([f"> **Claim expectation footer:** {page['footer']}", ""])
    (OUTPUT_DIR / "paper-t-rail-source-fixture.md").write_text("\n".join(markdown), encoding="utf-8")
    write_pdf_file(
        SOURCE_FIXTURE_PATH,
        page_contents,
        "Paper T-Rail synthetic source document QA fixture",
        "English academic-style PDF fixture with citation and claim parser cases",
    )


def render_markdown() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    lines = ["# Paper T-Rail V1 Validation Workbook", "", "**Repository snapshot:** 2026-10-04", ""]
    for page in PAGES:
        lines.extend([f"# {page['title']}", "", page["subtitle"], ""])
        for block in page["blocks"]:
            kind = block["kind"]
            if kind == "section":
                lines.extend([f"## {block['text']}", ""])
            elif kind == "body":
                lines.extend([block["text"], ""])
            elif kind == "notice":
                lines.extend([f"> **{block['label']}:** {block['text']}", ""])
            elif kind == "case":
                lines.extend([
                    f"### [ ] {block['id']} - {block['title']}",
                    f"- **Action:** {block['action']}",
                    f"- **Expected:** {block['expected']}",
                ])
                if block.get("note"):
                    lines.append(f"- **Note:** {block['note']}")
                lines.append("")
        lines.extend([f"> **Claim expectation footer:** {page['footer']}", "", "---", ""])
    MARKDOWN_PATH.write_text("\n".join(lines), encoding="utf-8")


def main() -> None:
    render_markdown()
    render_pdf()
    render_source_fixture()
    print(f"Wrote {PDF_PATH.relative_to(ROOT)}")
    print(f"Wrote {MARKDOWN_PATH.relative_to(ROOT)}")
    print(f"Wrote {SOURCE_FIXTURE_PATH.relative_to(ROOT)}")
    print(f"Wrote {(OUTPUT_DIR / 'paper-t-rail-source-fixture.md').relative_to(ROOT)}")


if __name__ == "__main__":
    main()
