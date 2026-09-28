package com.papertrail.api.evidence.verification.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.document.service.requireActiveAnalysisRun
import com.papertrail.api.evidence.verification.domain.EvidenceAggregationPolicy
import com.papertrail.api.evidence.verification.domain.EvidenceAggregationThresholds
import com.papertrail.api.evidence.verification.domain.AtomicClaimForJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.domain.EvidencePassageForJudgement
import com.papertrail.api.evidence.verification.domain.LayaEvidencePassageSpanPlanner
import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import com.papertrail.api.evidence.verification.provider.LayaSystemOneProviderException
import com.papertrail.api.evidence.verification.provider.LayaSystemOneSettings
import com.papertrail.api.evidence.verification.provider.SystemOneProvider
import com.papertrail.api.evidence.verification.provider.SystemOneRequestPreflight
import com.papertrail.api.evidence.verification.repository.ClaimReferenceVerificationRepository
import com.papertrail.api.evidence.verification.repository.EvidenceJudgementRepository
import com.papertrail.api.evidence.verification.repository.EvidencePassageSpanRepository
import com.papertrail.api.evidence.verification.repository.PersistedEvidencePassageSpan
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallPayload
import com.papertrail.api.infrastructure.providers.SYSTEM_ONE_ROLE
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class EvidenceVerificationService(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val providerCallGate: ProviderCallGate,
    private val systemOneProviders: List<SystemOneProvider>,
    private val judgementRepository: EvidenceJudgementRepository,
    private val spanRepository: EvidencePassageSpanRepository,
    private val spanPlanner: LayaEvidencePassageSpanPlanner,
    private val verificationRepository: ClaimReferenceVerificationRepository,
) {
    fun verifyReference(analysisRunId: UUID, bibliographyEntryId: UUID) {
        val configuration = loadConfiguration(analysisRunId)
        val aggregationSnapshot = configuration.aggregation
        val evaluationOnly = aggregationSnapshot.executionStatus == "NOT_RUN" &&
            configuration.systemOne.provider == LayaSystemOneSettings.PROVIDER_ID
        if (aggregationSnapshot.executionStatus == "NOT_RUN" && !evaluationOnly) return
        require(aggregationSnapshot.executionStatus == "PENDING" || evaluationOnly) {
            "Conflict-aware verification is not configured for this Analysis Run."
        }
        if (!evaluationOnly) {
            require(aggregationSnapshot.verificationPolicyVersion == EvidenceJudgement.STRENGTH_RUBRIC_VERSION) {
                "The pinned evidence-strength rubric is unavailable."
            }
            require(aggregationSnapshot.aggregationPolicyVersion == EvidenceAggregationPolicy.POLICY_VERSION) {
                "The pinned evidence aggregation policy is unavailable."
            }
        }
        val policy = if (evaluationOnly) null else EvidenceAggregationPolicy(
            thresholdsFrom(aggregationSnapshot.thresholds),
        )
        val providerSelection = configuration.systemOne
        val provider = systemOneProviders.singleOrNull {
            it.providerId == providerSelection.provider &&
                it.version == providerSelection.version &&
                it.modelId == providerSelection.model
        } ?: throw IllegalStateException("The pinned System One provider is unavailable.")
        val layaPreflight = if (provider.providerId == LayaSystemOneSettings.PROVIDER_ID) {
            provider as? SystemOneRequestPreflight
                ?: throw IllegalStateException("The pinned Laya tokenizer preflight is unavailable.")
        } else {
            null
        }

        judgementRepository.pendingRequests(analysisRunId, bibliographyEntryId).forEach { pending ->
            val requestedIds = pending.request.evidencePassages.map(EvidencePassageForJudgement::id).toSet()
            val passageBudgets = if (requestedIds.isNotEmpty() && layaPreflight != null) {
                pending.request.evidencePassages.associate { passage ->
                    passage.id to preflightThroughProviderGate(
                        analysisRunId,
                        layaPreflight,
                        provider,
                        configuration,
                        pending.request.atomicClaim,
                        passage,
                    )
                }
            } else {
                emptyMap()
            }
            val overLimitPassages = pending.request.evidencePassages.filter { passage ->
                passageBudgets[passage.id]?.any { it > LayaSystemOneSettings.MODEL_CONTEXT_TOKENS } == true
            }
            if (overLimitPassages.isNotEmpty()) {
                processSplitPassages(
                    analysisRunId = analysisRunId,
                    bibliographyEntryId = bibliographyEntryId,
                    verificationId = pending.verificationId,
                    request = pending.request,
                    provider = provider,
                    preflight = requireNotNull(layaPreflight),
                    configuration = configuration,
                    passageBudgets = passageBudgets,
                    overLimitPassages = overLimitPassages,
                )
                return@forEach
            }

            val providerJudgements = if (requestedIds.isEmpty()) {
                emptyList()
            } else {
                jdbc.requireActiveAnalysisRun(analysisRunId)
                try {
                    evaluateThroughProviderGate(provider, configuration, pending.request)
                } catch (exception: LayaSystemOneProviderException) {
                    val failureReasonCode = exception.failureReasonCode ?: throw exception
                    verificationRepository.failVerification(pending.verificationId, failureReasonCode)
                    return@forEach
                }
            }
            require(providerJudgements.map(EvidenceJudgement::evidenceCandidateId).toSet() == requestedIds &&
                providerJudgements.size == requestedIds.size
            ) { "System One must return exactly one judgement for each requested Evidence Passage." }
            val persisted = judgementRepository.persistAndLoad(
                analysisRunId = analysisRunId,
                bibliographyEntryId = bibliographyEntryId,
                verificationId = pending.verificationId,
                providerId = provider.providerId,
                modelId = provider.modelId,
                providerVersion = provider.version,
                judgements = providerJudgements,
            )
            require(persisted.map(EvidenceJudgement::evidenceCandidateId).toSet() == requestedIds &&
                persisted.size == requestedIds.size
            ) { "Persisted System One judgements do not match the requested Evidence Passages." }
            if (evaluationOnly) return@forEach
            completeVerification(
                pending.verificationId,
                requireNotNull(policy),
                requireNotNull(aggregationSnapshot.aggregationPolicyVersion),
                persisted,
            )
        }
    }

    fun failFullText(analysisRunId: UUID, bibliographyEntryId: UUID, reason: String) {
        spanRepository.failPendingForReference(analysisRunId, bibliographyEntryId, reason)
        verificationRepository.failFullText(analysisRunId, bibliographyEntryId, reason)
    }

    private fun processSplitPassages(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        verificationId: UUID,
        request: SemanticJudgementRequest,
        provider: SystemOneProvider,
        preflight: SystemOneRequestPreflight,
        configuration: AnalysisConfigurationSnapshot,
        passageBudgets: Map<UUID, List<Int>>,
        overLimitPassages: List<EvidencePassageForJudgement>,
    ) {
        val planned = overLimitPassages.map { passage ->
            val definitions = spanPlanner.plan(
                passage = passage,
                parentTokenCounts = requireNotNull(passageBudgets[passage.id]),
            ) { text ->
                preflightThroughProviderGate(
                    analysisRunId,
                    preflight,
                    provider,
                    configuration,
                    request.atomicClaim,
                    passage.copy(text = text),
                )
            }
            passage to spanRepository.prepare(
                analysisRunId = analysisRunId,
                bibliographyEntryId = bibliographyEntryId,
                verificationId = verificationId,
                evidenceCandidateId = passage.id,
                definitions = definitions,
                providerId = provider.providerId,
                modelId = requireNotNull(provider.modelId),
                providerVersion = provider.version,
                rubricVersion = EvidenceJudgement.STRENGTH_RUBRIC_VERSION,
            )
        }
        val fittingPassages = request.evidencePassages.filter { passage ->
            passageBudgets[passage.id]?.all { it <= LayaSystemOneSettings.MODEL_CONTEXT_TOKENS } == true
        }
        val alreadyJudgedIds = judgementRepository.persistedEvidenceCandidateIds(
            verificationId,
            fittingPassages.map(EvidencePassageForJudgement::id).toSet(),
        )
        val unjudgedFittingPassages = fittingPassages.filterNot { it.id in alreadyJudgedIds }
        if (unjudgedFittingPassages.isNotEmpty()) {
            jdbc.requireActiveAnalysisRun(analysisRunId)
            val fittingRequest = request.copy(evidencePassages = unjudgedFittingPassages)
            val judgements = evaluateThroughProviderGate(provider, configuration, fittingRequest)
            val expectedIds = unjudgedFittingPassages.map(EvidencePassageForJudgement::id).toSet()
            require(judgements.map(EvidenceJudgement::evidenceCandidateId).toSet() == expectedIds &&
                judgements.size == expectedIds.size
            ) { "System One must return exactly one judgement for each fitting Evidence Passage." }
            val persisted = judgementRepository.persistAndLoad(
                analysisRunId = analysisRunId,
                bibliographyEntryId = bibliographyEntryId,
                verificationId = verificationId,
                providerId = provider.providerId,
                modelId = provider.modelId,
                providerVersion = provider.version,
                judgements = judgements,
            ).filter { it.evidenceCandidateId in expectedIds }
            require(persisted.map(EvidenceJudgement::evidenceCandidateId).toSet() == expectedIds &&
                persisted.size == expectedIds.size
            ) { "Persisted System One judgements do not match the fitting Evidence Passages." }
        }
        planned.forEach { (parentPassage, spans) ->
            evaluatePendingSpans(
                analysisRunId = analysisRunId,
                parentPassage = parentPassage,
                atomicClaim = request.atomicClaim,
                spans = spans,
                provider = provider,
                configuration = configuration,
            )
        }
        if (planned.any { (_, spans) -> spans.any { it.status == "INCOMPLETE" } }) {
            verificationRepository.failVerification(verificationId, SYSTEM_ONE_INCOMPLETE_REASON)
        }
    }

    private fun evaluatePendingSpans(
        analysisRunId: UUID,
        parentPassage: EvidencePassageForJudgement,
        atomicClaim: AtomicClaimForJudgement,
        spans: List<PersistedEvidencePassageSpan>,
        provider: SystemOneProvider,
        configuration: AnalysisConfigurationSnapshot,
    ) {
        spans.forEach { span ->
            if (span.status == "COMPLETED" || span.status == "INCOMPLETE") return@forEach
            jdbc.requireActiveAnalysisRun(analysisRunId)
            if (span.status == "FAILED") spanRepository.retryFailed(span.id)
            val definition = span.definition
            val request = SemanticJudgementRequest(
                atomicClaim = atomicClaim,
                evidencePassages = listOf(
                    EvidencePassageForJudgement(
                        id = span.id,
                        text = parentPassage.text.substring(definition.contextStartOffset, definition.contextEndOffset),
                        sectionHeading = parentPassage.sectionHeading,
                    ),
                ),
            )
            val judgement = try {
                evaluateThroughProviderGate(provider, configuration, request).singleOrNull()
                    ?.also { require(it.evidenceCandidateId == span.id) { "Laya returned a judgement for a different span." } }
                    ?: throw IllegalStateException("Laya must return exactly one judgement for each Evidence Passage span.")
            } catch (exception: LayaSystemOneProviderException) {
                exception.failureReasonCode?.let { spanRepository.fail(span.id, it) }
                throw exception
            }
            spanRepository.complete(span.id, judgement)
        }
    }

    private fun preflightThroughProviderGate(
        analysisRunId: UUID,
        preflight: SystemOneRequestPreflight,
        provider: SystemOneProvider,
        configuration: AnalysisConfigurationSnapshot,
        claim: AtomicClaimForJudgement,
        passage: EvidencePassageForJudgement,
    ): List<Int> {
        jdbc.requireActiveAnalysisRun(analysisRunId)
        val request = SemanticJudgementRequest(
            atomicClaim = claim,
            evidencePassages = listOf(passage),
        )
        val payload = payload(request)
        val counts = providerCallGate.call(
            role = SYSTEM_ONE_ROLE,
            providerId = provider.providerId,
            payload = payload,
            configuration = configuration,
        ) { preflight.tokenCounts(claim.text, passage) }
        require(counts.size == QUESTION_SEQUENCE_COUNT && counts.all { it >= 0 }) {
            "Laya preflight must return token counts for all six complete question sequences."
        }
        return counts
    }

    private fun evaluateThroughProviderGate(
        provider: SystemOneProvider,
        configuration: AnalysisConfigurationSnapshot,
        request: SemanticJudgementRequest,
    ): List<EvidenceJudgement> {
        val result = providerCallGate.call(
            role = SYSTEM_ONE_ROLE,
            providerId = provider.providerId,
            payload = payload(request),
            configuration = configuration,
        ) { provider.evaluate(request) }
        return result.evidenceJudgements
    }

    private fun payload(request: SemanticJudgementRequest): ProviderCallPayload = ProviderCallPayload(
        mapOf(
            DataCategory.ATOMIC_CLAIMS to objectMapper.valueToTree(request.atomicClaim),
            DataCategory.EVIDENCE_PASSAGES to objectMapper.valueToTree(request.evidencePassages),
        ),
    )

    private fun completeVerification(
        verificationId: UUID,
        policy: EvidenceAggregationPolicy,
        aggregatorVersion: String,
        persisted: List<EvidenceJudgement>,
    ) {
        val decision = policy.aggregate(persisted)
        val completed = verificationRepository.complete(
            verificationId = verificationId,
            decision = decision,
            aggregatorVersion = aggregatorVersion,
        )
        if (!completed && !verificationRepository.isCompleted(verificationId)) {
            throw IllegalStateException("Claim–Reference Verification could not be completed after aggregation.")
        }
    }

    private fun thresholdsFrom(thresholds: Map<String, Double>?): EvidenceAggregationThresholds {
        requireNotNull(thresholds) { "The Analysis Run has no pinned evidence aggregation thresholds." }
        require(thresholds.keys == EvidenceAggregationThresholds.REQUIRED_KEYS) {
            "The Analysis Run aggregation thresholds are incomplete or unsupported."
        }
        return EvidenceAggregationThresholds(
            directSupport = thresholds.getValue(EvidenceAggregationThresholds.DIRECT_SUPPORT),
            partialSupport = thresholds.getValue(EvidenceAggregationThresholds.PARTIAL_SUPPORT),
            contradiction = thresholds.getValue(EvidenceAggregationThresholds.CONTRADICTION),
            comparabilityMargin = thresholds.getValue(EvidenceAggregationThresholds.COMPARABILITY_MARGIN),
        )
    }

    private fun loadConfiguration(analysisRunId: UUID): AnalysisConfigurationSnapshot = jdbc.query(
        "SELECT configuration_snapshot::text FROM analysis_runs WHERE id = ? AND status = 'PROCESSING'",
        { rs, _ -> objectMapper.readValue(rs.getString(1), AnalysisConfigurationSnapshot::class.java) },
        analysisRunId,
    ).firstOrNull() ?: throw IllegalStateException("Analysis Run is not available for semantic verification.")

    companion object {
        private const val QUESTION_SEQUENCE_COUNT = 6
        private const val SYSTEM_ONE_INCOMPLETE_REASON = "SYSTEM_ONE_INCOMPLETE"
    }
}
