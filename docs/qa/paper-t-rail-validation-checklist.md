# Paper T-Rail V1 Validation Workbook

**Repository snapshot:** 2026-10-04

# V1 Validation Workbook

A practical test plan for Paper T-Rail | Repository snapshot: 2026-10-04

> **PURPOSE:** Use this workbook to validate the documented V1 user journey and its edge cases: upload an academic PDF, follow its Analysis Run, inspect citation-backed Atomic Claims and Claim-Paper Verifications, record a separate Human Review, re-analyze, and delete.

## How to run the checks

Run the end-to-end path first with recorded/local providers. For deterministic semantic outcomes, use a controlled provider fixture in an integration test. Live Laya output is explicitly uncalibrated; do not require it to match a gold status on each run.

For every case, mark PASS, FAIL, BLOCKED, or N/A. Record the build/commit, environment, Analysis Run ID (when one exists), case ID, expected and observed behavior, and a safe evidence link. Do not copy Source Document, claim, or Evidence Passage text into logs or issue comments.

## V1 workflow and coverage

01 Read the PDF -> 02 Resolve references -> 03 Acquire cited sources -> 04 Prepare evidence -> 05 Assess evidence -> Evidence Coverage Report. Human Review is a separate, append-only action; it does not rewrite machine results.

> **CLAIM EXPECTATION FOOTER:** Every page footer summarizes the expected claim/citation/result behavior for that page. Case-level expectations below are the detailed oracle. A Claim-Paper Verification is an inferred triage result for one Atomic Claim x Cited Reference, not proof that the claim is true.

## Keep these distinctions

Passage judgements (DIRECT_SUPPORT, PARTIAL_SUPPORT, CONTRADICTS, UNRELATED, INSUFFICIENT) are not final statuses. Final statuses are SUPPORTED, PARTIALLY_SUPPORTED, CONTRADICTED, INSUFFICIENT_EVIDENCE, INACCESSIBLE, UNRESOLVED, and UNSUPPORTED_REFERENCE_TYPE.

Processing failure is not INSUFFICIENT_EVIDENCE. PARSED is an intermediate state, not a completed Evidence Coverage Report. Any Laya judgement or aggregated status must remain labeled uncalibrated.

V1 scope: English, text-based academic Source Documents; citation-backed claims; scholarly cited works; legally accessible evidence. OCR, scanned Source Documents, non-English Source Documents, paywall bypass, and truth certification are not supported.

> **Claim expectation footer:** The claim oracle on each page states the expected system behavior. It is not a certification of a research claim.

---

# 01 | Upload and Source Document

Validate PDF preflight, clear failure behavior, and safe limits.

### [ ] U-01 - Supported academic PDF
- **Action:** Upload an English, text-based journal article or thesis with selectable text, body citations, and a bibliography.
- **Expected:** The Source Document is accepted; a new Analysis Run starts. The run pins the source hash, page count, validation/parser provenance, and configuration.

### [ ] U-02 - Empty, wrong type, wrong filename, or invalid signature
- **Action:** Try a zero-byte file, a non-PDF MIME type, a filename without .pdf, and non-PDF bytes renamed .pdf.
- **Expected:** Each request is rejected with a clear validation error (for example EMPTY_UPLOAD, UNSUPPORTED_CONTENT_TYPE, PDF_FILENAME_REQUIRED, INVALID_PDF_SIGNATURE). No partial document or run is created.

### [ ] U-03 - Corrupt or encrypted PDF
- **Action:** Upload a damaged PDF and a password-protected PDF.
- **Expected:** Reject with a clear parse/password error; do not queue a run or retain partial parse output.

### [ ] U-04 - Scanned pages and insufficient text
- **Action:** Compare a scan-only PDF with a text PDF below the configured minimum extracted-text threshold.
- **Expected:** The scan is rejected as SCANNED_PDF_UNSUPPORTED (OCR is out of scope). Other low-text PDFs fail with PDF_HAS_INSUFFICIENT_TEXT.

### [ ] U-05 - Unsupported or uncertain source language
- **Action:** Upload a non-English document and an English-looking document whose language confidence is below the configured minimum.
- **Expected:** Both are rejected with UNSUPPORTED_LANGUAGE. No Analysis Run is queued.

### [ ] U-06 - Byte, page, and extracted-text limits
- **Action:** Check exactly-at and one-over boundaries for the configured byte, page, total-character, and per-page-character limits. Defaults: 50 MiB, 500 pages, 5,000,000 total chars, 100,000 chars/page.
- **Expected:** At-limit inputs are accepted when otherwise valid. Over-limit inputs fail with the corresponding error (UPLOAD_TOO_LARGE, PDF_TOO_MANY_PAGES, PDF_TEXT_TOO_LARGE); no content is truncated.

### [ ] U-07 - Claim-Citation pair cap
- **Action:** Exercise exactly 5,000 claim-to-target links, then 5,001 (or use a lower test configuration).
- **Expected:** The exact cap succeeds. Above the cap, the run fails with CLAIM_CITATION_PAIR_LIMIT_EXCEEDED before parsed output is persisted; there is no partial report.

### [ ] U-08 - PDF geometry and text fidelity
- **Action:** Use a multi-page PDF with columns, ligatures, Unicode punctuation, repeated headers/footers, and citations near line/page breaks. Open each extracted claim with Show in PDF.
- **Expected:** Displayed claim text and citation marker point to the correct source span/page. Page geometry does not shift the highlight to neighboring citations.

> **Claim expectation footer:** CLAIM ORACLE: valid input creates a run; invalid input is rejected clearly; limits fail closed and never silently truncate.

---

# 02 | Citation Contexts and Atomic Claims

Check extracted claim text, source spans, context boundaries, and inferred links.

### [ ] C-01 - Single citation-backed proposition
- **Action:** Use: 'The intervention improved mobility [1].' Inspect the claim and source offsets.
- **Expected:** One Atomic Claim is extracted; the marker is not part of its claim text. Its source span maps back to the exact proposition.

### [ ] C-02 - Independent coordinated predicates with a shared qualifier
- **Action:** Use: 'Among older adults, treatment reduced pain and improved mobility [1, 2].'
- **Expected:** Two Atomic Claims are extracted. Both retain 'Among older adults'; each claim links to both targets in this context. The links are labeled inferred/provisional.

### [ ] C-03 - Ambiguous negation
- **Action:** Use: 'Treatment did not improve symptoms and reduce dropout [1].'
- **Expected:** Keep one conservative claim because splitting could change the scope of 'not'. Preserve the negation; do not silently rewrite it.

### [ ] C-04 - Ambiguous trailing qualifier
- **Action:** Use: 'Treatment reduced pain in older adults and improved mobility [1].'
- **Expected:** Keep the coordination together when the population qualifier could scope over only one predicate. Do not guess which predicate it modifies.

### [ ] C-05 - Multiple markers in one context
- **Action:** Use: 'Prior work supports this method [1] and reports similar outcomes [2].'
- **Expected:** Markers share the same Citation Context under the current conservative segmentation rule. Claims in that context link to all its targets; the report discloses that the association is inferred.

### [ ] C-06 - Separate clauses and targets
- **Action:** Use: 'The treatment improved symptoms [1]; however, controls found no effect [2].' Repeat with 'whereas' or 'although'.
- **Expected:** Separate citation-bearing clauses become separate Citation Contexts. The first claim links to [1] only; the second links to [2] only.

### [ ] C-07 - Ambiguous clause boundary
- **Action:** Use: 'Prior work supports the method [1], which remains under discussion [2].'
- **Expected:** Use the containing sentence as SENTENCE_FALLBACK when the clause boundary is not reliable. Do not infer a narrower target assignment.

### [ ] C-08 - Duplicate wording and uncited text
- **Action:** Repeat an identical cited sentence at two distinct source locations; include an uncited claim elsewhere.
- **Expected:** The two distinct spans remain separate claims; only a duplicate with the same source span is deduplicated within a run. The uncited proposition is not included in citation-backed verification.

### [ ] C-09 - Citation marker / bibliography edge cases
- **Action:** Try grouped numeric citations, author-date citations, an unmatched callout, a repeated marker, and an unused bibliography heading.
- **Expected:** Persist only parser-observed occurrences and target links. Never invent a target; preserve source spans and exclude heading-only bibliography artifacts from structured entries.

> **Claim expectation footer:** CLAIM ORACLE: C-02 splits into two with qualifiers; ambiguous negation/scope stays together; targets never cross Citation Contexts.

---

# 03 | Reference Resolution

Verify conservative identity matching and separate outcomes per Cited Reference.

### [ ] R-01 - Exact DOI match
- **Action:** Resolve a bibliography entry with a valid DOI against metadata carrying that exact normalized DOI.
- **Expected:** The entry resolves to that Canonical Paper. A DOI lookup returning a different DOI must not resolve it.

### [ ] R-02 - Ambiguous or below-threshold metadata
- **Action:** Use a title-only reference with close competing matches, missing fields, or a score below the run-pinned threshold.
- **Expected:** Record UNRESOLVED rather than selecting the highest-scoring guess. Preserve the resolution reason and policy/threshold snapshot.

### [ ] R-03 - Unsupported reference type
- **Action:** Cite a WEBSITE/OTHER entry (for example, a website, standard, or news story) with no supported scholarly type.
- **Expected:** The entry is UNSUPPORTED_REFERENCE_TYPE before metadata lookup; no cited-source acquisition or System One work follows.

### [ ] R-04 - One marker with multiple targets
- **Action:** Link a claim to one resolved and one unresolved Cited Reference in the same Citation Context.
- **Expected:** Create a separate outcome for each Atomic Claim x Cited Reference. One resolved source does not hide, replace, or resolve the other.

> **Claim expectation footer:** CLAIM ORACLE: exact DOI can resolve; mismatch or ambiguity stays UNRESOLVED; each claim-reference pair keeps its own outcome.

---

# 04 | Cited-Paper Access

Verify legal acquisition, language gating, and conservative abstention.

### [ ] A-01 - Legal full text and exact asset
- **Action:** Acquire an allowed HTTPS PDF or text location with an explicit CC0, CC BY, or public-domain license.
- **Expected:** Persist legal-location/access provenance and the acquired asset hash. Stage 04 uses that exact run-pinned asset, parser, and language provenance.

### [ ] A-02 - Unsafe or unlicensed locations
- **Action:** Supply an unknown/restrictive license, paywall, private host, redirect, URL credentials, query string, or fragment.
- **Expected:** Reject the location before fetching. Never infer permission from public reachability; never bypass a paywall.

### [ ] A-03 - Fetch failure and location fallback
- **Action:** Make the first permitted location fail, then make a later permitted location succeed; repeat with every fetch failing.
- **Expected:** Try the next legal location. If none works, preserve known metadata/abstract and expose FULL_TEXT_ACQUISITION_FAILED; do not erase known access.

### [ ] A-04 - Abstract-only access
- **Action:** Resolve a paper with an abstract but no legally accessible full text.
- **Expected:** Final status INSUFFICIENT_EVIDENCE, scope ABSTRACT_ONLY. No embedding, retrieval, or semantic-verifier call uses the abstract.

### [ ] A-05 - Metadata-only or unavailable
- **Action:** Resolve a paper with metadata but no abstract/full text, then a paper with no usable access at all.
- **Expected:** A resolved reference with neither legal full text nor abstract is INACCESSIBLE. Keep it distinct from UNRESOLVED.

### [ ] A-06 - Accessible non-English cited full text
- **Action:** Acquire a non-English Cited Paper while the Source Document remains English.
- **Expected:** INSUFFICIENT_EVIDENCE with LANGUAGE_UNSUPPORTED and verification scope NONE. Do not embed or send the text to System One.

> **Claim expectation footer:** CLAIM ORACLE: abstract or unsupported language -> INSUFFICIENT_EVIDENCE; no legal full text or abstract -> INACCESSIBLE; unsafe sources are not fetched.

---

# 05 | Evidence and Claim-Paper Outcomes

Separate retrieval, passage judgement, aggregation, and operational failure.

### [ ] E-01 - Retrieval stays inside the cited work
- **Action:** Give two cited papers distinct sentinel passages; query each claim-reference pair.
- **Expected:** Every Evidence Passage comes only from the exact Cited Paper Asset for that reference. No passage leaks across references, runs, or newer assets.

### [ ] E-02 - Candidate passage is not a judgement
- **Action:** Inspect the retrieved top passages and their ranks before and after System One runs.
- **Expected:** Retrieval exposes candidates and provenance only. Ranking, similarity, or a top result does not itself imply support or a final status.

### [ ] E-03 - Evidence role and source context
- **Action:** Test a paper's own finding, author synthesis/review, and a secondary report of another study.
- **Expected:** Expose Evidence Role and section. Do not present a secondary mention as direct evidence for the underlying primary study's result.

### [ ] E-04 - Strong direct support
- **Action:** Feed a controlled DIRECT_SUPPORT judgement with strong scope/directness and no comparable contradiction.
- **Expected:** The deterministic aggregation policy yields SUPPORTED. Show the exact passage and uncalibrated judgement/provenance.

### [ ] E-05 - Partial support or strong contradiction
- **Action:** In separate controlled cases, supply partial support without stronger contradiction, then strong contradiction without comparable support.
- **Expected:** Outcomes are PARTIALLY_SUPPORTED and CONTRADICTED respectively. Material population, condition, scope, or outcome mismatch remains visible.

### [ ] E-06 - Comparable support and contradiction
- **Action:** Provide comparable credible passages on both sides for the same claim and Cited Reference.
- **Expected:** Yield INSUFFICIENT_EVIDENCE with evidenceConflict=true and expose both sides. Do not let raw model confidence alone decide.

### [ ] E-07 - No qualifying evidence / unrelated passages
- **Action:** Use empty retrieval, unrelated passages, or controlled INSUFFICIENT judgements.
- **Expected:** A successfully completed assessment with no qualifying support is INSUFFICIENT_EVIDENCE. UNRELATED remains a passage judgement, not a final status.

### [ ] E-08 - Unresolved, unsupported, inaccessible, abstract, language
- **Action:** Exercise each access/resolution outcome from pages 3 and 4.
- **Expected:** Final status stays distinct: UNRESOLVED, UNSUPPORTED_REFERENCE_TYPE, INACCESSIBLE, or INSUFFICIENT_EVIDENCE with its scope/reason. Do not collapse them into one bucket.

### [ ] E-09 - Provider/parser/indexing failure
- **Action:** Force a transient timeout, malformed provider response, parser failure, and deterministic provider rejection.
- **Expected:** Record an incomplete/failed pair and stable reason; never fabricate a judgement or disguise a processing failure as INSUFFICIENT_EVIDENCE. Partial success may complete with warnings.

### [ ] E-10 - System One context limit
- **Action:** Use an over-limit passage, including one with an unfit single sentence.
- **Expected:** Do not truncate. Eligible passages use diagnostic child spans under the unchanged parent; failed/missing spans remain incomplete and cannot create a separate final verification.

> **Claim expectation footer:** CLAIM ORACLE: direct -> SUPPORTED; partial -> PARTIALLY_SUPPORTED; contradiction -> CONTRADICTED; comparable conflict -> INSUFFICIENT + conflict; failures stay incomplete.

---

# 06 | Report, Human Review, and Lifecycle

Validate the visible report and keep human assessment separate from machine output.

### [ ] H-01 - Coverage summary and drill-down
- **Action:** Open a completed Analysis Run with multiple claims, references, outcomes, and one incomplete pair.
- **Expected:** Summary counts use the seven domain statuses and distinguish completed/incomplete/conflict work. Drill-down traces claim -> Citation Context/Marker -> Cited Reference -> exact Evidence Passage and provenance.

### [ ] H-02 - Source-document highlight
- **Action:** Select a claim and citation; use Show in PDF on pages with duplicate wording, columns, and nearby unrelated markers.
- **Expected:** Highlight the selected source span and marker only; page navigation and PDF search retain the correct position.

### [ ] H-03 - Pipeline progress and PARSED state
- **Action:** Inspect a run with final verification disabled and another with the full pipeline enabled.
- **Expected:** Stage summaries match persisted work items. PARSED is not presented as a completed Evidence Coverage Report; skipped, failed, warning, and completed states remain distinct.

### [ ] H-04 - Triage disclaimer and calibration language
- **Action:** Inspect summary, passage judgements, final statuses, and provider details.
- **Expected:** Report says triage aid, not truth certification or whole-paper grade. Laya judgements/statuses are visibly uncalibrated; no accuracy claim is implied.

### [ ] H-05 - Agree and disagree review
- **Action:** Record AGREE and DISAGREE for a completed machine verification without an override status.
- **Expected:** Both reviews are accepted and appended. Machine finalStatus is unchanged; review action/history is shown separately.

### [ ] H-06 - Override review validation
- **Action:** Submit OVERRIDE with and without overrideStatus; submit AGREE/DISAGREE with an overrideStatus; test notes at 2,000 and 2,001 characters.
- **Expected:** OVERRIDE requires a valid separate status. AGREE/DISAGREE omit it. Overlong note is rejected. Only a completed verification with a machine status can be reviewed.

### [ ] H-07 - Review append-only behavior
- **Action:** Record a second review that disagrees with the first; reload the Analysis Run.
- **Expected:** Both records remain in history in order. Neither review rewrites nor hides the machine result; reviews are removed only by explicit document deletion.

### [ ] H-08 - Re-analysis creates a new run
- **Action:** Re-analyze the same Source Document with a changed provider or profile.
- **Expected:** A new immutable Analysis Run is created with its own pinned configuration/provenance. Earlier results and reviews remain unchanged.

### [ ] H-09 - Delete document during and after processing
- **Action:** Delete an idle document, then repeat while worker work is pending/in flight; inspect related runs and data.
- **Expected:** Deletion tombstones first, invalidates pending work, removes document-scoped source/derived data and Human Reviews, and prevents workers from resurrecting it. Shared assets remain only while referenced elsewhere.

> **Claim expectation footer:** CLAIM ORACLE: report traces each Atomic Claim x Cited Reference; Human Review is append-only and never mutates the machine finalStatus.

---

# 07 | Providers, Privacy, and Reliability

Check consent, provenance, idempotency, safe logging, and deployment boundaries.

### [ ] P-01 - Provider availability is not consent
- **Action:** With recorded fixtures selected, verify no remote scholarly/OA calls. Select Crossref or Unpaywall without consent, then with exact per-run consent.
- **Expected:** No unconsented external call occurs. Consent is provider- and run-specific and names the actual data categories; enabling a provider alone is not consent.

### [ ] P-02 - Minimum payload and category boundary
- **Action:** Inspect a safe test transport for Crossref, Unpaywall, external Ollama, or System One calls.
- **Expected:** Send only documented minimum categories. Never send the full Source Document unless a reviewed provider operation explicitly requires it; never put credentials in the browser or run snapshot.

### [ ] P-03 - Retention/deletion disclosure
- **Action:** Review consent UI and deletion copy for each selected external provider.
- **Expected:** Show provider-specific categories and retention/deletion limits. Explain that local deletion cannot retract content already sent to an external provider.

### [ ] P-04 - Run-pinned provenance
- **Action:** Inspect the Analysis Run snapshot and verification for source hash/parser, extraction and resolution policy, provider selections, retrieval/embedding profile, exact cited asset hash/parser, and language detector when used.
- **Expected:** The run records the configuration actually executed, with non-secret fingerprints. Later configuration changes do not rewrite this snapshot.

### [ ] P-05 - Provider failure does not silently fall back
- **Action:** Make a selected provider fail or become unavailable after run creation.
- **Expected:** The run exposes the failure/incomplete work. It does not silently switch to a different provider or model and misstate provenance.

### [ ] P-06 - Duplicate delivery and worker restart
- **Action:** Deliver one pipeline event twice and restart a worker with pending stream work.
- **Expected:** Idempotent persistence prevents duplicate/corrupt outcomes; pending work is reclaimed and acknowledged only after its durable transaction succeeds.

### [ ] P-07 - Outbox and shared acquisition/indexing
- **Action:** Exercise a database commit/message-publish failure and concurrent runs needing the same Cited Paper asset.
- **Expected:** The transactional outbox prevents committed work from disappearing. Locks reduce duplicate expensive work; exact asset/profile boundaries remain intact.

### [ ] P-08 - Safe observability
- **Action:** Cause representative request, provider, parser, and worker errors; inspect API, worker, and sidecar logs.
- **Expected:** Do not log Source Document, claim, or evidence text, request bodies, query strings, credentials, or access tokens. Log safe request/status/error metadata only.

### [ ] P-09 - Network exposure
- **Action:** Inspect Compose bindings and deployment listener addresses for the unauthenticated workspace/API.
- **Expected:** Keep services on loopback or a trusted private network. Do not expose the V1 unauthenticated workspace/API to an untrusted network.

> **Claim expectation footer:** CLAIM ORACLE: external calls require fresh provider/run consent; historical runs keep their pins; deletion cannot retract data already sent externally.

---

# 08 | Boundaries, Sign-off, and Known Gaps

Use this page to separate a V1 failure from an explicit non-goal or open verification gap.

## V1 boundaries (not defects by themselves)

Not supported: OCR/scanned Source Documents; non-English Source Documents; books, websites, standards, and other non-scholarly Cited References as evidence sources; manual Cited Paper upload; paywall bypass; generic web crawling; automated rewriting/grading; authentication/multi-user workflows.

Optional provider availability is not a feature guarantee. LLM-based claim extraction, Semantic Scholar enrichment, Google embeddings, and hosted Jev integrations are not implemented in this repository snapshot. The runtime uses heuristic claim extraction, Crossref/recorded metadata options, local Ollama/feature-hash embeddings, and mock/Laya System One.

> **SPEC / IMPLEMENTATION TENSION:** The tech design excludes books as verifiable V1 sources, while the current ConservativeReferenceResolver lists BOOK among accepted metadata-resolution types. Use WEBSITE/OTHER to test UNSUPPORTED_REFERENCE_TYPE. Confirm the intended end-to-end book behavior with the product owner before using it as a release oracle.

## Known verification gaps in the repository documentation

The Pipeline Implementation and Feature Traceability Matrix dated 2026-10-03 identifies issue #55 (full web upload -> queued processing -> report) and issue #56 (PDF page-count boundary) as open verification gaps at that snapshot. Check current GitHub issue state before treating these as current; run and record the checks rather than inferring coverage from source tests.

## Release evidence record

### [ ] SIGN-OFF - Record one result per test case
- **Action:** For each case, record: case ID; build/commit; environment and provider selections; Analysis Run ID; PASS/FAIL/BLOCKED/N/A; expected vs observed result; safe evidence path; linked issue for any failure.
- **Expected:** All in-scope cases have an observed result. Every blocked or unrun case has a named dependency/owner. No unverified assumption is labeled PASS.

> **CLAIM STATUS QUICK REFERENCE:** SUPPORTED = strong direct full-text support; PARTIALLY_SUPPORTED = only part/narrower scope; CONTRADICTED = material conflict; INSUFFICIENT_EVIDENCE = no qualifying assessed support (also abstract-only/unsupported language); INACCESSIBLE = resolved but no legal full text or abstract; UNRESOLVED = identity not confidently matched; UNSUPPORTED_REFERENCE_TYPE = outside V1 source types.

Basis: CONTEXT.md; docs/paper-t-rail-tech-design.md; docs/adr/0001-0009; docs/pipeline-feature-matrix.md; docs/benchmarks/v1-runtime-matrix.md; docs/agents/provider-matrix.md. This checklist covers documented V1 product behavior, not every mathematically possible PDF or every scholarly domain.

> **Claim expectation footer:** CLAIM ORACLE: test documented V1 behavior; mark non-goals N/A; record every failed or unverified outcome instead of assuming it passed.

---
