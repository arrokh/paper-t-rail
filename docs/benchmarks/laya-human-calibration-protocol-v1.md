# Laya Human Calibration Protocol (Draft)

> **Status: DRAFT — NOT APPROVED FOR RELEASE.** This protocol records the minimum review process requested by issue [#45](https://github.com/arrokh/paper-t-rail/issues/45). It does not contain an approved dataset, numerical safety bar, production approval, or calibration result. Do not use it to enable production Laya or aggregation.

- Protocol ID: `laya-human-calibration-v1-draft`
- Candidate checkpoint: `convaiinnovations/laya-typed-decisions@1a793eb568e6718f15941d08f85432581df534e3`
- Runtime: `laya-serve` v0.3.20 at `23a17522aa4942da6cce53a995a275760320b691`
- Output mapping: `paper-trail-evidence-judgement-v1` (`pt-ej-v1`)

## 1. Dataset eligibility and provenance

1. Use only in-domain, legally usable, resolved Cited Papers. Reference-resolution threshold calibration is separate and must not be counted as Laya judgement approval.
2. For every Cited Paper and Evidence Passage, record the stable paper/case identifiers, bibliographic citation, source location (page and section, plus an opaque stable passage-locator ID), exact acquired asset hash, source URL or repository record, license/rights basis, and provenance needed to verify lawful use. Keep the mapping from locator IDs to source locations and all content in the approved local data boundary; reports may include only locator IDs, never claim or passage text.
3. Version the dataset manifest, annotation guide, case labels, model-output set, and split assignment. A dataset version must identify the exact Cited Paper group for each case so split integrity can be checked.
4. Assign each Cited Paper wholly to one split. No passage, claim, or derived case from one Cited Paper may occur in both calibration and held-out test splits. Freeze and identify the held-out paper set before using calibration results to change prompts, rubrics, thresholds, or policies.
5. Cover every Evidence Judgement (`DIRECT_SUPPORT`, `PARTIAL_SUPPORT`, `CONTRADICTS`, `UNRELATED`, `INSUFFICIENT`), every Evidence Role (`PRIMARY_FINDING`, `AUTHOR_SYNTHESIS`, `SECONDARY_REPORT`), scope/qualifier mismatches, secondary reports, comparable support/contradiction, and abstention/incomplete cases. Record the case counts for each category; absence of a category prevents claiming coverage for it.

## 2. Human annotation and adjudication

For each claim–passage case, qualified reviewers independently label:

- one Evidence Judgement using the five values above;
- one Evidence Role using the three values above;
- the four ordinal dimensions `directness`, `claim_scope_match`, `study_design_quality`, and `relevance`, using the exact pinned 0–4 rubrics documented in the [Laya request and output contract](../laya-evaluation.md#request-and-output-contract);
- the expected final Claim–Paper outcome and whether comparable support/contradiction constitutes a conflict, where that outcome is assessable from the reviewed evidence.

Use independent double review where practical. Preserve each original annotation separately; record reviewer identity/qualification, review timestamp, rationale, and disagreement. A designated adjudicator resolves disagreements and records the final label and rationale without erasing original votes. Reviewers must record abstention when the paper/passage does not support a defensible label; do not turn uncertainty or missing evidence into a negative label.

The release dataset may be marked `HUMAN_REVIEWED` only after all included release labels have been reviewed and disagreements adjudicated. Each label record must include reviewer/adjudicator identity, ISO-8601 timestamp, rationale, provenance, and paper-group split. Synthetic cases may be retained as software tests but must remain marked `DRAFT` and excluded from release metrics.

## 3. Pre-registration and held-out evaluation

Before running the held-out set, the human reviewer must freeze a dated evaluation plan identifying:

- dataset and held-out split versions/hashes;
- exact checkpoint, runtime, prompt/rubric, output-mapping version, application commit, and runtime configuration;
- the metrics to report, uncertainty-interval method, treatment of abstentions/incomplete requests, and sample counts;
- the numerical safety bar for each decision-relevant metric and the acceptable handling of high-risk errors;
- the aggregation policy version and every threshold/margin candidate to compare on the frozen Laya outputs.

This draft intentionally sets **no numerical safety bar**. The reviewer must supply and approve those values before evaluation; results produced without a predeclared bar cannot justify a production GO or threshold selection.

Run the exact pinned candidate on every eligible held-out case without truncation or provider fallback. Preserve the raw provider response/output needed for audit in the approved local dataset store, bound to the dataset and candidate versions. Record token-limit rejections, request failures, timeouts, and malformed responses as incomplete coverage with their reason; never fabricate a judgement or silently drop a failed case. Reports must not contain claim text, Evidence Passage text, request bodies, API keys, or query strings.

## 4. Required report

Report calibration and held-out results separately. For held-out data, include at least:

- per-class precision and recall plus the confusion matrix for Evidence Judgement;
- sample counts and stated uncertainty intervals for each reported metric;
- Evidence Role agreement and per-dimension ordinal-score agreement. For ordinal scores, exact agreement rounds the normalized prediction multiplied by four to the nearest integer; MAE uses that 0–4 prediction without rounding;
- calibration of the model's confidence against adjudicated correctness (for example, Brier score and/or ECE), without describing `answer_confidence` as a calibrated probability in advance;
- abstention rate and coverage, distinguishing `INSUFFICIENT` from infrastructure/token-limit failures;
- high-risk error examples identified by case ID and source locator, without copying protected claim/passage text into the report;
- token-limit and other incomplete-work counts, including any available complete-sequence token measurements;
- errors and outcomes by Cited Paper, including cases in which comparable support and contradiction require `INSUFFICIENT_EVIDENCE`.

Apply each pre-registered aggregation candidate to the frozen Laya outputs, then compare final-status precision/recall, false decisive outcomes (`SUPPORTED` / `CONTRADICTED`), partial-support handling, conflict detection, and `INSUFFICIENT_EVIDENCE`/abstention against the adjudicated Claim–Paper outcomes. No policy or threshold may be promoted from synthetic cases or from tuning-set results.

## 5. Deployment decision

A human reviewer must separately review the exact target deployment's artifact/license and provenance, private authenticated network boundary and no-egress behavior, retention/deletion behavior, failure semantics, and rollback plan. Record reviewer, date, rationale, exact deployment scope, and explicit GO/NO-GO in `docs/agents/provider-matrix.md`. A GO must cite the frozen held-out report and show that the predeclared safety bar was met. A target-specific NO-GO requires that target to opt out with `LAYA_ENABLED=false` and `SYSTEM_ONE_DEFAULT_PROVIDER=mock`; aggregation remains disabled unless separately approved. The production Spring profile's Laya-enabled/Laya-selected defaults are an owner-authorized configuration choice recorded in issue #45, not evidence that the candidate passed this protocol. Local experimental results do not establish accuracy or calibration approval.

## Current status

No legally usable in-domain release dataset, human-adjudicated held-out labels, or predeclared numerical safety bar is included in the repository. The current `paper-t-rail-v1-calibration-draft` fixture is synthetic and remains `DRAFT`; its aggregation harness does not call Laya. This document is a draft procedure only, not evidence that any protocol step or production gate has passed.
