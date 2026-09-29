package com.papertrail.api.calibration

import com.papertrail.api.evidence.verification.domain.EvidenceAggregationPolicy
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.scholarly.acquisition.domain.TerminalVerificationStatus
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.Random
import java.util.UUID
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Produces an auditable, text-free evaluation report from frozen provider outputs. */
class LayaEvaluationReport {
    fun render(
        dataset: LayaEvaluationDataset,
        run: LayaEvaluationRun,
        plan: LayaEvaluationPlan? = null,
        frozenOutputSha256: String? = null,
        preRegistrationSha256: String? = null,
    ): String {
        dataset.validate()
        run.validateAgainst(dataset)
        require(frozenOutputSha256 == null || frozenOutputSha256.matches(SHA256_PATTERN)) {
            "Frozen Laya output SHA-256 must be a 64-character hexadecimal value."
        }
        require((run.planId == null) == (plan == null) && run.planSha256 == preRegistrationSha256) {
            "Laya evaluation report requires the exact pre-registration plan file used by the run."
        }
        plan?.let {
            require(it.planId == run.planId && it.applicationRevision == run.applicationRevision) {
                "Laya evaluation report plan ID or application revision differs from the frozen run."
            }
            it.validateAgainst(dataset, run.datasetSha256)
        }
        if (run.split == LayaEvaluationDataset.Split.HELD_OUT) {
            require(plan != null) { "Held-out Laya evaluation reports require their pre-registered safety bar and split." }
        }
        val caseById = dataset.cases.associateBy(LayaEvaluationDataset.Case::caseId)
        val observations = run.caseResults.map { result ->
            Observation(caseById.getValue(result.caseId), result)
        }
        val metrics = linkedMapOf<String, Double?>()
        val bootstrap = Bootstrap(dataset.datasetId, run.split)

        return buildString {
            appendLine(if (run.split == LayaEvaluationDataset.Split.HELD_OUT) "# Laya held-out evaluation" else "# Laya calibration evaluation")
            appendLine()
            if (dataset.status == LayaEvaluationDataset.DatasetStatus.DRAFT) {
                appendLine("> **DRAFT — OPTIONAL RESEARCH ONLY.** This report does not establish accuracy or calibration; all product outputs remain uncalibrated.")
                appendLine()
            }
            appendLine("- Dataset: `${dataset.datasetId}` (schema ${dataset.schemaVersion}; ${dataset.status})")
            appendLine("- Protocol: `${dataset.protocolId}`")
            appendLine("- Dataset SHA-256: `${run.datasetSha256}`")
            appendLine("- Frozen Laya output SHA-256: `${frozenOutputSha256 ?: "not persisted"}`")
            appendLine("- Split: `${run.split}`")
            appendLine("- Cited Papers: ${observations.map { it.case.citedPaperId }.distinct().size}")
            appendLine("- Cases: ${observations.size}")
            appendLine("- Candidate checkpoint: `${run.candidate.checkpoint}`")
            appendLine("- Runtime: `${run.candidate.runtime}`")
            appendLine("- Prompt version: `${run.candidate.promptVersion}`")
            appendLine("- Output mapping: `${run.candidate.outputMapping}`")
            appendLine("- Context limit: ${run.candidate.contextLimitTokens} tokens")
            appendLine("- Application revision (pins prompt implementation): `${escapeMarkdown(run.applicationRevision)}`")
            appendLine("- Evaluated at: `${run.evaluatedAt}`")
            if (plan != null) {
                appendLine("- Pre-registration: `${plan.planId}` (SHA-256 `${run.planSha256}`)")
            }
            appendLine("- Uncertainty: 95% percentile Cited-Paper cluster bootstrap (${BOOTSTRAP_REPLICATES} replicates; `${LayaEvaluationDataset.UNCERTAINTY_METHOD_ID}`).")
            appendLine("- This report contains no Atomic Claim or Evidence Passage text. `answer_confidence` is evaluated against adjudicated correctness; it is not presumed to be calibrated.")
            appendLine()

            appendLine("## Coverage and failures")
            val completed = observations.count { it.result.prediction != null }
            metrics["coverage"] = proportion(observations) { it.result.prediction != null }
            appendLine("- Coverage: $completed/${observations.size} (${formatMetric(metrics.getValue("coverage"))})${formatInterval(bootstrap.interval(observations) { sample ->
                proportion(sample) { it.result.prediction != null }
            })}")
            val failures = observations.filter { it.result.failureCode != null }.groupingBy { it.result.failureCode!! }.eachCount()
            appendLine("- Incomplete cases: ${observations.size - completed}")
            if (failures.isEmpty()) {
                appendLine("- Failure codes: none")
            } else {
                appendLine("- Failure codes: " + failures.toSortedMap().entries.joinToString(", ") { (code, count) -> "`$code`=$count" })
            }
            val contextLimitFailures = observations.count { it.result.failureCode == CONTEXT_LIMIT_CODE }
            metrics["token_limit.failure_rate"] = proportion(observations) { it.result.failureCode == CONTEXT_LIMIT_CODE }
            appendLine("- Token-limit failures: $contextLimitFailures/${observations.size} (${formatMetric(metrics.getValue("token_limit.failure_rate"))})${formatInterval(bootstrap.interval(observations) { sample ->
                proportion(sample) { it.result.failureCode == CONTEXT_LIMIT_CODE }
            })}.")
            val rejectedTokenCounts = observations.filter { it.result.measuredSequenceTokenCounts.isNotEmpty() }
            if (rejectedTokenCounts.isEmpty()) {
                appendLine("- Rejected complete-sequence token measurements: unavailable for this split.")
            } else {
                appendLine("- Rejected complete-sequence token measurements (by opaque case ID):")
                rejectedTokenCounts.forEach { item ->
                    appendLine("  - `${item.case.caseId}`: ${item.result.measuredSequenceTokenCounts.joinToString(", ")}")
                }
            }
            val tokenUsages = observations.mapNotNull { item -> item.result.tokenUsage?.let { item to it } }
            val inputTokenTotal = tokenUsages.sumOf { it.second.inputTokens }
            val outputTokenTotal = tokenUsages.sumOf { it.second.outputTokens }
            val meanInputTokens = averageOrNull(tokenUsages.map { it.second.inputTokens.toDouble() })
            val meanOutputTokens = averageOrNull(tokenUsages.map { it.second.outputTokens.toDouble() })
            metrics["token_usage.input_tokens.mean"] = meanInputTokens
            metrics["token_usage.input_tokens.max"] = tokenUsages.maxOfOrNull { it.second.inputTokens }?.toDouble()
            metrics["token_usage.output_tokens.mean"] = meanOutputTokens
            appendLine("- Completed-request usage (full sequences, input tokens summed across the six questions): n=${tokenUsages.size}; input total=$inputTokenTotal; mean=${formatMetric(meanInputTokens)}${formatInterval(bootstrap.interval(observations) { sample ->
                averageOrNull(sample.mapNotNull { it.result.tokenUsage?.inputTokens?.toDouble() })
            })}; max=${metrics.getValue("token_usage.input_tokens.max")?.toLong() ?: "not estimable"}; output total=$outputTokenTotal; output mean=${formatMetric(meanOutputTokens)}${formatInterval(bootstrap.interval(observations) { sample ->
                averageOrNull(sample.mapNotNull { it.result.tokenUsage?.outputTokens?.toDouble() })
            })}.")
            appendLine()

            renderDatasetCoverage(this, observations)
            renderJudgementMetrics(this, observations, bootstrap, metrics)
            renderRoleAndScoreMetrics(this, observations, bootstrap, metrics)
            renderConfidenceMetrics(this, observations, bootstrap, metrics)
            renderHighRiskErrors(this, observations, bootstrap, metrics)
            renderAggregationMetrics(this, dataset, observations, bootstrap, metrics)
            renderCitedPaperSummary(this, dataset, observations)
            renderSafetyBar(this, run, plan, metrics)

            appendLine("## Decision boundary")
            appendLine("Metrics and numerical safety-bar comparisons are evaluation evidence only. They do not grant a production GO; a named human reviewer must make and record a deployment-scoped decision.")
        }
    }

    private fun renderDatasetCoverage(
        output: StringBuilder,
        observations: List<Observation>,
    ) {
        output.appendLine("## Label coverage")
        output.appendLine("- Evidence Judgement: " + EvidenceJudgementKind.entries.joinToString(", ") { kind ->
            "${kind.name}=${observations.count { it.case.adjudicatedLabels.judgement == kind }}"
        })
        output.appendLine("- Evidence Role: " + com.papertrail.api.evidence.verification.domain.EvidenceRole.entries.joinToString(", ") { role ->
            "${role.name}=${observations.count { it.case.adjudicatedLabels.evidenceRole == role }}"
        })
        output.appendLine("- Required case categories: " + LayaEvaluationDataset.CoverageTag.entries.joinToString(", ") { tag ->
            "${tag.name}=${observations.count { tag in it.case.coverageTags }}"
        })
        output.appendLine()
    }

    private fun renderJudgementMetrics(
        output: StringBuilder,
        observations: List<Observation>,
        bootstrap: Bootstrap,
        metrics: MutableMap<String, Double?>,
    ) {
        output.appendLine("## Evidence Judgement")
        output.appendLine("Precision, recall, role, score, and confidence-calibration metrics use completed provider cases only; incomplete requests remain in coverage/failure counts above and are not assigned labels.")
        val complete = observations.filter { it.result.prediction != null }
        val correct = complete.count { it.case.adjudicatedLabels.judgement == it.result.prediction!!.judgement }
        metrics["judgement.accuracy"] = ratio(correct, complete.size)
        output.appendLine("- Accuracy: $correct/${complete.size} (${formatMetric(metrics.getValue("judgement.accuracy"))})${formatInterval(bootstrap.interval(observations) { sample ->
            val predicted = sample.filter { it.result.prediction != null }
            ratio(predicted.count { it.case.adjudicatedLabels.judgement == it.result.prediction!!.judgement }, predicted.size)
        })}")
        output.appendLine()
        output.appendLine("| Gold \\ Predicted | ${EvidenceJudgementKind.entries.joinToString(" | ") { it.name }} | Gold n | Pred n | Precision | Recall |")
        output.appendLine("|---|${EvidenceJudgementKind.entries.joinToString("") { "---|" }}---:|---:|---:|---:|")
        EvidenceJudgementKind.entries.forEach { kind ->
            val row = complete.count { it.case.adjudicatedLabels.judgement == kind }
            output.append("| ${kind.name} | ")
            output.append(EvidenceJudgementKind.entries.joinToString(" | ") { predicted ->
                complete.count { it.case.adjudicatedLabels.judgement == kind && it.result.prediction!!.judgement == predicted }.toString()
            })
            val predictedCount = complete.count { it.result.prediction!!.judgement == kind }
            val truePositive = complete.count { it.case.adjudicatedLabels.judgement == kind && it.result.prediction!!.judgement == kind }
            val precision = ratio(truePositive, predictedCount)
            val recall = ratio(truePositive, row)
            val metricPrefix = "judgement.${kind.name.lowercase(Locale.ROOT)}"
            metrics["$metricPrefix.precision"] = precision
            metrics["$metricPrefix.recall"] = recall
            val precisionInterval = bootstrap.interval(observations) { sample ->
                val predicted = sample.filter { it.result.prediction?.judgement == kind }
                ratio(predicted.count { it.case.adjudicatedLabels.judgement == kind }, predicted.size)
            }
            val recallInterval = bootstrap.interval(observations) { sample ->
                val gold = sample.filter { it.case.adjudicatedLabels.judgement == kind && it.result.prediction != null }
                ratio(gold.count { it.result.prediction!!.judgement == kind }, gold.size)
            }
            output.appendLine(" | $row | $predictedCount | ${formatMetric(precision)}${formatInterval(precisionInterval)} | ${formatMetric(recall)}${formatInterval(recallInterval)} |")
        }
        val abstentions = complete.count { it.result.prediction!!.judgement == EvidenceJudgementKind.INSUFFICIENT }
        val abstentionRate = ratio(abstentions, complete.size)
        metrics["judgement.abstention_rate"] = abstentionRate
        output.appendLine("- INSUFFICIENT abstention rate: $abstentions/${complete.size} (${formatMetric(abstentionRate)})${formatInterval(bootstrap.interval(observations) { sample ->
            val predicted = sample.filter { it.result.prediction != null }
            ratio(predicted.count { it.result.prediction!!.judgement == EvidenceJudgementKind.INSUFFICIENT }, predicted.size)
        })}")
        output.appendLine()
    }

    private fun renderRoleAndScoreMetrics(
        output: StringBuilder,
        observations: List<Observation>,
        bootstrap: Bootstrap,
        metrics: MutableMap<String, Double?>,
    ) {
        val complete = observations.filter { it.result.prediction != null }
        val roleMatches = complete.count { it.case.adjudicatedLabels.evidenceRole == it.result.prediction!!.evidenceRole }
        val roleAgreement = ratio(roleMatches, complete.size)
        metrics["role.agreement"] = roleAgreement
        output.appendLine("## Evidence Role")
        output.appendLine("- Exact agreement: $roleMatches/${complete.size} (${formatMetric(roleAgreement)})${formatInterval(bootstrap.interval(observations) { sample ->
            val predicted = sample.filter { it.result.prediction != null }
            ratio(predicted.count { it.case.adjudicatedLabels.evidenceRole == it.result.prediction!!.evidenceRole }, predicted.size)
        })}")
        output.appendLine()
        output.appendLine("## Ordinal evidence scores")
        output.appendLine("Predicted normalized scores are multiplied by four without rounding for MAE; nearest-integer rounding is used only for exact agreement on the human 0–4 ordinal scale.")
        SCORE_DIMENSIONS.forEach { dimension ->
            val actualAndPredicted = complete.map { observation ->
                dimension.getGold(observation.case.adjudicatedLabels) to dimension.getPrediction(observation.result.prediction!!).times(4.0)
            }
            val exact = actualAndPredicted.count { (gold, predicted) -> gold == predicted.roundToInt() }
            val mae = averageOrNull(actualAndPredicted.map { (gold, predicted) -> abs(gold - predicted) })
            val exactMetricId = "score.${dimension.id}.exact_agreement"
            val maeMetricId = "score.${dimension.id}.mae"
            metrics[exactMetricId] = ratio(exact, actualAndPredicted.size)
            metrics[maeMetricId] = mae
            val exactInterval = bootstrap.interval(observations) { sample ->
                val values = sample.filter { it.result.prediction != null }.map { item ->
                    dimension.getGold(item.case.adjudicatedLabels) == dimension.getPrediction(item.result.prediction!!).times(4.0).roundToInt()
                }
                proportion(values) { it }
            }
            val maeInterval = bootstrap.interval(observations) { sample ->
                averageOrNull(sample.filter { it.result.prediction != null }
                    .map { item -> abs(dimension.getGold(item.case.adjudicatedLabels) - dimension.getPrediction(item.result.prediction!!).times(4.0)) })
            }
            output.appendLine("- `${dimension.id}` exact agreement: $exact/${actualAndPredicted.size} (${formatMetric(metrics.getValue(exactMetricId))})${formatInterval(exactInterval)}; MAE (n=${actualAndPredicted.size}): ${formatMetric(mae)}${formatInterval(maeInterval)}")
        }
        output.appendLine()
    }

    private fun renderConfidenceMetrics(
        output: StringBuilder,
        observations: List<Observation>,
        bootstrap: Bootstrap,
        metrics: MutableMap<String, Double?>,
    ) {
        val complete = observations.filter { it.result.prediction != null }
        val correctness = complete.map { observation ->
            val prediction = observation.result.prediction!!
            prediction.confidence to (observation.case.adjudicatedLabels.judgement == prediction.judgement)
        }
        val brier = averageOrNull(correctness.map { (confidence, correct) -> (confidence - if (correct) 1.0 else 0.0).let { it * it } })
        val ece = expectedCalibrationError(correctness)
        metrics["confidence.brier"] = brier
        metrics["confidence.ece"] = ece
        output.appendLine("## Confidence calibration")
        output.appendLine("- Brier score against adjudicated correctness: ${formatMetric(brier)}${formatInterval(bootstrap.interval(observations) { sample ->
            averageOrNull(sample.filter { it.result.prediction != null }.map { item ->
                val prediction = item.result.prediction!!
                val target = if (item.case.adjudicatedLabels.judgement == prediction.judgement) 1.0 else 0.0
                (prediction.confidence - target).let { it * it }
            })
        })}; n=${correctness.size}")
        output.appendLine("- Expected calibration error (10 bins): ${formatMetric(ece)}${formatInterval(bootstrap.interval(observations) { sample ->
            expectedCalibrationError(sample.filter { it.result.prediction != null }.map { item ->
                val prediction = item.result.prediction!!
                prediction.confidence to (item.case.adjudicatedLabels.judgement == prediction.judgement)
            })
        })}; n=${correctness.size}")
        output.appendLine()
    }

    private fun renderHighRiskErrors(
        output: StringBuilder,
        observations: List<Observation>,
        bootstrap: Bootstrap,
        metrics: MutableMap<String, Double?>,
    ) {
        val errors = observations.filter { observation ->
            val prediction = observation.result.prediction ?: return@filter false
            val gold = observation.case.adjudicatedLabels.judgement
            isHighRiskError(gold, prediction.judgement)
        }
        val completed = observations.filter { it.result.prediction != null }
        metrics["high_risk.error_count"] = errors.size.toDouble().takeIf { completed.isNotEmpty() }
        metrics["high_risk.error_rate"] = ratio(errors.size, completed.size)
        output.appendLine("## High-risk errors")
        output.appendLine("Cross-polarity support/contradiction errors and false decisive predictions against gold UNRELATED/INSUFFICIENT are listed by opaque case ID only.")
        output.appendLine("- Count: ${errors.size}/${completed.size} (${formatMetric(metrics.getValue("high_risk.error_rate"))})${formatInterval(bootstrap.interval(observations) { sample ->
            val predicted = sample.filter { it.result.prediction != null }
            ratio(predicted.count { item -> isHighRiskError(item.case.adjudicatedLabels.judgement, item.result.prediction!!.judgement) }, predicted.size)
        })}")
        if (errors.isEmpty()) {
            output.appendLine("- Cases: none")
        } else {
            errors.forEach { observation ->
                output.appendLine("- `${observation.case.caseId}` (`${observation.case.citedPaperId}`, `${observation.case.sourceLocatorId}`): ${observation.case.adjudicatedLabels.judgement} → ${observation.result.prediction!!.judgement}")
            }
        }
        output.appendLine()
    }

    private fun renderAggregationMetrics(
        output: StringBuilder,
        dataset: LayaEvaluationDataset,
        observations: List<Observation>,
        bootstrap: Bootstrap,
        metrics: MutableMap<String, Double?>,
    ) {
        val byClaimPaper = observations.groupBy { it.case.claimPaperId }
        val expectedOutcomes = dataset.claimPaperOutcomes.filter { it.split == observations.first().case.split }
        output.appendLine("## Claim–Paper aggregation candidates")
        dataset.aggregationCandidates.forEach { candidate ->
            val results = expectedOutcomes.map { outcome ->
                val group = byClaimPaper[outcome.claimPaperId].orEmpty()
                val predictions = group.mapNotNull { it.result.prediction }
                val decision = if (group.isNotEmpty() && predictions.size == group.size) {
                    val judgements = group.map { observation ->
                        observation.result.prediction!!.toJudgement(stableId(observation.case.caseId))
                    }
                    EvidenceAggregationPolicy(candidate.thresholds).aggregate(judgements)
                } else {
                    null
                }
                AggregationObservation(
                    citedPaperId = outcome.citedPaperId,
                    claimPaperId = outcome.claimPaperId,
                    expectedStatus = outcome.expectedStatus.name,
                    expectedConflict = outcome.expectedConflict,
                    predictedStatus = decision?.finalStatus?.name,
                    predictedConflict = decision?.evidenceConflict,
                )
            }
            val complete = results.filter { it.predictedStatus != null }
            val matches = complete.count { it.expectedStatus == it.predictedStatus }
            val statusAccuracy = ratio(matches, complete.size)
            val prefix = "aggregation.${candidate.candidateId}"
            metrics["$prefix.status_accuracy"] = statusAccuracy
            val coverage = ratio(complete.size, results.size)
            metrics["$prefix.coverage"] = coverage
            val coverageInterval = bootstrap.intervalForAggregates(results) { sample ->
                ratio(sample.count { it.predictedStatus != null }, sample.size)
            }
            val conflicts = complete.count { it.expectedConflict == it.predictedConflict }
            val conflictAgreement = ratio(conflicts, complete.size)
            metrics["$prefix.conflict_agreement"] = conflictAgreement
            val conflictAgreementInterval = bootstrap.intervalForAggregates(results) { sample ->
                val done = sample.filter { it.predictedStatus != null }
                ratio(done.count { it.expectedConflict == it.predictedConflict }, done.size)
            }
            val falseDecisive = complete.count { result ->
                result.predictedStatus in DECISIVE_STATUSES && result.predictedStatus != result.expectedStatus
            }
            val falseDecisiveRate = ratio(falseDecisive, complete.size)
            metrics["$prefix.false_decisive_count"] = falseDecisive.toDouble().takeIf { complete.isNotEmpty() }
            metrics["$prefix.false_decisive_rate"] = falseDecisiveRate
            val falseDecisiveInterval = bootstrap.intervalForAggregates(results) { sample ->
                val done = sample.filter { it.predictedStatus != null }
                ratio(done.count { result ->
                    result.predictedStatus in DECISIVE_STATUSES && result.predictedStatus != result.expectedStatus
                }, done.size)
            }
            val insufficientEvidence = complete.count {
                it.predictedStatus == TerminalVerificationStatus.INSUFFICIENT_EVIDENCE.name
            }
            val insufficientEvidenceRate = ratio(insufficientEvidence, complete.size)
            metrics["$prefix.insufficient_evidence_rate"] = insufficientEvidenceRate
            val insufficientEvidenceInterval = bootstrap.intervalForAggregates(results) { sample ->
                val done = sample.filter { it.predictedStatus != null }
                ratio(done.count {
                    it.predictedStatus == TerminalVerificationStatus.INSUFFICIENT_EVIDENCE.name
                }, done.size)
            }
            output.appendLine("### `${candidate.candidateId}`")
            output.appendLine("- Thresholds: direct=${candidate.thresholds.directSupport}, partial=${candidate.thresholds.partialSupport}, contradiction=${candidate.thresholds.contradiction}, comparabilityMargin=${candidate.thresholds.comparabilityMargin}")
            output.appendLine("- Claim–Paper coverage: ${complete.size}/${results.size} (${formatMetric(coverage)})${formatInterval(coverageInterval)}")
            output.appendLine("- Status, conflict, precision, and recall metrics below use completed groups only; incomplete groups are excluded, not labelled.")
            output.appendLine("- Final-status exact agreement: $matches/${complete.size} (${formatMetric(statusAccuracy)})${formatInterval(bootstrap.intervalForAggregates(results) { sample ->
                val done = sample.filter { it.predictedStatus != null }
                ratio(done.count { it.expectedStatus == it.predictedStatus }, done.size)
            })}")
            output.appendLine("- Conflict agreement: $conflicts/${complete.size} (${formatMetric(conflictAgreement)})${formatInterval(conflictAgreementInterval)}")
            output.appendLine("- False decisive outcomes (incorrect SUPPORTED/CONTRADICTED): $falseDecisive/${complete.size} (${formatMetric(falseDecisiveRate)})${formatInterval(falseDecisiveInterval)}")
            output.appendLine("- Predicted INSUFFICIENT_EVIDENCE: $insufficientEvidence/${complete.size} (${formatMetric(insufficientEvidenceRate)})${formatInterval(insufficientEvidenceInterval)}")
            renderAggregationConfusionMatrix(output, complete)
            renderAggregationClassMetrics(output, complete, candidate.candidateId, bootstrap, metrics)
            if (complete.any { it.expectedStatus != it.predictedStatus }) {
                output.appendLine("- Status errors by Claim–Paper ID:")
                complete.filter { it.expectedStatus != it.predictedStatus }.forEach { error ->
                    output.appendLine("  - `${error.claimPaperId}` (`${error.citedPaperId}`): ${error.expectedStatus} → ${error.predictedStatus}")
                }
            }
            output.appendLine("- Outcomes by Cited Paper:")
            results.groupBy(AggregationObservation::citedPaperId).toSortedMap().forEach { (paperId, paperResults) ->
                val done = paperResults.filter { it.predictedStatus != null }
                val correct = done.count { it.expectedStatus == it.predictedStatus }
                val conflicts = done.count { it.expectedConflict == it.predictedConflict }
                output.appendLine("  - `$paperId`: status agreement $correct/${done.size}; conflict agreement $conflicts/${done.size}; incomplete ${paperResults.size - done.size}")
            }
            output.appendLine()
        }
    }

    private fun renderAggregationConfusionMatrix(
        output: StringBuilder,
        observations: List<AggregationObservation>,
    ) {
        output.appendLine("- Final-status confusion matrix (rows=gold, columns=predicted):")
        output.appendLine("  | Gold \\ Predicted | ${CLAIM_PAPER_STATUSES.joinToString(" | ")} |")
        output.appendLine("  |---|${CLAIM_PAPER_STATUSES.joinToString("") { "---|" }}")
        CLAIM_PAPER_STATUSES.forEach { status ->
            val counts = CLAIM_PAPER_STATUSES.map { predicted ->
                observations.count { it.expectedStatus == status && it.predictedStatus == predicted }
            }
            output.appendLine("  | $status | ${counts.joinToString(" | ")} |")
        }
    }

    private fun renderAggregationClassMetrics(
        output: StringBuilder,
        observations: List<AggregationObservation>,
        candidateId: String,
        bootstrap: Bootstrap,
        metrics: MutableMap<String, Double?>,
    ) {
        output.appendLine("- Final-status precision / recall:")
        CLAIM_PAPER_STATUSES.forEach { status ->
            val predicted = observations.filter { it.predictedStatus == status }
            val gold = observations.filter { it.expectedStatus == status }
            val truePositives = observations.count { it.expectedStatus == status && it.predictedStatus == status }
            val precision = ratio(truePositives, predicted.size)
            val recall = ratio(truePositives, gold.size)
            val prefix = "aggregation.$candidateId.status.${status.lowercase(Locale.ROOT)}"
            metrics["$prefix.precision"] = precision
            metrics["$prefix.recall"] = recall
            val precisionInterval = bootstrap.intervalForAggregates(observations) { sample ->
                val predictedSample = sample.filter { it.predictedStatus == status }
                ratio(predictedSample.count { it.expectedStatus == status }, predictedSample.size)
            }
            val recallInterval = bootstrap.intervalForAggregates(observations) { sample ->
                val goldSample = sample.filter { it.expectedStatus == status && it.predictedStatus != null }
                ratio(goldSample.count { it.predictedStatus == status }, goldSample.size)
            }
            output.appendLine("  - `$status`: gold n=${gold.size}, predicted n=${predicted.size}, precision=${formatMetric(precision)}${formatInterval(precisionInterval)}, recall=${formatMetric(recall)}${formatInterval(recallInterval)}")
        }
    }

    private fun renderCitedPaperSummary(
        output: StringBuilder,
        dataset: LayaEvaluationDataset,
        observations: List<Observation>,
    ) {
        output.appendLine("## Case outcomes by Cited Paper")
        val outcomesByPaper = dataset.claimPaperOutcomes.filter { it.split == observations.first().case.split }
            .groupBy(LayaEvaluationDataset.ClaimPaperOutcome::citedPaperId)
        observations.groupBy { it.case.citedPaperId }.toSortedMap().forEach { (paperId, paperCases) ->
            val completed = paperCases.filter { it.result.prediction != null }
            val errors = completed.count { it.case.adjudicatedLabels.judgement != it.result.prediction!!.judgement }
            val expectedConflicts = outcomesByPaper[paperId].orEmpty().count(LayaEvaluationDataset.ClaimPaperOutcome::expectedConflict)
            output.appendLine("- `$paperId`: judgement errors $errors/${completed.size}; incomplete ${paperCases.size - completed.size}; adjudicated conflict Claim–Paper outcomes $expectedConflicts/${outcomesByPaper[paperId].orEmpty().size}")
        }
        output.appendLine()
    }

    private fun renderSafetyBar(
        output: StringBuilder,
        run: LayaEvaluationRun,
        plan: LayaEvaluationPlan?,
        metrics: Map<String, Double?>,
    ) {
        output.appendLine("## Pre-registered numerical safety bar")
        if (run.split != LayaEvaluationDataset.Split.HELD_OUT) {
            output.appendLine("The numerical safety bar is pre-registered for held-out evaluation only and is not evaluated on calibration data.")
            output.appendLine()
            return
        }
        val safetyBar = plan?.safetyBar
        if (safetyBar.isNullOrEmpty()) {
            output.appendLine("No numerical safety bar was pre-registered; this dataset cannot support a production GO.")
            output.appendLine()
            return
        }
        output.appendLine("Pre-registered by `${escapeMarkdown(plan!!.approvedBy)}` at `${plan.approvedAt}`; arithmetic only, not release approval.")
        safetyBar.forEach { criterion ->
            val actual = metrics[criterion.metricId]
            val passes = actual?.let { value ->
                when (criterion.comparison) {
                    LayaEvaluationPlan.Comparison.AT_LEAST -> value >= criterion.threshold
                    LayaEvaluationPlan.Comparison.AT_MOST -> value <= criterion.threshold
                    LayaEvaluationPlan.Comparison.EXACTLY -> abs(value - criterion.threshold) < 1e-9
                }
            }
            val result = when (passes) {
                true -> "MEETS"
                false -> "DOES NOT MEET"
                null -> "NOT EVALUABLE"
            }
            output.appendLine("- `${criterion.metricId}` ${criterion.comparison} ${formatMetric(criterion.threshold)}: actual ${formatMetric(actual)} — $result")
        }
        output.appendLine()
    }

    private fun isHighRiskError(
        gold: EvidenceJudgementKind,
        predicted: EvidenceJudgementKind,
    ): Boolean {
        val goldSupport = gold in SUPPORT_JUDGEMENTS
        val predictedSupport = predicted in SUPPORT_JUDGEMENTS
        val contradictoryPolarity = (gold == EvidenceJudgementKind.CONTRADICTS && predictedSupport) ||
            (predicted == EvidenceJudgementKind.CONTRADICTS && goldSupport)
        val falseDecisive = gold in NON_DECISIVE_GOLD && predicted in DECISIVE_JUDGEMENTS
        val overclaim = gold == EvidenceJudgementKind.PARTIAL_SUPPORT && predicted == EvidenceJudgementKind.DIRECT_SUPPORT
        return contradictoryPolarity || falseDecisive || overclaim
    }

    private fun expectedCalibrationError(values: List<Pair<Double, Boolean>>): Double? {
        if (values.isEmpty()) return null
        return (0 until ECE_BIN_COUNT).sumOf { bin ->
            val members = values.filter { (confidence, _) ->
                val index = floor(confidence * ECE_BIN_COUNT).toInt().coerceAtMost(ECE_BIN_COUNT - 1)
                index == bin
            }
            if (members.isEmpty()) 0.0 else {
                val meanConfidence = members.map { it.first }.average()
                val accuracy = members.count { it.second }.toDouble() / members.size
                members.size.toDouble() / values.size * abs(accuracy - meanConfidence)
            }
        }
    }

    private fun <T> proportion(values: List<T>, predicate: (T) -> Boolean): Double? =
        ratio(values.count(predicate), values.size)

    private fun ratio(numerator: Int, denominator: Int): Double? =
        if (denominator == 0) null else numerator.toDouble() / denominator

    private fun formatMetric(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "not estimable"

    private fun formatInterval(interval: Interval?): String = interval?.let {
        " (95% CI ${formatMetric(it.lower)}–${formatMetric(it.upper)})"
    } ?: " (95% CI not estimable)"

    private fun averageOrNull(values: List<Double>): Double? = values.takeIf(List<Double>::isNotEmpty)?.average()

    private fun stableId(value: String): UUID = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8))

    private fun escapeMarkdown(value: String): String = value
        .replace("`", "\\`")
        .replace("|", "\\|")
        .replace("\r", " ")
        .replace("\n", " ")

    private data class Observation(
        val case: LayaEvaluationDataset.Case,
        val result: LayaEvaluationRun.CaseResult,
    )

    private data class AggregationObservation(
        val citedPaperId: String,
        val claimPaperId: String,
        val expectedStatus: String,
        val expectedConflict: Boolean,
        val predictedStatus: String?,
        val predictedConflict: Boolean?,
    )

    private data class Interval(val lower: Double, val upper: Double)

    private data class ScoreDimension(
        val id: String,
        val getGold: (LayaEvaluationDataset.HumanLabels) -> Int,
        val getPrediction: (LayaEvaluationRun.Prediction) -> Double,
    )

    private class Bootstrap(
        datasetId: String,
        split: LayaEvaluationDataset.Split,
    ) {
        private val seed: Long = ByteBuffer.wrap(
            MessageDigest.getInstance("SHA-256")
                .digest("$datasetId|$split|${LayaEvaluationDataset.UNCERTAINTY_METHOD_ID}".toByteArray(Charsets.UTF_8)),
        ).long

        fun interval(
            observations: List<Observation>,
            statistic: (List<Observation>) -> Double?,
        ): Interval? = bootstrapInterval(observations, { it.case.citedPaperId }, statistic)

        fun intervalForAggregates(
            observations: List<AggregationObservation>,
            statistic: (List<AggregationObservation>) -> Double?,
        ): Interval? = bootstrapInterval(observations, AggregationObservation::citedPaperId, statistic)

        private fun <T> bootstrapInterval(
            observations: List<T>,
            groupKey: (T) -> String,
            statistic: (List<T>) -> Double?,
        ): Interval? {
            val groups = observations.groupBy(groupKey).values.toList()
            if (groups.size < 2) return null
            val random = Random(seed)
            val samples = ArrayList<Double>(BOOTSTRAP_REPLICATES)
            repeat(BOOTSTRAP_REPLICATES) {
                val sample = buildList {
                    repeat(groups.size) { addAll(groups[random.nextInt(groups.size)]) }
                }
                statistic(sample)?.takeIf(Double::isFinite)?.let(samples::add)
            }
            if (samples.size < MIN_VALID_BOOTSTRAP_REPLICATES) return null
            samples.sort()
            return Interval(
                lower = quantile(samples, 0.025),
                upper = quantile(samples, 0.975),
            )
        }

        private fun quantile(sorted: List<Double>, probability: Double): Double {
            val position = probability * (sorted.size - 1)
            val lowerIndex = floor(position).toInt()
            val upperIndex = (lowerIndex + 1).coerceAtMost(sorted.lastIndex)
            val fraction = position - lowerIndex
            return sorted[lowerIndex] + (sorted[upperIndex] - sorted[lowerIndex]) * fraction
        }
    }

    companion object {
        const val BOOTSTRAP_REPLICATES = 2_000
        private const val MIN_VALID_BOOTSTRAP_REPLICATES = 500
        private const val ECE_BIN_COUNT = 10
        private const val CONTEXT_LIMIT_CODE = "SYSTEM_ONE_CONTEXT_LIMIT_EXCEEDED"
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
        private val SUPPORT_JUDGEMENTS = setOf(EvidenceJudgementKind.DIRECT_SUPPORT, EvidenceJudgementKind.PARTIAL_SUPPORT)
        private val NON_DECISIVE_GOLD = setOf(EvidenceJudgementKind.UNRELATED, EvidenceJudgementKind.INSUFFICIENT)
        private val DECISIVE_JUDGEMENTS = setOf(EvidenceJudgementKind.DIRECT_SUPPORT, EvidenceJudgementKind.CONTRADICTS)
        private val DECISIVE_STATUSES = setOf(
            TerminalVerificationStatus.SUPPORTED.name,
            TerminalVerificationStatus.CONTRADICTED.name,
        )
        private val CLAIM_PAPER_STATUSES = listOf(
            TerminalVerificationStatus.SUPPORTED.name,
            TerminalVerificationStatus.PARTIALLY_SUPPORTED.name,
            TerminalVerificationStatus.CONTRADICTED.name,
            TerminalVerificationStatus.INSUFFICIENT_EVIDENCE.name,
        )
        private val SCORE_DIMENSIONS = listOf(
            ScoreDimension("directness", LayaEvaluationDataset.HumanLabels::directness, LayaEvaluationRun.Prediction::directness),
            ScoreDimension("claim_scope_match", LayaEvaluationDataset.HumanLabels::claimScopeMatch, LayaEvaluationRun.Prediction::claimScopeMatch),
            ScoreDimension("study_design_quality", LayaEvaluationDataset.HumanLabels::studyDesignQuality, LayaEvaluationRun.Prediction::studyDesignQuality),
            ScoreDimension("relevance", LayaEvaluationDataset.HumanLabels::relevance, LayaEvaluationRun.Prediction::relevance),
        )
    }
}
