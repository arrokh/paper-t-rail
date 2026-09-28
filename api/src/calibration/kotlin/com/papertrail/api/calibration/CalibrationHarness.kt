package com.papertrail.api.calibration

import com.papertrail.api.evidence.verification.domain.EvidenceAggregationPolicy
import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.scholarly.references.client.ScholarlyWork
import com.papertrail.api.scholarly.references.resolver.ScholarlyMetadataMatcher
import java.util.Locale
import java.util.UUID

/** Evaluates candidate snapshots using the same deterministic policies as Analysis Runs. */
class CalibrationHarness {
    fun renderReport(fixture: CalibrationFixture): String {
        fixture.validate()
        val referenceEvaluations = fixture.referencePolicyCandidates.map { policy ->
            policy to fixture.referenceCases.map { case -> evaluateReference(policy, case) }
        }
        val aggregationEvaluations = fixture.aggregationPolicyCandidates.map { policy ->
            policy to fixture.evidenceCases.map { case -> evaluateAggregation(policy, case) }
        }
        val reviewedLabelCount = fixture.referenceCases.count { it.adjudication.state == CalibrationFixture.LabelState.HUMAN_REVIEWED } +
            fixture.evidenceCases.count { it.adjudication.state == CalibrationFixture.LabelState.HUMAN_REVIEWED }
        val totalLabelCount = fixture.referenceCases.size + fixture.evidenceCases.size

        return buildString {
            appendLine("# V1 Reference and Evidence Calibration Benchmark")
            appendLine()
            appendLine("> **${fixture.fixtureStatus.name}: NOT RELEASE CALIBRATION EVIDENCE.** Candidate metrics over draft labels are exploratory only. Human adjudication and explicit release review remain required; this report never approves or activates a policy.")
            appendLine()
            appendLine("## Fixture and method")
            appendLine()
            appendLine("- Fixture: `${fixture.fixtureId}` (schema version ${fixture.schemaVersion}; ${fixture.fixtureStatus.name})")
            appendLine("- Release calibration: `${fixture.releaseCalibrationStatus}`")
            appendLine("- Label provenance: ${fixture.provenance.description}")
            appendLine("- Labels with human review: $reviewedLabelCount / $totalLabelCount")
            appendLine("- Fixture contract: versioned JSON cases carry expected outcomes, provenance, and `adjudication.state` (`DRAFT` or `HUMAN_REVIEWED`). Human-reviewed labels require reviewer, ISO-8601 `reviewedAt`, and rationale; draft labels must not include review metadata.")
            appendLine("- Required evidence cases include direct support, partial support, contradiction, comparable conflict, and high-confidence/low-scope abstention.")
            appendLine("- Reference implementation: `${ScholarlyMetadataMatcher.POLICY_VERSION}` via the production matcher, including its ambiguity abstention.")
            appendLine("- Evidence implementation: `${EvidenceJudgement.STRENGTH_RUBRIC_VERSION}` and `${EvidenceAggregationPolicy.POLICY_VERSION}` via the production strength and aggregation policies.")
            appendLine("- This harness evaluates aggregation over fixture-supplied Evidence Judgements; it does not invoke a System One provider or measure Laya judgement accuracy, role accuracy, or confidence calibration.")
            appendLine("- A resolved reference is a positive prediction only when the selected candidate ID equals the fixture's expected candidate ID. Precision is true-positive resolutions divided by all automatic resolutions. False-auto-resolution rate is also reported against the entire cohort.")
            appendLine("- Status agreement is exact final-status agreement; conflict agreement is reported separately.")
            appendLine("- Candidate policy snapshots below are benchmark inputs only. Runtime run snapshots are created independently; no threshold is promoted by this harness.")
            appendLine()
            appendLine("## Reference-resolution candidates")
            appendLine()
            appendLine("| Candidate snapshot | Labels | Cases | Auto-resolved | False auto-resolutions | Precision | False-auto rate / all cases | Recall |")
            appendLine("|---|---|---:|---:|---:|---:|---:|---:|")
            referenceEvaluations.forEach { (policy, results) ->
                appendReferenceMetricRows(policy, results, CalibrationFixture.LabelState.HUMAN_REVIEWED)
                appendReferenceMetricRows(policy, results, CalibrationFixture.LabelState.DRAFT)
            }
            appendLine()
            appendLine("Reference case outcomes by candidate:")
            appendLine()
            appendLine("| Candidate | Case | Label state | Expected | Observed | Score | Agreement |")
            appendLine("|---|---|---|---|---|---:|---|")
            referenceEvaluations.forEach { (policy, results) ->
                results.forEach { result ->
                    appendLine("| `${policy.candidateId}` | `${result.case.caseId}` | ${result.case.adjudication.state} | ${result.case.expected.status}${result.case.expected.candidateId?.let { " (`$it`)" }.orEmpty()} | ${result.outcome} [${result.reasonCode}]${result.candidateId?.let { " (`$it`)" }.orEmpty()} | ${result.score?.let(::formatScore) ?: "—"} | ${if (result.agrees) "yes" else "no"} |")
                }
            }
            appendLine()
            appendLine("## Evidence-aggregation candidates")
            appendLine()
            appendLine("| Candidate snapshot | Labels | Cases | Status agreement | Conflict agreement |")
            appendLine("|---|---|---:|---:|---:|")
            aggregationEvaluations.forEach { (policy, results) ->
                appendAggregationMetricRows(policy, results, CalibrationFixture.LabelState.HUMAN_REVIEWED)
                appendAggregationMetricRows(policy, results, CalibrationFixture.LabelState.DRAFT)
            }
            appendLine()
            appendLine("Evidence case outcomes by candidate:")
            appendLine()
            appendLine("| Candidate | Case | Label state | Expected | Observed | Conflict expected / observed | Agreement |")
            appendLine("|---|---|---|---|---|---|---|")
            aggregationEvaluations.forEach { (policy, results) ->
                results.forEach { result ->
                    appendLine("| `${policy.candidateId}` | `${result.case.caseId}` | ${result.case.adjudication.state} | ${result.case.expectedStatus} | ${result.status} | ${result.case.expectedConflict} / ${result.conflict} | ${if (result.agrees) "yes" else "no"} |")
                }
            }
            appendLine()
            appendLine("## Interpretation and release gate")
            appendLine()
            appendLine("This versioned fixture is intentionally synthetic and its labels are `DRAFT`; its numbers demonstrate reproducible harness behavior, not real-world precision or calibration. Do not use these results to select production thresholds. Before any release-calibration claim, replace or supplement draft cases with provenance-bearing human-adjudicated labels, review the benchmark method and results, and explicitly approve a policy. Production remains conservative: reference thresholds are configurable and pinned per Analysis Run, while evidence aggregation remains `NOT_RUN` with null policy and thresholds unless explicitly configured. Below-threshold and ambiguous reference candidates must remain `UNRESOLVED`.")
            appendLine()
            appendLine("Rebuild this report with `make calibrate` (or `cd api && ./gradlew calibrate`).")
        }
    }

    private fun evaluateReference(
        policy: CalibrationFixture.ReferencePolicyCandidate,
        case: CalibrationFixture.ReferenceCase,
    ): ReferenceEvaluation {
        val works = case.candidates.map { candidate ->
            candidate.candidateId to ScholarlyWork(candidate.doi, candidate.title, candidate.authors, candidate.year)
        }
        val match = ScholarlyMetadataMatcher(policy.confidenceThreshold, policy.ambiguityMargin)
            .match(case.reference, works.map { it.second })
        val selectedCandidateId = match.candidate?.let { selected ->
            works.firstOrNull { (_, work) -> work === selected }?.first
        }
        val outcome = if (selectedCandidateId == null) {
            CalibrationFixture.ResolutionOutcome.UNRESOLVED
        } else {
            CalibrationFixture.ResolutionOutcome.RESOLVED
        }
        return ReferenceEvaluation(
            case = case,
            outcome = outcome,
            candidateId = selectedCandidateId,
            score = match.score,
            reasonCode = match.reasonCode,
            agrees = outcome == case.expected.status && selectedCandidateId == case.expected.candidateId,
        )
    }

    private fun evaluateAggregation(
        policy: CalibrationFixture.AggregationPolicyCandidate,
        case: CalibrationFixture.EvidenceCase,
    ): AggregationEvaluation {
        val judgements = case.judgements.mapIndexed { index, input ->
            val candidateId = UUID.nameUUIDFromBytes("${case.caseId}:$index".toByteArray(Charsets.UTF_8))
            input.toJudgement(candidateId)
        }
        val decision = EvidenceAggregationPolicy(policy.thresholds).aggregate(judgements)
        val status = decision.finalStatus.name
        return AggregationEvaluation(
            case = case,
            status = status,
            conflict = decision.evidenceConflict,
            agrees = status == case.expectedStatus && decision.evidenceConflict == case.expectedConflict,
        )
    }

    private fun StringBuilder.appendReferenceMetricRows(
        policy: CalibrationFixture.ReferencePolicyCandidate,
        results: List<ReferenceEvaluation>,
        labelState: CalibrationFixture.LabelState,
    ) {
        val cohort = results.filter { it.case.adjudication.state == labelState }
        if (cohort.isEmpty()) {
            appendLine("| ${referenceSnapshot(policy)} | $labelState | 0 | — | — | — | — | — |")
            return
        }
        val automaticallyResolved = cohort.filter { it.candidateId != null }
        val truePositives = automaticallyResolved.count { it.candidateId == it.case.expected.candidateId }
        val falseAutoResolutions = automaticallyResolved.size - truePositives
        val expectedPositiveCount = cohort.count { it.case.expected.candidateId != null }
        val falseAutoRateAllCases = falseAutoResolutions.toDouble() / cohort.size
        appendLine(
            "| ${referenceSnapshot(policy)} | $labelState | ${cohort.size} | ${automaticallyResolved.size} | $falseAutoResolutions | ${ratio(truePositives, automaticallyResolved.size)} | ${percent(falseAutoRateAllCases)} | ${ratio(truePositives, expectedPositiveCount)} |",
        )
    }

    private fun StringBuilder.appendAggregationMetricRows(
        policy: CalibrationFixture.AggregationPolicyCandidate,
        results: List<AggregationEvaluation>,
        labelState: CalibrationFixture.LabelState,
    ) {
        val cohort = results.filter { it.case.adjudication.state == labelState }
        val snapshot = aggregationSnapshot(policy)
        if (cohort.isEmpty()) {
            appendLine("| $snapshot | $labelState | 0 | — | — |")
            return
        }
        val statusAgreements = cohort.count { it.status == it.case.expectedStatus }
        val conflictAgreements = cohort.count { it.conflict == it.case.expectedConflict }
        appendLine("| $snapshot | $labelState | ${cohort.size} | ${ratio(statusAgreements, cohort.size)} | ${ratio(conflictAgreements, cohort.size)} |")
    }

    private fun referenceSnapshot(policy: CalibrationFixture.ReferencePolicyCandidate): String =
        "`${policy.candidateId}`<br>`${policy.scorePolicyVersion}`<br>threshold=${formatScore(policy.confidenceThreshold)}, ambiguity=${formatScore(policy.ambiguityMargin)}"

    private fun aggregationSnapshot(policy: CalibrationFixture.AggregationPolicyCandidate): String =
        "`${policy.candidateId}`<br>`${policy.aggregationPolicyVersion}` / `${policy.verificationPolicyVersion}`<br>direct=${formatScore(policy.thresholds.directSupport)}, partial=${formatScore(policy.thresholds.partialSupport)}, contradiction=${formatScore(policy.thresholds.contradiction)}, margin=${formatScore(policy.thresholds.comparabilityMargin)}"

    private fun ratio(numerator: Int, denominator: Int): String =
        if (denominator == 0) "—" else "${numerator}/${denominator} (${percent(numerator.toDouble() / denominator)})"

    private fun percent(value: Double): String = String.format(Locale.ROOT, "%.1f%%", value * 100.0)

    private fun formatScore(value: Double): String = String.format(Locale.ROOT, "%.3f", value)

    private data class ReferenceEvaluation(
        val case: CalibrationFixture.ReferenceCase,
        val outcome: CalibrationFixture.ResolutionOutcome,
        val candidateId: String?,
        val score: Double?,
        val reasonCode: String,
        val agrees: Boolean,
    )

    private data class AggregationEvaluation(
        val case: CalibrationFixture.EvidenceCase,
        val status: String,
        val conflict: Boolean,
        val agrees: Boolean,
    )
}
