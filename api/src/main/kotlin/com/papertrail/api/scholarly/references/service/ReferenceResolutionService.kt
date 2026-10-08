package com.papertrail.api.scholarly.references.service

import com.papertrail.api.analysis.configuration.ReferenceResolutionSnapshot
import com.papertrail.api.document.repository.SourceDocumentRepository
import com.papertrail.api.evidence.verification.repository.ClaimReferenceVerificationRepository
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookupFactory
import com.papertrail.api.scholarly.references.report.ReferenceResolutionSummary
import com.papertrail.api.scholarly.references.repository.ReferenceResolutionRepository
import com.papertrail.api.scholarly.references.resolver.ConservativeReferenceResolver
import com.papertrail.api.scholarly.references.resolver.ScholarlyMetadataMatcher
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
class ReferenceResolutionService(
    private val sourceDocumentRepository: SourceDocumentRepository,
    private val transactionTemplate: TransactionTemplate,
    private val claimReferenceVerificationRepository: ClaimReferenceVerificationRepository,
    private val repository: ReferenceResolutionRepository,
    private val lookupFactories: List<ScholarlyMetadataLookupFactory>,
) {
    fun isResolutionConfigured(analysisRunId: UUID): Boolean {
        val context = loadRun(analysisRunId) ?: throw IllegalArgumentException("Analysis Run not found for reference resolution.")
        return context.configuration.referenceResolution.isConfigured()
    }

    fun resolveEntry(analysisRunId: UUID, bibliographyEntryId: UUID) {
        val context = loadRun(analysisRunId) ?: throw IllegalArgumentException("Analysis Run not found for reference resolution.")
        val configuration = context.configuration.referenceResolution
        require(configuration.isConfigured()) { "Reference resolution is not configured for this Analysis Run." }
        val stored = repository.pendingEntry(analysisRunId, bibliographyEntryId)
        if (stored == null) {
            if (repository.entryExists(analysisRunId, bibliographyEntryId) &&
                repository.resolutionExists(analysisRunId, bibliographyEntryId)
            ) return
            throw IllegalArgumentException("Bibliography Entry is not part of this Analysis Run or has no pending resolution.")
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
        sourceDocumentRepository.requireActiveAnalysisRun(analysisRunId)
        val decision = resolver.resolve(
            BibliographyReference(stored.title, stored.authors, stored.year, stored.doi, stored.referenceType),
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

    fun failResolution(analysisRunId: UUID, bibliographyEntryId: UUID, reason: String) {
        claimReferenceVerificationRepository.failReference(analysisRunId, bibliographyEntryId, reason)
    }

    fun summary(analysisRunId: UUID): ReferenceResolutionSummary =
        ReferenceResolutionSummary.fromEntries(repository.reportEntries(analysisRunId))

    private fun ReferenceResolutionSnapshot.isConfigured(): Boolean =
        provider?.provider != null && !scorePolicyVersion.isNullOrBlank() && confidenceThreshold != null && executionStatus != "NOT_RUN"

    private fun loadRun(analysisRunId: UUID): ReferenceResolutionRepository.RunResolutionContext? = repository.loadRun(analysisRunId)
}
