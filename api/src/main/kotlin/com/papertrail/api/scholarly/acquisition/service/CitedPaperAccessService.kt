package com.papertrail.api.scholarly.acquisition.service

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
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
import com.papertrail.api.scholarly.acquisition.domain.UnsafeOpenAccessLocationException
import com.papertrail.api.scholarly.acquisition.report.CitedPaperAcquisitionCandidateOutcome
import com.papertrail.api.evidence.verification.repository.ClaimReferenceVerificationRepository
import com.papertrail.api.scholarly.acquisition.repository.CitedPaperAccessRepository
import com.papertrail.api.scholarly.acquisition.repository.ResolvedCitedReference
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.document.validation.DocumentLanguageDetector
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.net.URI
import java.net.UnknownHostException
import java.time.Instant
import java.util.Locale
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
    private val identityValidator: CitedPaperIdentityValidator,
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
        val checkedLocations = locations.map { location ->
            CheckedOpenAccessLocation(location, locationPolicy.rejectionReasons(location))
        }
        val permittedLocations = checkedLocations.filter { it.rejectionReasons.isEmpty() }.map { it.location }
        val rejectedLocationCauses = checkedLocations.flatMap { it.rejectionReasons }.distinct()
        val acquisition = acquireExtractableFullText(
            analysisRunId = analysisRunId,
            documentId = context.documentId,
            providerId = providerSelection.provider,
            provider = provider,
            checkedLocations = checkedLocations,
            reference = reference,
            configuration = context.configuration,
        )
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
        val accessDecision = accessPolicy.decide(
            metadataAvailable = metadataAvailable,
            abstractAvailable = discovery?.abstractAvailable == true,
            fullTextAvailable = acquired != null,
            language = supportedLanguage,
        )
        val decision = if (accessDecision.accessStatus == CitedPaperAccessStatus.METADATA_ONLY) {
            acquisition.identityFailureReason?.let { accessDecision.copy(terminalReason = it) } ?: accessDecision
        } else {
            accessDecision
        }
        val accessReason = when {
            permittedLocations.isNotEmpty() && acquired == null -> CitedPaperAccessReason.FULL_TEXT_ACQUISITION_FAILED
            decision.accessStatus == CitedPaperAccessStatus.ABSTRACT_ONLY -> CitedPaperAccessReason.ABSTRACT_ONLY
            decision.accessStatus == CitedPaperAccessStatus.METADATA_ONLY -> CitedPaperAccessReason.NO_LEGAL_FULL_TEXT_LOCATION
            decision.accessStatus == CitedPaperAccessStatus.UNAVAILABLE -> CitedPaperAccessReason.NO_ACCESSIBLE_METADATA
            else -> null
        }
        val accessCauses = when {
            acquired != null -> (rejectedLocationCauses + acquisition.failureCauses +
                listOfNotNull(CitedPaperAccessCause.LANGUAGE_UNSUPPORTED.takeIf { supportedLanguage != "en" })).distinct()
            discovery == null || !metadataAvailable -> listOf(CitedPaperAccessCause.NO_ACCESSIBLE_METADATA)
            locations.isEmpty() -> listOf(CitedPaperAccessCause.NO_FULL_TEXT_LOCATION_RETURNED)
            permittedLocations.isEmpty() -> rejectedLocationCauses
            else -> (rejectedLocationCauses + acquisition.failureCauses).distinct()
        }
        val provenanceLocation = acquired?.location ?: acquisition.identityFailureLocation ?: permittedLocations.firstOrNull()
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
                    candidateOutcomes = acquisition.candidateOutcomes,
                    candidateOutcomesOmittedCount = acquisition.candidateOutcomesOmittedCount,
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
        checkedLocations: List<CheckedOpenAccessLocation>,
        reference: ResolvedCitedReference,
        configuration: AnalysisConfigurationSnapshot,
    ): FullTextAcquisitionResult {
        val failureCauses = linkedSetOf<CitedPaperAccessCause>()
        val candidateOutcomes = CandidateOutcomeCollector(MAX_CANDIDATE_OUTCOMES)
        var identityFailureReason: String? = null
        var identityFailureLocation: OpenAccessLocation? = null
        var fetchAttempts = 0
        for ((index, checkedLocation) in checkedLocations.withIndex()) {
            val location = checkedLocation.location
            val rejectionReasons = checkedLocation.rejectionReasons
            val ordinal = index + 1
            if (rejectionReasons.isNotEmpty()) {
                failureCauses += rejectionReasons
                candidateOutcomes.add(candidateOutcome(location, ordinal, "REJECTED", rejectionReasons.map { it.name }))
                continue
            }
            if (fetchAttempts >= MAX_LOCATIONS_TO_TRY) {
                candidateOutcomes.add(candidateOutcome(location, ordinal, "NOT_ATTEMPTED", listOf(MAX_FETCH_ATTEMPTS_REACHED)))
                continue
            }
            fetchAttempts++
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
            } catch (_: UnsafeOpenAccessLocationException) {
                val cause = CitedPaperAccessCause.FULL_TEXT_LOCATION_URL_REJECTED
                failureCauses += cause
                candidateOutcomes.add(candidateOutcome(location, ordinal, "REJECTED", listOf(cause.name)))
                continue
            } catch (_: UnknownHostException) {
                val cause = CitedPaperAccessCause.FULL_TEXT_LOCATION_URL_REJECTED
                failureCauses += cause
                candidateOutcomes.add(candidateOutcome(location, ordinal, "REJECTED", listOf(cause.name)))
                continue
            } catch (_: UnsupportedCitedPaperFormatException) {
                val cause = CitedPaperAccessCause.FULL_TEXT_FORMAT_UNSUPPORTED
                failureCauses += cause
                candidateOutcomes.add(candidateOutcome(location, ordinal, "FORMAT_UNSUPPORTED", listOf(cause.name)))
                continue
            } catch (_: Exception) {
                val cause = CitedPaperAccessCause.FULL_TEXT_DOWNLOAD_FAILED
                failureCauses += cause
                candidateOutcomes.add(candidateOutcome(location, ordinal, "DOWNLOAD_FAILED", listOf(cause.name)))
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
                val cause = CitedPaperAccessCause.FULL_TEXT_FORMAT_UNSUPPORTED
                failureCauses += cause
                candidateOutcomes.add(candidateOutcome(location, ordinal, "FORMAT_UNSUPPORTED", listOf(cause.name)))
                continue
            } catch (_: Exception) {
                val cause = CitedPaperAccessCause.FULL_TEXT_PARSE_FAILED
                failureCauses += cause
                candidateOutcomes.add(candidateOutcome(location, ordinal, "PARSE_FAILED", listOf(cause.name)))
                continue
            }
            val identity = try {
                identityValidator.validate(acquired, reference, configuration)
            } catch (exception: ProviderCallRejectedException) {
                throw exception
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw exception
            } catch (_: Exception) {
                CitedPaperIdentityValidationResult(
                    status = CitedPaperIdentityValidationStatus.FAILED,
                    reasonCode = "IDENTITY_VALIDATION_FAILED",
                    failureCause = CitedPaperAccessCause.FULL_TEXT_IDENTITY_VALIDATION_FAILED,
                )
            }
            val identityCause = identity.accessCause()
            if (identityCause != null) {
                failureCauses += identityCause
                identityFailureReason = identity.reasonCode
                identityFailureLocation = location
                candidateOutcomes.add(candidateOutcome(location, ordinal, identityOutcome(identityCause), listOf(identityCause.name)))
                continue
            }
            candidateOutcomes.add(candidateOutcome(location, ordinal, "ACQUIRED", emptyList()))
            checkedLocations.drop(index + 1).forEachIndexed { nextIndex, nextLocation ->
                val reasonCodes = nextLocation.rejectionReasons.map { it.name }.ifEmpty { listOf(EARLIER_CANDIDATE_ACQUIRED) }
                val outcome = if (nextLocation.rejectionReasons.isNotEmpty()) "REJECTED" else "NOT_ATTEMPTED"
                candidateOutcomes.add(candidateOutcome(nextLocation.location, ordinal + nextIndex + 1, outcome, reasonCodes))
            }
            return FullTextAcquisitionResult(
                fullText = acquired,
                extractedText = text,
                failureCauses = failureCauses.toList(),
                identityFailureReason = null,
                identityFailureLocation = null,
                candidateOutcomes = candidateOutcomes.outcomes,
                candidateOutcomesOmittedCount = candidateOutcomes.omittedCount,
            )
        }
        return FullTextAcquisitionResult(
            fullText = null,
            extractedText = null,
            failureCauses = failureCauses.toList(),
            identityFailureReason = identityFailureReason,
            identityFailureLocation = identityFailureLocation,
            candidateOutcomes = candidateOutcomes.outcomes,
            candidateOutcomesOmittedCount = candidateOutcomes.omittedCount,
        )
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

    private fun candidateOutcome(
        location: OpenAccessLocation,
        ordinal: Int,
        outcome: String,
        reasonCodes: List<String>,
    ): CitedPaperAcquisitionCandidateOutcome = CitedPaperAcquisitionCandidateOutcome(
        ordinal = ordinal,
        host = safeCandidateHost(location.url),
        license = safeCandidateLabel(location.license),
        version = safeCandidateLabel(location.version),
        hostType = safeCandidateLabel(location.hostType),
        outcome = outcome,
        reasonCodes = reasonCodes,
    )

    private fun safeCandidateHost(url: String): String? {
        val host = runCatching { URI(url).host }.getOrNull()?.removeSurrounding("[", "]") ?: return null
        return host.takeIf { it.length <= MAX_CANDIDATE_HOST_LENGTH && SAFE_HOST.matches(it) }
            ?.lowercase(Locale.ROOT)
    }

    private fun safeCandidateLabel(value: String?): String? = value
        ?.trim()
        ?.takeIf { it.length <= MAX_CANDIDATE_LABEL_LENGTH && SAFE_CANDIDATE_LABEL.matches(it) }

    private fun identityOutcome(cause: CitedPaperAccessCause): String = when (cause) {
        CitedPaperAccessCause.FULL_TEXT_IDENTITY_UNVERIFIED -> "IDENTITY_UNVERIFIED"
        CitedPaperAccessCause.FULL_TEXT_IDENTITY_MISMATCH -> "IDENTITY_MISMATCH"
        else -> "IDENTITY_VALIDATION_FAILED"
    }

    private data class CheckedOpenAccessLocation(
        val location: OpenAccessLocation,
        val rejectionReasons: Set<CitedPaperAccessCause>,
    )

    private data class FullTextAcquisitionResult(
        val fullText: AcquiredFullText?,
        val extractedText: String?,
        val failureCauses: List<CitedPaperAccessCause>,
        val identityFailureReason: String?,
        val identityFailureLocation: OpenAccessLocation?,
        val candidateOutcomes: List<CitedPaperAcquisitionCandidateOutcome>,
        val candidateOutcomesOmittedCount: Int,
    )

    private class CandidateOutcomeCollector(private val maximumRecords: Int) {
        private val storedOutcomes = mutableListOf<CitedPaperAcquisitionCandidateOutcome>()
        var omittedCount: Int = 0
            private set

        val outcomes: List<CitedPaperAcquisitionCandidateOutcome>
            get() = storedOutcomes.toList()

        fun add(outcome: CitedPaperAcquisitionCandidateOutcome) {
            if (storedOutcomes.size < maximumRecords) {
                storedOutcomes += outcome
                return
            }
            if (outcome.outcome == "ACQUIRED") {
                storedOutcomes.removeAt(storedOutcomes.lastIndex)
                omittedCount++
                storedOutcomes += outcome
                return
            }
            omittedCount++
        }
    }

    companion object {
        private const val MAX_LOCATIONS_TO_TRY = 5
        private const val MAX_CANDIDATE_OUTCOMES = 5
        private const val MAX_CANDIDATE_HOST_LENGTH = 253
        private const val MAX_CANDIDATE_LABEL_LENGTH = 80
        private const val MAX_FETCH_ATTEMPTS_REACHED = "MAX_FETCH_ATTEMPTS_REACHED"
        private const val EARLIER_CANDIDATE_ACQUIRED = "EARLIER_CANDIDATE_ACQUIRED"
        private val SAFE_HOST = Regex("^[A-Za-z0-9.:_-]{1,253}$")
        private val SAFE_CANDIDATE_LABEL = Regex("^[A-Za-z0-9._-]{1,80}$")
        private val logger = LoggerFactory.getLogger(CitedPaperAccessService::class.java)
    }
}
