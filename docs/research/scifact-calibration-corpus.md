# Optional research: SciFact as an evaluation-corpus candidate

## Recommendation

If optional evaluation research is pursued, use **SciFact as a limited, external benchmark candidate** for scientific citation-claim verification—not as a complete Laya calibration corpus or approval evidence by itself. This research is not a product or release requirement.

SciFact is unusually well aligned with Paper T-Rail's claim-to-cited-paper problem: its claims were rewritten from scientific citation sentences, and annotators labeled cited-paper abstracts with support/refute/no-information outcomes and sentence rationales. Its released claim/evidence annotations are CC BY 4.0. However, the corpus consists of abstracts rather than full text, does not provide Paper T-Rail's Evidence Roles or four ordinal score fields, and lacks the full set of product judgement categories. Its public split is claim-based and does not promise a Cited-Paper-disjoint held-out set. These gaps limit its usefulness as a standalone research resource; there is no product or release gate tied to completing this work.

## What SciFact provides

The official repository describes 1,409 expert-written scientific claims and provides labeled claim files for train/dev, an unlabeled test file, and a shared abstract corpus. Claims originate from citation sentences, and each claim records `cited_doc_ids`; annotated evidence is represented as `SUPPORT` or `CONTRADICT` rationales with sentence indices. Cited documents without annotated evidence are represented by absence from the claim's evidence map. The original paper describes the abstract-level outcomes as Supports, Refutes, and NoInfo. Verification annotators included three NLP experts, five life-science undergraduates, and five life-science graduate students; annotators verified claims they did not write. Human quality review independently re-annotated 232 claim–abstract pairs, reporting Cohen's κ = 0.75 for labels and κ = 0.71 for sentence-level rationale membership.

### Fit to Paper T-Rail

| Calibration need | SciFact coverage | Consequence |
|---|---|---|
| Claim linked to a cited research document | Yes; claims derive from citation sentences and retain cited document IDs. | Useful for a supplementary citation-verification pilot. |
| Human expected outcome and evidence sentence rationale | Yes, for support/refute/no-information on abstracts. | Candidate labels can seed outcome/rationale comparison, subject to reviewed mapping. |
| Full Cited Paper text | No; the released corpus contains abstracts. | It cannot stand in for the full-text Evidence Passage task; Paper T-Rail does not semantically judge abstract-only access. |
| Every Evidence Judgement class | No; the core scheme is support/refute/no-information. | `NOINFO` may be a reviewed abstract-level analogue for insufficiency, but is not automatically equivalent to full-text insufficiency; additional labels are needed for partial support and unrelated evidence. |
| Evidence Role and four ordinal score dimensions | No. | Qualified reviewers must add these labels for the Paper T-Rail calibration protocol. |
| Paper-disjoint evaluation split | Not guaranteed by the official claim-level splits/shared corpus. | If used for research, build and validate a grouped split so no Cited Paper appears in more than one partition. |

## License and acquisition assessment

The official [SciFact license file](https://github.com/allenai/scifact/blob/master/LICENSE.md) states that `claims_*.jsonl` claims and evidence annotations are released under **CC BY 4.0**, the abstracts in `corpus.jsonl` are part of S2ORC and licensed under **ODC-By 1.0**, and repository code is Apache-2.0. CC BY 4.0 allows sharing and adaptation subject to attribution and other license conditions.

ODC-By 1.0 is an attribution license for database rights. Its own preamble says the license governs the database, not the individual contents; those may have separate rights. Therefore, the repository's ODC-By statement is a useful provenance lead, but does not by itself establish the copyright/reuse status of every underlying abstract. Record the SciFact/S2ORC attribution, pin the exact dataset release and checksums, and obtain a rights review for any abstract content used in the calibration set.

This is also narrower than the current application acquisition policy: `LegalOpenAccessLocationPolicy` accepts CC0, CC BY, and public-domain identifiers, not ODC-By. Do not route SciFact abstracts through the production cited-paper acquisition path unless the policy/legal review explicitly permits it. For a full-text supplement, use an approved source such as the PMC Open Access Subset, verify the individual article license, and download only through PMC's designated OAI-PMH, FTP, or Cloud services; PMC prohibits systematic batch downloading from its main website. Do not treat free-to-read as reusable.

## Optional research use

1. SciFact may be used as a **supplementary abstract-level benchmark** for its supported outcome/rationale labels, after creating a Cited-Paper-disjoint split and recording the exact release/checksums and applicable rights.
2. Any broader calibration research would need legally usable, in-domain **full-text Cited Papers** whose article-level licenses are accepted by the project. Reviewers could label the existing judgement schema, Evidence Role, ordinal dimensions, and expected Claim–Paper outcomes/conflicts, with independent review and adjudication.
3. Researchers could compare predeclared aggregation threshold arms on frozen Laya outputs and report uncertainty and limitations. No result changes the product's uncalibrated status unless separately decided; completion or approval of this research is not a product or release requirement.
4. The current Spring aggregation default and SciFact's public benchmark labels do not establish model accuracy or calibration.

## Sources

- Allen Institute for AI, [SciFact repository](https://github.com/allenai/scifact) — release layout, claim-level partitions, and dataset overview.
- Allen Institute for AI, [SciFact data schema](https://github.com/allenai/scifact/blob/master/doc/data.md) — citation-derived claims, `cited_doc_ids`, evidence labels, rationale sentence indices, and abstract schema.
- Allen Institute for AI, [SciFact license](https://github.com/allenai/scifact/blob/master/LICENSE.md) — CC BY 4.0 annotations and ODC-By 1.0 abstracts.
- Wadden et al., [Fact or Fiction: Verifying Scientific Claims](https://arxiv.org/html/2004.14974) — task, dataset construction, annotation and agreement results.
- Open Data Commons, [ODC-By 1.0 license](https://opendatacommons.org/licenses/by/1-0/) — database/content-rights distinction and attribution obligations.
- Creative Commons, [CC BY 4.0 deed](https://creativecommons.org/licenses/by/4.0/) — reuse subject to attribution and license terms.
- NCBI, [PMC Copyright Notice](https://pmc.ncbi.nlm.nih.gov/about/copyright/) — article-level rights, OA-subset distinction, and permitted batch-download services.
- Paper T-Rail, [`LegalOpenAccessLocationPolicy`](../../api/src/main/kotlin/com/papertrail/api/scholarly/acquisition/domain/LegalOpenAccessLocationPolicy.kt) — currently accepted processing-license identifiers.
