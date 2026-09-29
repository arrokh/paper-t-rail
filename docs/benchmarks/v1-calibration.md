# Optional V1 Reference and Evidence Calibration Benchmark

> **OPTIONAL DRAFT: NOT A RELEASE REQUIREMENT OR CALIBRATION EVIDENCE.** Candidate metrics over draft labels are exploratory only. Human adjudication is not required for product release; this report never approves or activates a policy.

## Fixture and method

- Fixture: `paper-t-rail-v1-calibration-draft` (schema version 1; DRAFT)
- Calibration research status: `NOT_APPROVED`
- Label provenance: Hand-authored synthetic known-answer scenarios for exercising reference matching and evidence aggregation. No case is drawn from or adjudicated against a scholarly paper.
- Labels with human review: 0 / 11
- Fixture contract: versioned JSON cases carry expected outcomes, provenance, and `adjudication.state` (`DRAFT` or `HUMAN_REVIEWED`). Human-reviewed labels require reviewer, ISO-8601 `reviewedAt`, and rationale; draft labels must not include review metadata.
- Required evidence cases include direct support, partial support, contradiction, comparable conflict, and high-confidence/low-scope abstention.
- Reference implementation: `title-author-year-weighted-edit-similarity-v1` via the production matcher, including its ambiguity abstention.
- Evidence implementation: `weighted-evidence-role-scope-design-v1` and `conflict-aware-evidence-strength-v1` via the production strength and aggregation policies.
- This harness evaluates aggregation over fixture-supplied Evidence Judgements; it does not invoke a System One provider or measure Laya judgement accuracy, role accuracy, or confidence calibration.
- A resolved reference is a positive prediction only when the selected candidate ID equals the fixture's expected candidate ID. Precision is true-positive resolutions divided by all automatic resolutions. False-auto-resolution rate is also reported against the entire cohort.
- Status agreement is exact final-status agreement; conflict agreement is reported separately.
- Candidate policy snapshots below are benchmark inputs only. Runtime run snapshots are created independently; no threshold is promoted by this harness.

## Reference-resolution candidates

| Candidate snapshot | Labels | Cases | Auto-resolved | False auto-resolutions | Precision | False-auto rate / all cases | Recall |
|---|---|---:|---:|---:|---:|---:|---:|
| `reference-threshold-090`<br>`title-author-year-weighted-edit-similarity-v1`<br>threshold=0.900, ambiguity=0.020 | HUMAN_REVIEWED | 0 | — | — | — | — | — |
| `reference-threshold-090`<br>`title-author-year-weighted-edit-similarity-v1`<br>threshold=0.900, ambiguity=0.020 | DRAFT | 5 | 3 | 1 | 2/3 (66.7%) | 20.0% | 2/2 (100.0%) |
| `reference-threshold-098`<br>`title-author-year-weighted-edit-similarity-v1`<br>threshold=0.980, ambiguity=0.020 | HUMAN_REVIEWED | 0 | — | — | — | — | — |
| `reference-threshold-098`<br>`title-author-year-weighted-edit-similarity-v1`<br>threshold=0.980, ambiguity=0.020 | DRAFT | 5 | 2 | 1 | 1/2 (50.0%) | 20.0% | 1/2 (50.0%) |
| `reference-threshold-099`<br>`title-author-year-weighted-edit-similarity-v1`<br>threshold=0.990, ambiguity=0.020 | HUMAN_REVIEWED | 0 | — | — | — | — | — |
| `reference-threshold-099`<br>`title-author-year-weighted-edit-similarity-v1`<br>threshold=0.990, ambiguity=0.020 | DRAFT | 5 | 1 | 0 | 1/1 (100.0%) | 0.0% | 1/2 (50.0%) |

Reference case outcomes by candidate:

| Candidate | Case | Label state | Expected | Observed | Score | Agreement |
|---|---|---|---|---|---:|---|
| `reference-threshold-090` | `reference-confirmed-exact-metadata` | DRAFT | RESOLVED (`confirmed-work`) | RESOLVED [MATCHED] (`confirmed-work`) | 1.000 | yes |
| `reference-threshold-090` | `reference-confirmed-minor-title-variation` | DRAFT | RESOLVED (`minor-variation-work`) | RESOLVED [MATCHED] (`minor-variation-work`) | 0.962 | yes |
| `reference-threshold-090` | `reference-near-miss-title-decoy` | DRAFT | UNRESOLVED | RESOLVED [MATCHED] (`near-miss-decoy`) | 0.984 | no |
| `reference-threshold-090` | `reference-near-miss-unrelated-work` | DRAFT | UNRESOLVED | UNRESOLVED [BELOW_CONFIDENCE_THRESHOLD] | 0.142 | yes |
| `reference-threshold-090` | `reference-ambiguous-duplicate-candidates` | DRAFT | UNRESOLVED | UNRESOLVED [AMBIGUOUS_MATCH] | 1.000 | yes |
| `reference-threshold-098` | `reference-confirmed-exact-metadata` | DRAFT | RESOLVED (`confirmed-work`) | RESOLVED [MATCHED] (`confirmed-work`) | 1.000 | yes |
| `reference-threshold-098` | `reference-confirmed-minor-title-variation` | DRAFT | RESOLVED (`minor-variation-work`) | UNRESOLVED [BELOW_CONFIDENCE_THRESHOLD] | 0.962 | no |
| `reference-threshold-098` | `reference-near-miss-title-decoy` | DRAFT | UNRESOLVED | RESOLVED [MATCHED] (`near-miss-decoy`) | 0.984 | no |
| `reference-threshold-098` | `reference-near-miss-unrelated-work` | DRAFT | UNRESOLVED | UNRESOLVED [BELOW_CONFIDENCE_THRESHOLD] | 0.142 | yes |
| `reference-threshold-098` | `reference-ambiguous-duplicate-candidates` | DRAFT | UNRESOLVED | UNRESOLVED [AMBIGUOUS_MATCH] | 1.000 | yes |
| `reference-threshold-099` | `reference-confirmed-exact-metadata` | DRAFT | RESOLVED (`confirmed-work`) | RESOLVED [MATCHED] (`confirmed-work`) | 1.000 | yes |
| `reference-threshold-099` | `reference-confirmed-minor-title-variation` | DRAFT | RESOLVED (`minor-variation-work`) | UNRESOLVED [BELOW_CONFIDENCE_THRESHOLD] | 0.962 | no |
| `reference-threshold-099` | `reference-near-miss-title-decoy` | DRAFT | UNRESOLVED | UNRESOLVED [BELOW_CONFIDENCE_THRESHOLD] | 0.984 | yes |
| `reference-threshold-099` | `reference-near-miss-unrelated-work` | DRAFT | UNRESOLVED | UNRESOLVED [BELOW_CONFIDENCE_THRESHOLD] | 0.142 | yes |
| `reference-threshold-099` | `reference-ambiguous-duplicate-candidates` | DRAFT | UNRESOLVED | UNRESOLVED [AMBIGUOUS_MATCH] | 1.000 | yes |

## Evidence-aggregation candidates

| Candidate snapshot | Labels | Cases | Status agreement | Conflict agreement |
|---|---|---:|---:|---:|
| `aggregation-permissive-example`<br>`conflict-aware-evidence-strength-v1` / `weighted-evidence-role-scope-design-v1`<br>direct=0.700, partial=0.650, contradiction=0.700, margin=0.100 | HUMAN_REVIEWED | 0 | — | — |
| `aggregation-permissive-example`<br>`conflict-aware-evidence-strength-v1` / `weighted-evidence-role-scope-design-v1`<br>direct=0.700, partial=0.650, contradiction=0.700, margin=0.100 | DRAFT | 6 | 6/6 (100.0%) | 6/6 (100.0%) |
| `aggregation-design-baseline-example`<br>`conflict-aware-evidence-strength-v1` / `weighted-evidence-role-scope-design-v1`<br>direct=0.800, partial=0.700, contradiction=0.800, margin=0.080 | HUMAN_REVIEWED | 0 | — | — |
| `aggregation-design-baseline-example`<br>`conflict-aware-evidence-strength-v1` / `weighted-evidence-role-scope-design-v1`<br>direct=0.800, partial=0.700, contradiction=0.800, margin=0.080 | DRAFT | 6 | 6/6 (100.0%) | 6/6 (100.0%) |
| `aggregation-conservative-example`<br>`conflict-aware-evidence-strength-v1` / `weighted-evidence-role-scope-design-v1`<br>direct=0.900, partial=0.850, contradiction=0.900, margin=0.050 | HUMAN_REVIEWED | 0 | — | — |
| `aggregation-conservative-example`<br>`conflict-aware-evidence-strength-v1` / `weighted-evidence-role-scope-design-v1`<br>direct=0.900, partial=0.850, contradiction=0.900, margin=0.050 | DRAFT | 6 | 5/6 (83.3%) | 6/6 (100.0%) |

Evidence case outcomes by candidate:

| Candidate | Case | Label state | Expected | Observed | Conflict expected / observed | Agreement |
|---|---|---|---|---|---|---|
| `aggregation-permissive-example` | `evidence-direct-support` | DRAFT | SUPPORTED | SUPPORTED | false / false | yes |
| `aggregation-permissive-example` | `evidence-partial-support` | DRAFT | PARTIALLY_SUPPORTED | PARTIALLY_SUPPORTED | false / false | yes |
| `aggregation-permissive-example` | `evidence-contradiction` | DRAFT | CONTRADICTED | CONTRADICTED | false / false | yes |
| `aggregation-permissive-example` | `evidence-comparable-support-contradiction` | DRAFT | INSUFFICIENT_EVIDENCE | INSUFFICIENT_EVIDENCE | true / true | yes |
| `aggregation-permissive-example` | `evidence-high-confidence-low-scope` | DRAFT | INSUFFICIENT_EVIDENCE | INSUFFICIENT_EVIDENCE | false / false | yes |
| `aggregation-permissive-example` | `evidence-secondary-report-only` | DRAFT | INSUFFICIENT_EVIDENCE | INSUFFICIENT_EVIDENCE | false / false | yes |
| `aggregation-design-baseline-example` | `evidence-direct-support` | DRAFT | SUPPORTED | SUPPORTED | false / false | yes |
| `aggregation-design-baseline-example` | `evidence-partial-support` | DRAFT | PARTIALLY_SUPPORTED | PARTIALLY_SUPPORTED | false / false | yes |
| `aggregation-design-baseline-example` | `evidence-contradiction` | DRAFT | CONTRADICTED | CONTRADICTED | false / false | yes |
| `aggregation-design-baseline-example` | `evidence-comparable-support-contradiction` | DRAFT | INSUFFICIENT_EVIDENCE | INSUFFICIENT_EVIDENCE | true / true | yes |
| `aggregation-design-baseline-example` | `evidence-high-confidence-low-scope` | DRAFT | INSUFFICIENT_EVIDENCE | INSUFFICIENT_EVIDENCE | false / false | yes |
| `aggregation-design-baseline-example` | `evidence-secondary-report-only` | DRAFT | INSUFFICIENT_EVIDENCE | INSUFFICIENT_EVIDENCE | false / false | yes |
| `aggregation-conservative-example` | `evidence-direct-support` | DRAFT | SUPPORTED | SUPPORTED | false / false | yes |
| `aggregation-conservative-example` | `evidence-partial-support` | DRAFT | PARTIALLY_SUPPORTED | INSUFFICIENT_EVIDENCE | false / false | no |
| `aggregation-conservative-example` | `evidence-contradiction` | DRAFT | CONTRADICTED | CONTRADICTED | false / false | yes |
| `aggregation-conservative-example` | `evidence-comparable-support-contradiction` | DRAFT | INSUFFICIENT_EVIDENCE | INSUFFICIENT_EVIDENCE | true / true | yes |
| `aggregation-conservative-example` | `evidence-high-confidence-low-scope` | DRAFT | INSUFFICIENT_EVIDENCE | INSUFFICIENT_EVIDENCE | false / false | yes |
| `aggregation-conservative-example` | `evidence-secondary-report-only` | DRAFT | INSUFFICIENT_EVIDENCE | INSUFFICIENT_EVIDENCE | false / false | yes |

## Interpretation and limitations

This versioned fixture is intentionally synthetic and its labels are `DRAFT`; its numbers demonstrate reproducible harness behavior, not real-world precision or calibration. Do not use these results to characterize model quality or imply calibration. Calibration is not a product or release requirement, and this optional report does not validate thresholds or outputs. Runtime configuration enables experimental evidence aggregation by default, but this synthetic fixture does not exercise Laya inference; generated judgements and statuses must remain explicitly uncalibrated. Set `LOCAL_LAYA_AGGREGATION_ENABLED=false` to keep final statuses `NOT_RUN`. Below-threshold and ambiguous reference candidates must remain `UNRESOLVED`.

Rebuild this report with `make calibrate` (or `cd api && ./gradlew calibrate`).
