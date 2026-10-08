# English Reference Resolution and Access — Diagnostic Baseline

**Status:** Exploratory audit; not a human-adjudicated accuracy evaluation. This report excludes Source Document titles, bibliography text, claims, evidence text, and raw provider payloads.

## Purpose

Establish what the observed Analysis Run can prove about extraction, identity resolution, and cited-full-text access. Use the result to prioritize follow-up work, not to certify the resolver or choose a new score threshold.

## Provenance and permitted data

- The diagnostic case is the user-supplied, completed local Analysis Run **`26794b47-cff2-43a3-9c67-1fb225c0f6bd`**. This opaque run UUID identifies the local immutable record; the Source Document ID, title, hash, PDF, TEI, and raw bibliography are not published or copied into this repository. The audit read the local API report, safe execution metadata, parsed-entry field summaries, and persisted GROBID TEI.
- The checked-in [`paper-t-rail-source-fixture.pdf`](../qa/paper-t-rail-source-fixture.pdf) and its Markdown source are explicitly synthetic and intended for local/test use. They contain one recorded-fixture journal match, one deliberately unmatched journal reference, and one website-style entry. They are permission-safe deterministic behavior fixtures, not scholarly-accuracy evidence.
- Existing [resolver unit tests](../../api/src/test/kotlin/com/papertrail/api/scholarly/references/resolver/ConservativeReferenceResolverTest.kt) use synthetic metadata and exercise DOI, journal, book, preprint, unsupported-type, ambiguous, and below-threshold behavior. They do not estimate real-provider performance.
- The run completed with execution recording `COMPLETE`; source parsing used GROBID `0.9.1-crf`, cited-paper parsing used Docling `1.30.0`, reference matching used `title-author-year-weighted-edit-similarity-v1` at `0.25`, and the configured providers were Crossref and Unpaywall. No new scholarly-provider calls or credential use were made. Generic public searches inspected dataset descriptions and license pages because the repository lacked a broad evaluation corpus; those queries contained no run ID, source/claim/evidence content, bibliography text, or credentials. No corpus rows, dataset samples, PDFs, or archives were downloaded or added to the repository. Provider results below were already persisted by the Analysis Run under its own configuration/consent.

## Public benchmark triage

A generic public search for permission-compatible citation datasets (pages inspected on **2026-10-08**) found no ready-to-use all-discipline, human-adjudicated identity-and-asset set:

- [RenoBench](https://huggingface.co/datasets/public-knowledge-project/ref-annotation-benchmark/blob/e70e817e60da2dfe1a837c1c5f9a1b2ac518ecd2/README.md) advertises 10,000 citation-string/JATS annotation pairs under CDLA-Permissive-1.0 and data from four publishing platforms. Its card reports multilingual data, with 32% English, and publication-type groups of journal articles, books, webpages, theses, and conference proceedings. It supports citation-field parsing research, but has no stated book-chapter stratum; the annotations are paired to publisher XML through automated normalized-edit-distance matching, not human-adjudicated Canonical Paper and exact-asset labels. It is not used here as identity ground truth.
- The [Zenodo bibliometric reference-matching dataset](https://zenodo.org/records/19471288) has no reuse license specified on the record page and is limited to 1,064 English journal articles selected by a bibliometrics/science-mapping query. It is excluded on both permission and subgroup-coverage grounds.
- The [GROBID evaluation corpus](https://huggingface.co/datasets/sciencialab/grobid-evaluation) is a useful extraction benchmark, but its listed source collections are biomedical article collections; it does not establish all-discipline coverage or cited-asset identity labels.

These sources were inspected only at their public descriptions; no records were fetched. RenoBench may be considered later as a supplementary parser benchmark after rights and annotation review. It does not close this issue's need for a paper-disjoint, human-adjudicated identity/asset sample spanning the requested disciplines and reference types.

## Run-level results

The run parsed **47** bibliography entries and completed all reference-resolution work. It used `title-author-year-weighted-edit-similarity-v1`, threshold **0.25**, with Crossref as its pinned metadata provider.

| Pipeline stage | Items | Completed | Skipped | Failed |
|---|---:|---:|---:|---:|
| Source parse | 1 | 1 | 0 | 0 |
| Reference resolution | 47 | 47 | 0 | 0 |
| Cited-paper access | 47 | 33 | 14 | 0 |
| Evidence indexing | 47 | 6 | 41 | 0 |
| Verification | 47 | 6 | 41 | 0 |

The 37 report outcomes are Claim–Reference pairs, not reference-stage items: **20 `INACCESSIBLE`**, **11 `UNRESOLVED`**, and **6 `INSUFFICIENT_EVIDENCE`**. There are no supported, partially supported, or contradicted outcomes. The 14 access skips are the same unresolved entries that lack a resolved identity; they are not 14 additional acquisition failures.

| Reference outcome | Count | Interpretation |
|---|---:|---|
| Resolved by metadata match | 32 | Candidate selected; not independently proven correct |
| DOI confirmed | 1 | DOI lookup confirmed the same DOI |
| Unresolved: ambiguous | 10 | Top candidates were too close |
| Unresolved: below threshold | 2 | Best candidate was below the run threshold |
| Unresolved: insufficient metadata | 2 | Entry did not have enough comparable fields |
| Unsupported / failed / not attempted | 0 | No entry had one of these terminal outcomes |

| Extracted reference type | Total | Resolved | Unresolved |
|---|---:|---:|---:|
| Preprint | 20 | 13 | 7 |
| Book | 19 | 14 | 5 |
| Journal article | 5 | 3 | 2 |
| Conference paper | 3 | 3 | 0 |
| Book chapter | 0 | 0 | 0 |

The observed source is English-language AI/CS and planning-focused literature, with some adjacent robotics and cognitive-science references. This is a useful diagnostic slice, **not** a representative sample of all disciplines. The current local synthetic QA fixture adds format checks, not disciplinary breadth. No chapter fixture or held-out cross-discipline corpus was available in the inspected repository.

## Extraction findings

The run contains 47 persisted bibliography entries. Its saved GROBID TEI contains one `listBibl` with **47 `biblStruct` children, all with identifiers**. There is one additional unkeyed `biblStruct` outside `listBibl`; the adapter's bibliography reader does not include it in the 47 persisted entries. The entries at opaque local keys `b45` and `b46` are children of that `listBibl`, but each has no author or date and carries unusually long, digit-heavy, decimal-heavy content. This is strong evidence that the table-like rows were already emitted by GROBID as bibliography structures; the adapter's `GrobidTeiParser` maps `biblStruct`/`bibl` children and did not invent those structures during title normalization.

The run's parser trace deliberately omits the raw GROBID response, while a separate local raw-TEI object allowed this structural comparison. The original page/layout relation is not retained in the parsed-entry view, so this audit cannot establish whether the source layout itself caused the model error or which GROBID layout decision did so. The adapter behavior is visible in [`GrobidTeiParser.kt`](../../api/src/main/kotlin/com/papertrail/api/external/grobid/GrobidTeiParser.kt), which reads bibliography structures under `listBibl`. Another entry (`b44`) has plausible author/date/identifier fields but also unusually numeric notes; keep it as a mixed-signal case rather than count it as a confirmed artifact.

**Extraction classification:** 47 entries parsed; 2 strong table-artifact indicators (`b45`, `b46`); the other 45 are candidate references, not individually human-adjudicated genuine entries. Do not silently delete them or change Citation Target links based on this audit alone.

## Identity findings

For title and author fields separately, normalize each string with Unicode NFKC and `casefold()`, tokenize with the Unicode-alphanumeric regex `[^\W_]+`, discard duplicate tokens, and calculate Jaccard overlap `|source ∩ candidate| / |source ∪ candidate|`. Author lists are joined with one space before tokenization; no stemming, transliteration, or acronym expansion is applied. On the 33 resolved entries, title-only overlaps were below 0.25 for 9 entries, from 0.25 through 0.49 for 6, and at least 0.50 for 18. This is a screening statistic, not the resolver score or a correctness label: alternate versions, translations, abbreviations, and extraction errors can produce low overlap.

An independent read-only audit flagged two opaque entries (`b10`, `b18`) as strong false-accept indicators. Both are preprint metadata matches accepted at scores **0.362** and **0.421**, respectively. Under the token-Jaccard formula above, `b10` has title overlap **1/17 (5.9%)** and author overlap **1/18 (5.6%)**; `b18` has title overlap **2/20 (10.0%)** and author overlap **1/17 (5.9%)**. Neither extracted entry contains a DOI, while each selected candidate does. These visibly discordant metadata pairs should be prioritized for adjudication; the exact strings are omitted to protect source content. They are **not counted as confirmed false matches** because the evaluation set has no human identity labels. No defensible overall precision, recall, or false-match rate can be calculated from this one run.

### Identity-label accounting

| Label | Count | Denominator / qualification |
|---|---:|---|
| Parsed entries | 47 | All GROBID bibliography entries persisted |
| Strong extraction-artifact indicators | 2 | `b45`, `b46`; 45 remaining entries are only candidate genuine references |
| Resolver-accepted candidate identities | 33 | 1 DOI confirmation plus 32 metadata matches; not truth labels |
| Human-confirmed correct identities | 0 | No identity adjudication was performed |
| Strong false-accept indicators | 2 | `b10`, `b18`; included among the 33, pending adjudication |
| Human-confirmed false identities | 0 | No identity adjudication was performed |

The “0” ground-truth counts mean **not adjudicated**, not that the run contains no correct or false identities.

## Access and downstream coverage

| Persisted access result | Count | What is known |
|---|---:|---|
| Full text available | 8 | All 8 are recorded as English and have acquired-asset provenance |
| Metadata only | 25 | All 25 carry `NO_LEGAL_FULL_TEXT_LOCATION` |
| No access result | 14 | These are the 14 unresolved references; acquisition was skipped because identity was not resolved |
| Abstract only / unavailable | 0 | None recorded |

All 33 resolved references have persisted access outcomes; 14 unresolved references have no access outcome. There are 33 successful Unpaywall discovery operations and 8 successful full-text fetches. The report retains source-URL/hash provenance for the 8 acquired assets, but exact values are omitted here. Six of the 8 acquired assets have completed evidence indexing and produced full-text Claim–Reference outcomes; the other 2 have no indexing/outcome record in the report. The run records six full-text evidence assessments, all `INSUFFICIENT_EVIDENCE`; this is not a judgment that the papers fail to support their claims. Therefore, **8/47** entries have a fetched asset, **6/47** have an indexed asset, and **0/47** have a human-adjudicated exact-identity asset in this baseline. The last count means “not adjudicated,” not “no correct assets.”

The 25 metadata-only records establish that **no location passed the run's legal-usability policy**. They do not distinguish “provider returned no location” from “locations were returned but rejected by policy”: the discovery response is not retained in the per-reference execution artifacts. Do not claim the 25 are all paywalled or all license-rejected. Likewise, the 8 fetched assets are not 8 verified-correct cited works: identity and asset correctness remain separate questions.

The completed execution trace contains 603 successful spans and no safe error codes. Trace completion is not complete payload capture: provider responses and raw document content were intentionally omitted or sanitized. Crossref is in the run configuration, but the per-entry trace records safe item counts rather than provider candidate payloads, so the run cannot reconstruct why each particular metadata candidate was selected.

## Evaluation design and limitations

Use the supplied run as a **diagnostic/development case only**. Do not tune the matcher threshold on it and then report it as held-out evaluation. Keep the repository's synthetic QA fixture in development-only coverage. A future accuracy evaluation needs a permission-appropriate, source/paper-disjoint, human-adjudicated set with explicit labels for:

1. genuine Bibliography Entry versus extraction artifact;
2. correct Canonical Paper identity versus false match / unresolved;
3. exact Cited Paper Asset identity and version;
4. legal, processing-eligible full-text availability.

Stratify by discipline, reference type, and observable metadata quality. Include articles, conference papers, preprints, books, and chapters; report absent/too-small strata instead of pooling them away. Split by source document and Canonical Paper before tuning; do not allow versions of one work across development and held-out partitions. Record fixture origin, permission/license, parser/provider versions, adjudicator agreement, and disagreements. Do not reuse the user-supplied document or any third-party full text in a public fixture without permission.

### Proposed targets for human approval

Because false identities can taint every downstream evidence result, bias the automatic-resolution gate toward precision and accept a higher unresolved count. For an eventual operating point, consider a **pilot precision target of at least 98%** on the held-out, human-adjudicated identity set, with confidence intervals and subgroup counts reported; report recall and unresolved coverage separately. Review every apparent high-confidence false match. The 98% figure is a product proposal for human approval, not a target measured or calibrated by this baseline, and not a universal guarantee. This run is too small and lacks ground truth to select the numeric matcher threshold; preserve uncertainty rather than lowering the threshold to reduce unresolved counts.

## Actionable conclusions

1. Investigate the GROBID table-artifact path using provenance-preserving fixtures; keep suspicious entry classification separate from identity resolution.
2. Treat `b10` and `b18` as first-priority manual-adjudication cases; do not retroactively rewrite this immutable run.
3. Improve discovery/access reason instrumentation so future reports separate no returned location from policy rejection.
4. Keep the 14 access skips counted as a dependency on unresolved identity, not as independent acquisition failures.
5. Do not claim all-discipline performance or full evidence coverage from this narrow diagnostic run. Add a rights-reviewed cross-discipline, paper-disjoint evaluation set before selecting matcher thresholds.

## Reproduction notes

The counts above were derived from the local persisted run report, paginated execution spans, parsed bibliographic-field summaries, and raw GROBID TEI already stored for that run. The raw bibliography, source/claim/evidence text, provider response bodies, and signed URLs are intentionally not included. Reproduction must use an authorized local run and summarize only aggregate counts and opaque entry keys.
