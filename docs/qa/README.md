# Paper T-Rail QA PDFs

- **`paper-t-rail-source-fixture.pdf`** is the English, selectable-text synthetic Source Document to upload in a local/test environment. Its page footers show expected claim-extraction and reference-resolution behavior. The bibliography page omits unique footer text so the parser does not mistake the oracle for another reference. Bibliography entry `[1]` matches the checked-in recorded scholarly-work/full-text fixture; `[2]` is deliberately unmatched; `[3]` is a website-style reference for inspecting how GROBID classifies non-scholarly entries. Confirm its parsed type before expecting an unsupported-reference outcome. The PDF does not promise a live semantic model status.
- **`paper-t-rail-validation-checklist.pdf`** is the broader manual/system QA workbook. It covers upload limits, parsing, resolution and access, evidence outcomes, Human Review, provider consent, reliability, privacy, and V1 boundaries. It is a checklist, not an academic Source Document to upload.

The footers are claim-expectation oracles, not claims about research truth. Retrieved Evidence Passages are candidates, and Laya judgements/final statuses remain uncalibrated. Use controlled provider fixtures for deterministic semantic-status tests.

Editable Markdown sources are beside the PDFs. Rebuild all four artifacts offline with:

```sh
python3 scripts/generate_paper_t_rail_validation_pdf.py
```

The checklist records a product/spec tension: the tech design excludes books as verifiable V1 sources, while the current reference resolver accepts `BOOK` for metadata resolution. Confirm intended end-to-end behavior before using a book citation as a release oracle.
