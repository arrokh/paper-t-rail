package com.papertrail.api.scholarly.references.service

import com.papertrail.api.analysis.configuration.ReferenceResolutionSnapshot
import com.papertrail.api.document.repository.SourceDocumentRepository
import com.papertrail.api.document.service.SourceDocumentEgressHandoffLock
import com.papertrail.api.evidence.verification.repository.ClaimReferenceVerificationRepository
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookupFactory
import com.papertrail.api.scholarly.references.model.StoredBibliographyReference
import com.papertrail.api.scholarly.references.attempts.service.ReferenceResolutionChainService
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainClaimResult
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionQueueFailureArbitration
import com.papertrail.api.scholarly.references.report.ReferenceResolutionSummary
import com.papertrail.api.scholarly.references.repository.ReferenceResolutionRepository
import com.papertrail.api.scholarly.references.resolver.ConservativeReferenceResolver
import com.papertrail.api.scholarly.references.resolver.ScholarlyMetadataMatcher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Service
class ReferenceResolutionService(
    private val sourceDocumentRepository: SourceDocumentRepository,
    private val sourceDocumentEgressHandoffLock: SourceDocumentEgressHandoffLock,
    private val transactionTemplate: TransactionTemplate,
    private val claimReferenceVerificationRepository: ClaimReferenceVerificationRepository,
    private val repository: ReferenceResolutionRepository,
    private val lookupFactories: List<ScholarlyMetadataLookupFactory>,
    private val chainService: ReferenceResolutionChainService,
    private val providerChainCoordinator: ReferenceResolutionProviderChainCoordinator,
) {
    fun isResolutionConfigured(analysisRunId: UUID): Boolean {
        val context = loadRun(analysisRunId) ?: throw IllegalArgumentException("Analysis Run not found for reference resolution.")
        val referenceResolution = context.configuration.referenceResolution
        return referenceResolution.providerChain != null || referenceResolution.isConfigured()
    }

    fun claimProviderChainEntry(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
    ): ReferenceResolutionChainClaimResult? {
        val context = loadRun(analysisRunId) ?: return null
        if (context.configuration.referenceResolution.providerChain == null) return null
        return chainService.claim(analysisRunId, bibliographyEntryId)
    }

    fun arbitrateQueueFailure(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        persistFailure: () -> Unit,
        persistResolved: () -> Unit,
    ): ReferenceResolutionQueueFailureArbitration {
        val context = loadRun(analysisRunId)
        if (context?.configuration?.referenceResolution?.providerChain == null) {
            persistFailure()
            return ReferenceResolutionQueueFailureArbitration.FailureRecorded
        }
        return chainService.arbitrateQueueFailure(
            analysisRunId,
            bibliographyEntryId,
            persistFailure,
            persistResolved,
        )
    }

    fun resolveEntry(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        chainClaim: ReferenceResolutionChainClaimResult.Claimed? = null,
        onChainFinalized: ((ProviderChainTerminalResult) -> Unit)? = null,
    ): ProviderChainResolutionDisposition? {
        val context = loadRun(analysisRunId) ?: throw IllegalArgumentException("Analysis Run not found for reference resolution.")
        val configuration = context.configuration.referenceResolution
        require(configuration.providerChain != null || configuration.isConfigured()) {
            "Reference resolution is not configured for this Analysis Run."
        }
        val stored = repository.pendingEntry(analysisRunId, bibliographyEntryId)
        if (stored == null) {
            if (repository.entryExists(analysisRunId, bibliographyEntryId) &&
                repository.resolutionExists(analysisRunId, bibliographyEntryId)
            ) return null
            throw IllegalArgumentException("Bibliography Entry is not part of this Analysis Run or has no pending resolution.")
        }
        configuration.providerChain?.let {
            val claimed = requireNotNull(chainClaim) { "Provider-chain resolution requires a database ownership claim." }
            val finalizeEvent = requireNotNull(onChainFinalized) { "Provider-chain resolution requires atomic queue-event finalization." }
            return providerChainCoordinator.resolveAndFinalize(
                authorization = claimed.authorization,
                configuration = context.configuration,
                reference = stored.toBibliographyReference(),
                recoveredReservation = claimed.recoveredReservation,
                uncertainAttempt = claimed.uncertainAttempt,
            ) { terminal ->
                val decision = requireNotNull(terminal.decision)
                val metadataProviderId = terminal.metadataOriginProviderId ?: PROVIDER_CHAIN_RESULT_PROVIDER_ID
                val canonicalPaperId = repository.save(analysisRunId, stored, decision, metadataProviderId)
                claimReferenceVerificationRepository.applyResolution(
                    analysisRunId = analysisRunId,
                    bibliographyEntryId = stored.id,
                    status = decision.status,
                    reason = decision.reasonCode,
                    canonicalPaperId = canonicalPaperId,
                )
                finalizeEvent(terminal)
            }
        }
        require(chainClaim == null && onChainFinalized == null) {
            "A provider-chain ownership claim cannot be used with scalar reference resolution."
        }
        val providerId = requireNotNull(configuration.provider?.provider)
        val threshold = requireNotNull(configuration.confidenceThreshold)
        val factory = lookupFactories.singleOrNull { it.providerId == providerId }
            ?: throw IllegalStateException("Configured scholarly metadata provider is unavailable.")
        val resolver = ConservativeReferenceResolver(
            scholarlyMetadata = factory.forRun(context.configuration),
            matcher = ScholarlyMetadataMatcher(
                threshold = threshold,
                ambiguityMargin = ScholarlyMetadataMatcher.AMBIGUITY_MARGIN,
                policyVersion = configuration.scorePolicyVersion ?: ScholarlyMetadataMatcher.LEGACY_POLICY_VERSION,
            ),
        )
        sourceDocumentEgressHandoffLock.acquireForRun(analysisRunId).use {
            sourceDocumentRepository.requireActiveAnalysisRun(analysisRunId)
            val decision = resolver.resolve(
                BibliographyReference(
                    title = stored.title,
                    authors = stored.authors,
                    year = stored.year,
                    doi = stored.doi,
                    referenceType = stored.referenceType,
                    doiIdentifiers = stored.doiIdentifiers,
                ),
            )
            transactionTemplate.executeWithoutResult {
                sourceDocumentRepository.lockActiveAnalysisRun(analysisRunId)
                val canonicalPaperId = repository.save(analysisRunId, stored, decision, providerId)
                claimReferenceVerificationRepository.applyResolution(
                    analysisRunId = analysisRunId,
                    bibliographyEntryId = stored.id,
                    status = decision.status,
                    reason = decision.reasonCode,
                    canonicalPaperId = canonicalPaperId,
                )
            }
        }
        return null
    }

    fun failResolution(analysisRunId: UUID, bibliographyEntryId: UUID, reason: String) {
        claimReferenceVerificationRepository.failReference(analysisRunId, bibliographyEntryId, reason)
    }

    fun isEntryResolved(analysisRunId: UUID, bibliographyEntryId: UUID): Boolean =
        repository.resolutionExists(analysisRunId, bibliographyEntryId)

    fun summary(analysisRunId: UUID): ReferenceResolutionSummary =
        ReferenceResolutionSummary.fromEntries(repository.reportEntries(analysisRunId))

    private fun ReferenceResolutionSnapshot.isConfigured(): Boolean =
        provider?.provider != null && !scorePolicyVersion.isNullOrBlank() && confidenceThreshold != null && executionStatus != "NOT_RUN"

    private fun StoredBibliographyReference.toBibliographyReference() = BibliographyReference(
        title = title,
        authors = authors,
        year = year,
        doi = doi,
        referenceType = referenceType,
        doiIdentifiers = doiIdentifiers,
    )

    private fun loadRun(analysisRunId: UUID): ReferenceResolutionRepository.RunResolutionContext? = repository.loadRun(analysisRunId)

    companion object {
        private const val PROVIDER_CHAIN_RESULT_PROVIDER_ID = "provider-chain"
    }
}
