package com.papertrail.api.scholarly.acquisition.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.document.storage.SourceDocumentObjectStore
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.providers.ProviderCallRejectedException
import com.papertrail.api.scholarly.acquisition.client.OpenAccessProvider
import com.papertrail.api.scholarly.acquisition.client.OpenAccessProviderFactory
import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessPolicy
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessReason
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessStatus
import com.papertrail.api.scholarly.acquisition.domain.LegalOpenAccessLocationPolicy
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessLocation
import com.papertrail.api.evidence.verification.repository.ClaimReferenceVerificationRepository
import com.papertrail.api.scholarly.acquisition.repository.CitedPaperAccessRepository
import com.papertrail.api.scholarly.acquisition.repository.ResolvedCitedReference
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.document.validation.DocumentLanguageDetector
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Service
class CitedPaperAccessService(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
    private val claimReferenceVerificationRepository: ClaimReferenceVerificationRepository,
    private val repository: CitedPaperAccessRepository,
    private val objectStore: SourceDocumentObjectStore,
    private val languageDetector: DocumentLanguageDetector,
    private val textExtractor: CitedPaperTextExtractor,
    private val providerFactories: List<OpenAccessProviderFactory>,
) {
    private val locationPolicy = LegalOpenAccessLocationPolicy()
    private val accessPolicy = CitedPaperAccessPolicy()

    fun failAccess(analysisRunId: UUID, bibliographyEntryId: UUID, reason: String) {
        claimReferenceVerificationRepository.failReference(analysisRunId, bibliographyEntryId, reason)
    }

    fun acquire(analysisRunId: UUID, bibliographyEntryId: UUID) {
        if (repository.accessExists(analysisRunId, bibliographyEntryId)) return
        val context = loadRun(analysisRunId) ?: throw IllegalArgumentException("Analysis Run not found for cited-paper access.")
        require(context.status == "PROCESSING") { "Analysis Run is not accepting cited-paper access work." }
        val reference = repository.resolvedReference(analysisRunId, bibliographyEntryId)
            ?: throw IllegalArgumentException("Bibliography Entry is not resolved to a Canonical Paper.")
        val providerSelection = context.configuration.openAccess
        val providerFactory = providerFactories.singleOrNull { it.providerId == providerSelection.provider }
            ?: throw IllegalStateException("Configured open-access provider is unavailable.")
        val provider = providerFactory.forRun(context.configuration)
        val metadata = BibliographyReference(
            title = reference.title,
            authors = reference.authors,
            year = reference.year,
            doi = reference.doi,
            referenceType = reference.referenceType,
        )
        val discovery = provider.discover(metadata)
        val permittedLocations = discovery?.locations.orEmpty().filter(locationPolicy::isUsable)
        val acquiredText = acquireExtractableFullText(provider, permittedLocations)
        val acquired = acquiredText?.first
        val extractedText = acquiredText?.second
        val languageDetection = extractedText?.let(languageDetector::detect)
        val language = languageDetection?.language
        val confidenceThreshold = context.configuration.validationLimits.minimumLanguageConfidence
        val supportedLanguage = languageDetection?.takeIf { it.confidence >= confidenceThreshold }?.language
        val metadataAvailable = discovery?.metadataAvailable == true || discovery?.abstractAvailable == true ||
            discovery?.locations?.isNotEmpty() == true || acquired != null
        val decision = accessPolicy.decide(
            metadataAvailable = metadataAvailable,
            abstractAvailable = discovery?.abstractAvailable == true,
            fullTextAvailable = acquired != null,
            language = supportedLanguage,
        )
        val accessReason = when {
            permittedLocations.isNotEmpty() && acquired == null -> CitedPaperAccessReason.FULL_TEXT_ACQUISITION_FAILED
            decision.accessStatus == CitedPaperAccessStatus.ABSTRACT_ONLY -> CitedPaperAccessReason.ABSTRACT_ONLY
            decision.accessStatus == CitedPaperAccessStatus.METADATA_ONLY -> CitedPaperAccessReason.NO_LEGAL_FULL_TEXT_LOCATION
            decision.accessStatus == CitedPaperAccessStatus.UNAVAILABLE -> CitedPaperAccessReason.NO_ACCESSIBLE_METADATA
            else -> null
        }
        val provenanceLocation = acquired?.location ?: permittedLocations.firstOrNull()
        val contentHash = acquired?.bytes?.let(::sha256Hex)
        val objectKey = if (acquired != null && contentHash != null) {
            val extension = if (acquired.mediaType.substringBefore(';').trim().equals("application/pdf", ignoreCase = true)) "pdf" else "txt"
            "papers/${reference.canonicalPaperId}/analysis-runs/$analysisRunId/references/$bibliographyEntryId/full-text-$contentHash.$extension"
        } else {
            null
        }
        try {
            if (acquired != null && objectKey != null) objectStore.put(objectKey, acquired.bytes, acquired.mediaType)
            val inserted = transactionTemplate.execute {
                val saved = repository.save(
                    analysisRunId = analysisRunId,
                    reference = reference,
                    discovery = discovery,
                    decision = decision,
                    accessReason = accessReason,
                    locationUrl = provenanceLocation?.url,
                    license = provenanceLocation?.license,
                    version = provenanceLocation?.version,
                    hostType = provenanceLocation?.hostType,
                    providerId = providerSelection.provider,
                    discoveredAt = discovery?.discoveredAt ?: Instant.now(),
                    objectKey = objectKey,
                    fullText = acquired,
                    language = language,
                    languageDetectorVersion = context.configuration.languageDetector.version.takeIf { acquired != null },
                )
                if (saved) {
                    claimReferenceVerificationRepository.applyAccessDecision(
                        analysisRunId = analysisRunId,
                        bibliographyEntryId = reference.bibliographyEntryId,
                        canonicalPaperId = reference.canonicalPaperId,
                        decision = decision,
                    )
                }
                saved
            } ?: false
            if (!inserted && objectKey != null && !repository.isObjectKeyReferenced(objectKey)) {
                objectStore.delete(objectKey)
            }
        } catch (exception: Exception) {
            cleanupObjectIfUnreferenced(analysisRunId, objectKey)
            throw exception
        }
    }

    private fun acquireExtractableFullText(
        provider: OpenAccessProvider,
        locations: List<OpenAccessLocation>,
    ): Pair<AcquiredFullText, String>? {
        for (location in locations.take(MAX_LOCATIONS_TO_TRY)) {
            try {
                val acquired = provider.fetch(location)
                return acquired to textExtractor.extract(acquired)
            } catch (exception: ProviderCallRejectedException) {
                throw exception
            } catch (_: Exception) {
                // Try the next location from the same legal discovery result.
            }
        }
        return null
    }

    private fun loadRun(analysisRunId: UUID): AccessRunContext? = jdbc.query(
        "SELECT status, configuration_snapshot::text AS configuration FROM analysis_runs WHERE id = ?",
        { rs, _ ->
            AccessRunContext(
                status = rs.getString("status"),
                configuration = objectMapper.readValue(rs.getString("configuration"), AnalysisConfigurationSnapshot::class.java),
            )
        },
        analysisRunId,
    ).firstOrNull()

    private fun cleanupObjectIfUnreferenced(analysisRunId: UUID, objectKey: String?) {
        if (objectKey == null) return
        val referenced = runCatching { repository.isObjectKeyReferenced(objectKey) }.getOrElse { exception ->
            logger.atWarn()
                .addKeyValue("analysisRunId", analysisRunId)
                .addKeyValue("errorType", exception.javaClass.simpleName)
                .log("Could not verify cited full-text object references; preserving stored content")
            return
        }
        if (referenced) return
        runCatching { objectStore.delete(objectKey) }
            .onFailure { exception ->
                logger.atWarn()
                    .addKeyValue("analysisRunId", analysisRunId)
                    .addKeyValue("errorType", exception.javaClass.simpleName)
                    .log("Failed to clean up an unreferenced cited full-text object")
            }
    }

    private data class AccessRunContext(
        val status: String,
        val configuration: AnalysisConfigurationSnapshot,
    )

    companion object {
        private const val MAX_LOCATIONS_TO_TRY = 5
        private val logger = LoggerFactory.getLogger(CitedPaperAccessService::class.java)
    }
}
