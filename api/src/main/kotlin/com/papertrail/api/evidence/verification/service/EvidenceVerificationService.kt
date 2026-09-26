package com.papertrail.api.evidence.verification.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.evidence.verification.domain.EvidenceAggregationPolicy
import com.papertrail.api.evidence.verification.domain.EvidenceAggregationThresholds
import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import com.papertrail.api.evidence.verification.provider.SystemOneProvider
import com.papertrail.api.evidence.verification.repository.ClaimReferenceVerificationRepository
import com.papertrail.api.evidence.verification.repository.EvidenceJudgementRepository
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
    private val verificationRepository: ClaimReferenceVerificationRepository,
) {
    fun verifyReference(analysisRunId: UUID, bibliographyEntryId: UUID) {
        val configuration = loadConfiguration(analysisRunId)
        val aggregationSnapshot = configuration.aggregation
        if (aggregationSnapshot.executionStatus == "NOT_RUN") return
        require(aggregationSnapshot.executionStatus == "PENDING") {
            "Conflict-aware verification is not configured for this Analysis Run."
        }
        require(aggregationSnapshot.verificationPolicyVersion == EvidenceJudgement.STRENGTH_RUBRIC_VERSION) {
            "The pinned evidence-strength rubric is unavailable."
        }
        require(aggregationSnapshot.aggregationPolicyVersion == EvidenceAggregationPolicy.POLICY_VERSION) {
            "The pinned evidence aggregation policy is unavailable."
        }
        val thresholds = thresholdsFrom(aggregationSnapshot.thresholds)
        val providerSelection = configuration.systemOne
        val provider = systemOneProviders.singleOrNull {
            it.providerId == providerSelection.provider &&
                it.version == providerSelection.version &&
                it.modelId == providerSelection.model
        } ?: throw IllegalStateException("The pinned System One provider is unavailable.")
        val policy = EvidenceAggregationPolicy(thresholds)

        judgementRepository.pendingRequests(analysisRunId, bibliographyEntryId).forEach { pending ->
            val requestedIds = pending.request.evidencePassages.map { it.id }.toSet()
            val providerJudgements = if (requestedIds.isEmpty()) {
                emptyList()
            } else {
                evaluateThroughProviderGate(provider, configuration, pending.request)
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
            val decision = policy.aggregate(persisted)
            val completed = verificationRepository.complete(
                verificationId = pending.verificationId,
                decision = decision,
                aggregatorVersion = aggregationSnapshot.aggregationPolicyVersion,
            )
            if (!completed && !verificationRepository.isCompleted(pending.verificationId)) {
                throw IllegalStateException("Claim–Reference Verification could not be completed after aggregation.")
            }
        }
    }

    private fun evaluateThroughProviderGate(
        provider: SystemOneProvider,
        configuration: AnalysisConfigurationSnapshot,
        request: SemanticJudgementRequest,
    ): List<EvidenceJudgement> {
        val payload = ProviderCallPayload(
            mapOf(
                DataCategory.ATOMIC_CLAIMS to objectMapper.valueToTree(request.atomicClaim),
                DataCategory.EVIDENCE_PASSAGES to objectMapper.valueToTree(request.evidencePassages),
            ),
        )
        val result = providerCallGate.call(
            role = SYSTEM_ONE_ROLE,
            providerId = provider.providerId,
            payload = payload,
            configuration = configuration,
        ) { provider.evaluate(request) }
        return result.evidenceJudgements
    }

    private fun thresholdsFrom(thresholds: Map<String, Double>?): EvidenceAggregationThresholds {
        requireNotNull(thresholds) { "The Analysis Run has no pinned evidence aggregation thresholds." }
        val defaults = EvidenceAggregationThresholds.CALIBRATED_V1
        require(thresholds.keys == defaults.asMap().keys) { "The Analysis Run aggregation thresholds are incomplete or unsupported." }
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
}
