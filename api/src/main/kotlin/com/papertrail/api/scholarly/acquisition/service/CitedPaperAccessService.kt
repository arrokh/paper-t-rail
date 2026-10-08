package com.papertrail.api.scholarly.acquisition.service

import com.papertrail.api.analysis.execution.service.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.domain.ExecutionSpanSpec
import com.papertrail.api.infrastructure.storage.SourceDocumentObjectStore
import com.papertrail.api.document.repository.SourceDocumentRepository
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.infrastructure.providers.ProviderCallRejectedException
import com.papertrail.api.scholarly.acquisition.client.OpenAccessProvider
import com.papertrail.api.scholarly.acquisition.client.OpenAccessProviderFactory
import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessCause
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessPolicy
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessReason
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessStatus
import com.papertrail.api.scholarly.acquisition.domain.LegalOpenAccessLocationPolicy
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessLocation
import com.papertrail.api.scholarly.acquisition.domain.UnsupportedCitedPaperFormatException
import com.papertrail.api.evidence.verification.repository.ClaimReferenceVerificationRepository
import com.papertrail.api.scholarly.acquisition.repository.CitedPaperAccessRepository
import com.papertrail.api.scholarly.acquisition.repository.ResolvedCitedReference
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.document.validation.DocumentLanguageDetector
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Service
class CitedPaperAccessService(
    private val sourceDocumentRepository: SourceDocumentRepository,
    private val transactionTemplate: TransactionTemplate,
    private val claimReferenceVerificationRepository: ClaimReferenceVerificationRepository,
    private val repository: CitedPaperAccessRepository,
    private val objectStore: SourceDocumentObjectStore,
    private val languageDetector: DocumentLanguageDetector,
    private val textExtractor: CitedPaperTextExtractor,
    private val providerFactories: List<OpenAccessProviderFactory>,
    private val executionService: AnalysisRunExecutionService? = null,
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
        sourceDocumentRepository.requireActiveSourceDocument(context.documentId)
        val discover = { provider.discover(metadata) }
        val discovery = if (executionService == null) {
            discover()
        } else {
            executionService.recordCurrentProviderCall(
                operationKey = "open-access-discovery",
                name = "Discover cited-paper access",
                providerId = providerSelection.provider,
                modelId = null,
                analysisRunId = analysisRunId,
                stageId = "access",
                operation = discover,
            )
        }
        val locations = discovery?.locations.orEmpty()
        val checkedLocations = locations.map { location -> location to locationPolicy.rejectionReasons(location) }
        val permittedLocations = checkedLocations.filter { (_, reasons) -> reasons.isEmpty() }.map { it.first }
        val rejectedLocationCauses = checkedLocations.flatMap { it.second }.distinct()
        val acquisition = acquireExtractableFullText(analysisRunId, context.documentId, providerSelection.provider, provider, permittedLocations)
        val acquired = acquisition.fullText
        val extractedText = acquisition.extractedText
        val languageDetection = extractedText?.let { text ->
            val detect = { languageDetector.detect(text) }
            executionService?.record(
                analysisRunId,
                ExecutionSpanSpec("access", "TRANSFORMATION", "Detect cited-paper language"),
                detect,
            ) ?: detect()
        }
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
        val accessCauses = when {
            acquired != null && supportedLanguage != "en" -> listOf(CitedPaperAccessCause.LANGUAGE_UNSUPPORTED)
            acquired != null -> emptyList()
            discovery == null || !metadataAvailable -> listOf(CitedPaperAccessCause.NO_ACCESSIBLE_METADATA)
            locations.isEmpty() -> listOf(CitedPaperAccessCause.NO_FULL_TEXT_LOCATION_RETURNED)
            permittedLocations.isEmpty() -> rejectedLocationCauses
            else -> (rejectedLocationCauses + acquisition.failureCauses).distinct()
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
                sourceDocumentRepository.lockActiveAnalysisRun(analysisRunId)
                val saved = repository.save(
                    analysisRunId = analysisRunId,
                    reference = reference,
                    discovery = discovery,
                    decision = decision,
                    accessReason = accessReason,
                    accessReasons = accessCauses,
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
            if (inserted) recordAccessCauses(analysisRunId, accessCauses)
        } catch (exception: Exception) {
            cleanupObjectIfUnreferenced(analysisRunId, objectKey)
            throw exception
        }
    }

    private fun acquireExtractableFullText(
        analysisRunId: UUID,
        documentId: UUID,
        providerId: String,
        provider: OpenAccessProvider,
        locations: List<OpenAccessLocation>,
    ): FullTextAcquisitionResult {
        val failureCauses = linkedSetOf<CitedPaperAccessCause>()
        for ((index, location) in locations.take(MAX_LOCATIONS_TO_TRY).withIndex()) {
            sourceDocumentRepository.requireActiveSourceDocument(documentId)
            val fetch = { provider.fetch(location) }
            val acquired = try {
                executionService?.recordCurrentProviderCall(
                    operationKey = "open-access-fulltext-fetch-$index",
                    name = "Fetch cited-paper full text",
                    providerId = providerId,
                    modelId = null,
                    analysisRunId = analysisRunId,
                    stageId = "access",
                    operation = fetch,
                ) ?: fetch()
            } catch (exception: ProviderCallRejectedException) {
                throw exception
            } catch (_: UnsupportedCitedPaperFormatException) {
                failureCauses += CitedPaperAccessCause.FULL_TEXT_FORMAT_UNSUPPORTED
                continue
            } catch (_: Exception) {
                failureCauses += CitedPaperAccessCause.FULL_TEXT_DOWNLOAD_FAILED
                continue
            }
            val text = try {
                val extract = { textExtractor.extract(acquired) }
                executionService?.record(
                    analysisRunId,
                    ExecutionSpanSpec("access", "TRANSFORMATION", "Extract cited-paper text"),
                    extract,
                ) ?: extract()
            } catch (_: UnsupportedCitedPaperFormatException) {
                failureCauses += CitedPaperAccessCause.FULL_TEXT_FORMAT_UNSUPPORTED
                continue
            } catch (_: Exception) {
                failureCauses += CitedPaperAccessCause.FULL_TEXT_PARSE_FAILED
                continue
            }
            return FullTextAcquisitionResult(acquired, text, emptyList())
        }
        return FullTextAcquisitionResult(null, null, failureCauses.toList())
    }

    private fun recordAccessCauses(analysisRunId: UUID, causes: List<CitedPaperAccessCause>) {
        causes.forEach { cause ->
            executionService?.record(
                analysisRunId,
                ExecutionSpanSpec(
                    stageId = "access",
                    kind = "TRANSFORMATION",
                    name = "Classify Cited Paper access cause",
                    attributes = mapOf("reasonCode" to cause.name),
                ),
            ) { Unit }
        }
    }

    private fun loadRun(analysisRunId: UUID): CitedPaperAccessRepository.AccessRunContext? = repository.loadRun(analysisRunId)

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

    private data class FullTextAcquisitionResult(
        val fullText: AcquiredFullText?,
        val extractedText: String?,
        val failureCauses: List<CitedPaperAccessCause>,
    )

    companion object {
        private const val MAX_LOCATIONS_TO_TRY = 5
        private val logger = LoggerFactory.getLogger(CitedPaperAccessService::class.java)
    }
}
