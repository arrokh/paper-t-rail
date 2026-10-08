# Preliminary technical capability spike: Docling metadata for cited PDFs (#91)

**Prepared:** 2026-10-08

**Status:** Preliminary feasibility note; does not satisfy or complete issue #91.

## Summary

The current adapter sends PDFs to Docling for Markdown (`md_content`) and turns that Markdown into normalized sections; its current contract test mocks the Docling response. A coordinator-run local test establishes that the configured Docling Serve v1.30.0 accepted `to_formats=json` and returned a generic DoclingDocument for one synthetic Source Document fixture. Neither that fixture nor the official response schema establishes a tested, reliable bibliographic identity contract for Cited Papers (title, authors, DOI/other identifiers, provenance, or missing-field behavior). The available evidence is enough to justify a focused follow-up investigation, not an implementation or OCR-support claim.

## Findings

1. **Repository-observed: the adapter currently extracts sections, not identity metadata.** `DoclingCitedPaperPdfParser` posts multipart data to `/v1/convert/file` with `from_formats=pdf` and `to_formats=md`; it requires a successful response and nonblank `document.md_content`, normalizes Markdown headings/paragraphs into sections, and returns empty citation-context and bibliography-entry lists. It does not request or parse `json_content`. Its configured parser version defaults to `1.30.0`. [Parser source](../../api/src/main/kotlin/com/papertrail/api/external/docling/DoclingCitedPaperPdfParser.kt)

2. **Repository-observed: current parser contract coverage is mocked.** The contract test uses `MockRestServiceServer` to supply Markdown responses. It verifies Markdown normalization and rejection of a partial result or oversized response; it does not call a Docling service or verify JSON output or metadata extraction. [Contract test](../../api/src/test/kotlin/com/papertrail/api/evidence/parsing/DoclingCitedPaperPdfParserContractTest.kt)

3. **Repository-observed: the PDFBox gate matters.** `PdfBoxCitedPaperTextExtractor` uses PDFBox `PDFTextStripper` for PDFs and rejects blank extracted text before returning. Parent issue #85 explicitly records that the current acquisition path extracts with PDFBox before accepting a PDF, so a PDF without extractable text can be rejected before Docling. This is a gate in the existing path, not evidence that OCR cannot work in Docling or that it can work end to end. [Extractor source](../../api/src/main/kotlin/com/papertrail/api/scholarly/acquisition/service/PdfBoxCitedPaperTextExtractor.kt) · [Parent issue #85](https://github.com/arrokh/paper-t-rail/issues/85)

4. **Repository-observed: local defaults are versioned/configurable, not a complete option fingerprint.** `application.yml` defaults the cited-paper parser ID to `docling`, version to `1.30.0`, base URL to `http://127.0.0.1:5001`, response limit to 64 MiB and timeout to 600,000 ms. Compose defaults to `ghcr.io/docling-project/docling-serve-cpu:v1.30.0`, enables API docs, and configures page/file-size limits; these settings can be overridden through environment variables. The adapter currently sends only the two format fields beyond the file itself, so the repository does not yet capture a complete effective Docling option set as a tested parser contract. [Application config](../../api/src/main/resources/application.yml) · [Compose](../../infra/docker-compose.yml)

5. **Coordinator-observed local test (one fixture only; not independently rerun by this researcher).** Inside the existing local trusted container, image `ghcr.io/docling-project/docling-serve-cpu:v1.30.0` accepted `to_formats=json`; its live `/openapi.json` exposed `/v1/convert/file` and `ConvertDocumentResponse.document -> ExportDocumentResponse`, including `md_content`, `text_content`, and generic `json_content -> DoclingDocument` (`additionalProperties: true`). The checked-in synthetic Source Document QA PDF converted successfully. The JSON had top-level keys `body, form_items, furniture, groups, key_value_items, name, origin, pages, pictures, schema_name, tables, texts, version`; `origin` had `binary_hash, filename, mimetype, uri`. It represented 4 pages, 67 text nodes (7 page headers, 14 section headers, 32 text, 14 page footers), 4 pictures and zero tables, groups, key-value items or form items. No explicit title/author/DOI fields appeared in this response structure. No document text is reproduced in this note. The QA README describes this PDF as a synthetic, English, selectable-text **Source Document** fixture—not a Cited Paper. Therefore this is evidence only that the JSON export path works for this one fixture; it is not a bibliographic-extraction test or broad capability result. [QA README](../qa/README.md)

6. **Official Docling Serve/API evidence: JSON is a supported export, not a bibliographic extractor contract.** The v1.30.0 usage docs describe `POST /v1/convert/file` as a multipart endpoint and list `json` as a `to_formats` output. In the matching versioned Docling response model, `ConvertDocumentResponse.document` is an `ExportDocumentResponse`; that export includes `md_content`, `text_content`, and `json_content: DoclingDocument`. The v2.91.0 DoclingDocument root model lists document-structure fields such as `name`, `origin`, `body`, `texts`, `groups`, `pictures`, `tables`, and `pages`, not dedicated top-level bibliographic title, author, or DOI fields. **Interpretation:** JSON provides structured document/layout content, but the declared shape does not by itself promise normalized bibliographic identity fields. A title-like text node or content from which identity might be extracted is not equivalent to a validated metadata field with provenance and missing-value semantics.

7. **OCR remains unproven.** The versioned API options document `do_ocr`, `force_ocr`, OCR preset/language options and their meanings/defaults. That is evidence of API options only; neither the coordinator test nor the repository test exercised OCR, scanned input, or an OCR-assisted path through the PDFBox gate. Do not infer OCR support, quality, or eligibility from the options or defaults. Chapter-versus-book boundaries and OCR/scanned-file behavior remain explicit approval gates in #91.

## What remains unknown

- Whether JSON output from representative Cited Papers exposes dependable title, author set, DOI/other identifiers or version/chapter evidence, and where those values are grounded in pages/nodes.
- How to represent absent, ambiguous, malformed, conflicting, or partial metadata without inventing values, and what evidence/provenance is sufficient for a machine validation versus human confirmation.
- Whether whole books, chapters, edited volumes and alternate versions can be distinguished safely; this is not established by the Source Document fixture.
- Whether a controlled scanned or mixed-content PDF can pass the complete product path, what OCR engine/preset is actually available in the deployed image, and how language/quality gates should behave. No OCR conclusion is made here.
- Whether validated parses can be safely reused under identical bytes, service/parser version, effective options, normalization and limits; no compatible-parse reuse contract is demonstrated.

## Follow-up needed to meet #91

1. **Agree the extraction contract and policy first.** Define supported output fields and types, accepted evidence/provenance for each extracted value, explicit missing/ambiguous/conflicting-field semantics, and pinned service version plus effective request options. Do not treat Markdown string matching or a generic document `name` as a title/identity contract without independent evaluation.
2. **Use controlled, ground-truthed Cited Paper fixtures.** Include known article metadata and identifiers, missing/partial and malformed metadata, title/author/identifier conflicts, multiple disciplines, same-work alternate versions, and controlled book/chapter examples. Include selectable-text, mixed-content, encrypted, partial/unreadable, and scanned PDFs only under controlled conditions; keep OCR fixtures and outcomes separate until that behavior is approved. Record expected metadata and source locations independently of the parser output. Use synthetic or appropriately permitted fixtures; do not use private/user PDFs.
3. **Add real-service behavioral contract tests.** Run against the pinned local image rather than only mocks; request JSON explicitly and assert response status/schema handling, field extraction and provenance, and each missing/conflict outcome. Test that unsupported fields are never fabricated, and pin the content hash, Docling/adapter versions, options, limits and normalization. Keep mocked tests for transport/error cases, but they cannot substitute for real fixture evidence.
4. **Test workflow safety separately from upload validity.** Exercise distinct Validated / Needs confirmation / Mismatch decisions, human confirmation separate from machine confidence, and clear wrong-work mismatch blocking selection. Verify one exact version per reference, replacement/reuse decisions per entry, and no cross-version passage merging or evidence assessment during validation. Verify automatic and supplied acquisition cannot bypass wrong-work or language/integrity blockers.
5. **Resolve gates before implementation claims.** Agree the chapter/book policy and scanned/OCR behavior as #91 requires; specifically decide whether/how the existing PDFBox nonblank-text gate is aligned with Docling. Add public API/OpenAPI, persistence/migration, UI, deletion/privacy and compatibility tests for any implementation scope. A feasibility observation alone does not satisfy these acceptance criteria.

## Issue status and scope

Issue #91 is **OPEN**, an S6 design-gated issue, and its acceptance criteria remain unchecked. Its text lists #89 (S4) and #90 (S5) under “Blocked by.” Issue #89 is **OPEN**. Issue #90 is **CLOSED (COMPLETED)**, closed 2026-10-08 by PR #101. Parent #85 says S6 depends on S4/S5 but that the feasibility spike may start earlier. This note is that bounded preliminary investigation only: it does **not** claim #91 complete, remove or alter dependencies, approve the chapter/book or OCR gates, or unblock implementation.

No private/user PDFs, credentials, paid service, or non-local external transfer were used for the coordinator’s observation. No code, issue, label or PR changes are part of this note.

## Sources

### Repository observations

- [DoclingCitedPaperPdfParser.kt](../../api/src/main/kotlin/com/papertrail/api/external/docling/DoclingCitedPaperPdfParser.kt) — current request and Markdown normalization.
- [PdfBoxCitedPaperTextExtractor.kt](../../api/src/main/kotlin/com/papertrail/api/scholarly/acquisition/service/PdfBoxCitedPaperTextExtractor.kt) — PDF extraction and nonblank guard.
- [DoclingCitedPaperPdfParserContractTest.kt](../../api/src/test/kotlin/com/papertrail/api/evidence/parsing/DoclingCitedPaperPdfParserContractTest.kt) — mocked contract coverage.
- [application.yml](../../api/src/main/resources/application.yml), [docker-compose.yml](../../infra/docker-compose.yml) — local defaults and service configuration.
- [QA README](../qa/README.md) — checked-in fixture classification.

### Official issues and versioned Docling sources

All web sources below accessed 2026-10-08.

- [Issue #91 — Validate supplied cited PDFs with Docling and select exact versions](https://github.com/arrokh/paper-t-rail/issues/91) — current scope, acceptance gates and dependency text.
- [Issue #85 — Improve reference identity and recover cited full text](https://github.com/arrokh/paper-t-rail/issues/85) — parent requirements, pre-Docling gate and early-spike permission.
- [Issue #89 — S4 reference identity](https://github.com/arrokh/paper-t-rail/issues/89) — status and dependency context.
- [Issue #90 — S5 staged PDF uploads](https://github.com/arrokh/paper-t-rail/issues/90) — closed/completed status.
- [Docling Serve v1.30.0 usage docs](https://raw.githubusercontent.com/docling-project/docling-serve/v1.30.0/docs/usage.md) — file endpoint, `to_formats=json`, and documented request options.
- [Docling Serve v1.30.0 app source](https://raw.githubusercontent.com/docling-project/docling-serve/v1.30.0/docling_serve/app.py) — versioned endpoint implementation/OpenAPI setup.
- [Docling v2.118.0 response model](https://raw.githubusercontent.com/docling-project/docling/v2.118.0/docling/datamodel/service/responses.py) — `ConvertDocumentResponse` and `ExportDocumentResponse` fields.
- [Docling v2.118.0 conversion options](https://raw.githubusercontent.com/docling-project/docling/v2.118.0/docling/datamodel/service/options.py) — output and OCR option definitions; not evidence of OCR performance or availability.
- [Docling Core v2.91.0 DoclingDocument model](https://raw.githubusercontent.com/docling-project/docling-core/v2.91.0/docling_core/types/doc/document.py) — typed document structure.
- [Docling Serve v1.30.0 release](https://github.com/docling-project/docling-serve/releases/tag/v1.30.0) — version alignment for Docling Serve 1.30.0, Docling 2.118.0 and Docling Core 2.91.0.

A source-check call returned “unclear” because automated semantic assessment was unavailable; the versioned source passages above were then fetched and inspected manually. No contradictory evidence was found; important gaps are documented above.
