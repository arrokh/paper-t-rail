package com.papertrail.api.analysis.successor.service

import com.papertrail.api.analysis.configuration.AggregationPolicySnapshot
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ExternalProviderConsentSnapshot
import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.analysis.configuration.ReferenceResolutionSnapshot
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.analysis.recovery.domain.RecoveryAssetSelectionMethod
import com.papertrail.api.analysis.recovery.domain.RecoveryBatch
import com.papertrail.api.analysis.recovery.domain.RecoveryBatchStatus
import com.papertrail.api.analysis.recovery.domain.RecoveryIdentityOutcome
import com.papertrail.api.analysis.recovery.domain.RecoveryLanguageEligibility
import com.papertrail.api.analysis.recovery.domain.RecoveryRightsDeclaration
import com.papertrail.api.analysis.recovery.domain.RecoveryStagingException
import com.papertrail.api.analysis.recovery.domain.RecoveryUpload
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadAssetSelection
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadStatus
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationAttempt
import com.papertrail.api.analysis.recovery.domain.RecoveryIdentityConfirmation
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadIdentifierConfirmation
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadMetadataCandidateFingerprint
import com.papertrail.api.analysis.recovery.domain.RecoveryValidationStatus
import com.papertrail.api.analysis.recovery.repository.RecoveryBatchRepository
import com.papertrail.api.analysis.recovery.repository.RecoveryUploadIdentityDecisionRepository
import com.papertrail.api.analysis.recovery.service.RecoveryUploadValidationService
import com.papertrail.api.analysis.successor.domain.SuccessorWorkDisposition
import com.papertrail.api.analysis.successor.http.SuccessorRunPreviewRequest
import com.papertrail.api.analysis.successor.repository.SuccessorAccessProviderSnapshot
import com.papertrail.api.analysis.successor.repository.SuccessorEntrySnapshot
import com.papertrail.api.analysis.successor.repository.SuccessorPredecessorSnapshot
import com.papertrail.api.analysis.successor.repository.SuccessorReferenceKey
import com.papertrail.api.analysis.successor.repository.SuccessorRunPreviewRepository
import com.papertrail.api.citation.parsing.BibliographyNormalizationPolicySelection
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataCandidate
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataExtractionMethod
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataField
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

class SuccessorRunServiceTest {
    @Test
    fun `preview reuses unchanged stages retains other entries and fingerprints the current selected asset`() {
        val fixture = fixture()
        val service = fixture.service

        val first = service.preview(fixture.batch.id, fixture.request)
        val repeated = service.preview(fixture.batch.id, fixture.request)

        assertTrue(first.eligible)
        assertEquals(fixture.batch.revision, first.revision)
        assertEquals(listOf("ref2"), first.retainedReferenceKeys)
        assertEquals(SuccessorWorkDisposition.RETAIN, first.retainedDecision.disposition)
        assertEquals("ref1", first.selectedEntries.single().localReferenceKey)
        assertEquals(fixture.upload.actualSha256, first.selectedEntries.single().contentSha256)
        assertEquals(
            mapOf(
                "source-parse" to SuccessorWorkDisposition.REUSE,
                "claim-extraction" to SuccessorWorkDisposition.REUSE,
                "reference-resolution" to SuccessorWorkDisposition.REUSE,
                "selected-access" to SuccessorWorkDisposition.REPROCESS,
                "cited-paper-parse" to SuccessorWorkDisposition.REPROCESS,
                "evidence-retrieval" to SuccessorWorkDisposition.REPROCESS,
                "claim-verification" to SuccessorWorkDisposition.REPROCESS,
            ),
            first.selectedEntries.single().stages.associate { it.stage to it.disposition },
        )
        assertEquals(first.planFingerprint, repeated.planFingerprint)
        assertTrue(first.planFingerprint.matches(Regex("[0-9a-f]{64}")))
        assertEquals(listOf("cited-paper-parser", "evidence-embedding", "claim-verification"), first.expectedProviderWork.map { it.stage })
    }

    @Test
    fun `preview explains configuration invalidation instead of treating changed provider output as reusable`() {
        val fixture = fixture(
            successorConfiguration = configuration().copy(
                systemOne = ProviderSelection("external-reviewer", "v2", trustBoundary = "EXTERNAL", dataCategories = listOf("claim-text")),
                externalProviderConsents = emptyList(),
            ),
        )

        val plan = fixture.service.preview(fixture.batch.id, fixture.request)

        assertFalse(plan.eligible)
        assertTrue(plan.invalidationReasons.contains("VERIFICATION_CONFIGURATION_CHANGED"))
        assertTrue(plan.blockers.contains("CONFIGURATION_CHANGE_REQUIRES_UNSUPPORTED_GLOBAL_REPROCESSING"))
        assertEquals(
            SuccessorWorkDisposition.REPROCESS,
            plan.selectedEntries.single().stages.single { it.stage == "claim-verification" }.disposition,
        )
        assertTrue(plan.planFingerprint.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `preview blocks reuse of an external-provider result without its original matching consent`() {
        val externalConfiguration = configuration().copy(
            systemOne = ProviderSelection("external-reviewer", "v2", trustBoundary = "EXTERNAL", dataCategories = listOf("claim-text"), retentionDisclosure = "Deletes submitted data after 30 days."),
            externalProviderConsents = emptyList(),
        )
        val fixture = fixture(
            successorConfiguration = externalConfiguration,
            predecessorConfiguration = externalConfiguration,
        )

        val plan = fixture.service.preview(fixture.batch.id, fixture.request)

        assertFalse(plan.eligible)
        assertTrue(plan.blockers.contains("PREDECESSOR_EXTERNAL_PROVIDER_CONSENT_MISSING_OR_MISMATCHED"))
        assertTrue(plan.invalidationReasons.isEmpty())
    }

    @Test
    fun `preview permits historical external output only with exact provider categories and retention disclosure`() {
        val selection = ProviderSelection(
            provider = "external-reviewer",
            version = "v2",
            trustBoundary = "EXTERNAL",
            dataCategories = listOf("claim-text"),
            configurationFingerprint = "f".repeat(64),
            retentionDisclosure = "Deletes submitted data after 30 days.",
        )
        val embeddingSelection = selection.copy(dataCategories = listOf("embedding-input"))
        val exactConsent = ExternalProviderConsentSnapshot(
            providerId = selection.provider,
            dataCategories = listOf("claim-text", "embedding-input"),
            retentionDisclosure = selection.retentionDisclosure!!,
        )
        val externalConfiguration = configuration().copy(
            systemOne = selection,
            embedding = embeddingSelection,
            externalProviderConsents = listOf(exactConsent),
        )
        val fixture = fixture(
            successorConfiguration = externalConfiguration,
            predecessorConfiguration = externalConfiguration,
        )

        val plan = fixture.service.preview(fixture.batch.id, fixture.request)

        assertTrue(plan.eligible)
        assertEquals(listOf(exactConsent), plan.requiredConsents)
        val mismatchedConfiguration = externalConfiguration.copy(
            externalProviderConsents = listOf(exactConsent.copy(retentionDisclosure = "Keeps submitted data.")),
        )
        val mismatchFixture = fixture(
            successorConfiguration = mismatchedConfiguration,
            predecessorConfiguration = mismatchedConfiguration,
        )
        val mismatchedPlan = mismatchFixture.service.preview(mismatchFixture.batch.id, mismatchFixture.request)
        assertFalse(mismatchedPlan.eligible)
        assertTrue(mismatchedPlan.blockers.contains("PREDECESSOR_EXTERNAL_PROVIDER_CONSENT_MISSING_OR_MISMATCHED"))
    }

    @Test
    fun `preview blocks unmatched access provider provenance omitted from a legacy snapshot`() {
        val legacySnapshot = com.papertrail.api.utils.JsonUtil.parseTree(
            com.papertrail.api.utils.JsonUtil.toJson(configuration()),
        ) as com.fasterxml.jackson.databind.node.ObjectNode
        legacySnapshot.remove(listOf("openAccess", "openAccessProviderConfigurationFingerprint", "openAccessRetentionDisclosure"))
        val legacyConfiguration = com.papertrail.api.utils.JsonUtil.fromJson(
            legacySnapshot.toString(),
            AnalysisConfigurationSnapshot::class.java,
        )
        assertEquals("recorded-fixtures", legacyConfiguration.openAccess.provider)
        val fixture = fixture(predecessorConfiguration = legacyConfiguration)
        Mockito.`when`(fixture.previewRepository.accessProviders(fixture.batch.analysisRunId)).thenReturn(
            listOf(SuccessorAccessProviderSnapshot(UUID.randomUUID(), "unpaywall")),
        )

        val plan = fixture.service.preview(fixture.batch.id, fixture.request)

        assertFalse(plan.eligible)
        assertTrue(plan.blockers.contains("PREDECESSOR_ACCESS_PROVIDER_PROVENANCE_UNVERIFIABLE"))
    }

    @Test
    fun `preview retries unresolved identity only with a DOI from the human-confirmed exact PDF`() {
        val fixture = fixture()
        val uploadId = fixture.batch.selectedAssets.single().uploadId
        val candidate = ParsedBibliographicMetadataCandidate(
            field = ParsedBibliographicMetadataField.DOI,
            value = "doi: 10.5555/assisted.identity",
            pageNumber = 1,
            sourceLabel = "doi",
            extractionMethod = ParsedBibliographicMetadataExtractionMethod.EXPLICIT_DOI_PREFIX,
        )
        val attempt = requireNotNull(fixture.validationService.latest(fixture.batch.id, uploadId)).copy(
            identityOutcome = RecoveryIdentityOutcome.NEEDS_CONFIRMATION,
            metadataCandidates = listOf(candidate),
        )
        val selection = fixture.batch.selectedAssets.single().copy(
            selectionMethod = RecoveryAssetSelectionMethod.HUMAN_CONFIRMED,
        )
        val batch = fixture.batch.copy(selectedAssets = listOf(selection))
        Mockito.`when`(fixture.batchRepository.activeBatchById(anyUuid(), anyInstant())).thenReturn(batch)
        Mockito.`when`(fixture.validationService.latest(fixture.batch.id, uploadId)).thenReturn(attempt)
        val fingerprint = RecoveryUploadMetadataCandidateFingerprint.of(candidate)
        val identifierConfirmation = RecoveryUploadIdentifierConfirmation(
            id = UUID.randomUUID(),
            batchId = fixture.batch.id,
            uploadId = uploadId,
            analysisRunId = fixture.batch.analysisRunId,
            validationAttemptId = attempt.id,
            contentSha256 = fixture.upload.actualSha256!!,
            identifierType = "DOI",
            identifierValue = "10.5555/assisted.identity",
            candidateFingerprint = fingerprint,
            confirmedAt = Instant.parse("2026-10-13T00:00:00Z"),
        )
        Mockito.`when`(fixture.identityDecisionRepository.confirmation(fixture.batch.id, uploadId, attempt.id)).thenReturn(
            RecoveryIdentityConfirmation(
                id = UUID.randomUUID(),
                batchId = fixture.batch.id,
                uploadId = uploadId,
                validationAttemptId = attempt.id,
                contentSha256 = attempt.contentSha256,
                decision = "CONFIRM_EXACT_VERSION",
                confirmedAt = identifierConfirmation.confirmedAt,
                identifierConfirmation = identifierConfirmation,
            ),
        )
        Mockito.`when`(fixture.previewRepository.selectedEntry(fixture.batch.analysisRunId, selection.bibliographyEntryId)).thenReturn(
            SuccessorEntrySnapshot(
                bibliographyEntryId = selection.bibliographyEntryId,
                localReferenceKey = "ref1",
                entryOrder = 0,
                referenceType = "JOURNAL_ARTICLE",
                resolutionStatus = "UNRESOLVED",
                canonicalPaperId = null,
                claimCount = 1,
            ),
        )

        val plan = fixture.service.preview(fixture.batch.id, fixture.request)

        assertTrue(plan.eligible, plan.blockers.joinToString())
        assertEquals("10.5555/assisted.identity", plan.selectedEntries.single().assistedDoi)
        assertEquals(
            "HUMAN_CONFIRMED_IDENTIFIER_ASSISTANCE",
            plan.selectedEntries.single().stages.single { it.stage == "reference-resolution" }.reasonCode,
        )
        assertEquals(
            listOf("cited-paper-parser", "evidence-embedding", "claim-verification", "reference-resolution"),
            plan.expectedProviderWork.map { it.stage },
        )

        Mockito.`when`(fixture.identityDecisionRepository.confirmation(fixture.batch.id, uploadId, attempt.id)).thenReturn(null)
        val unconfirmedPlan = fixture.service.preview(fixture.batch.id, fixture.request)
        assertFalse(unconfirmedPlan.eligible)
        assertEquals(null, unconfirmedPlan.selectedEntries.single().assistedDoi)
        assertTrue(unconfirmedPlan.blockers.contains("SELECTED_ENTRY_NOT_RESOLVED"))

        Mockito.`when`(fixture.identityDecisionRepository.confirmation(fixture.batch.id, uploadId, attempt.id)).thenReturn(
            RecoveryIdentityConfirmation(
                id = UUID.randomUUID(),
                batchId = fixture.batch.id,
                uploadId = uploadId,
                validationAttemptId = attempt.id,
                contentSha256 = attempt.contentSha256,
                decision = "CONFIRM_EXACT_VERSION",
                confirmedAt = identifierConfirmation.confirmedAt,
                identifierConfirmation = identifierConfirmation,
            ),
        )
        Mockito.`when`(fixture.previewRepository.selectedEntry(fixture.batch.analysisRunId, selection.bibliographyEntryId)).thenReturn(
            SuccessorEntrySnapshot(
                bibliographyEntryId = selection.bibliographyEntryId,
                localReferenceKey = "ref1",
                entryOrder = 0,
                referenceType = "REPORT",
                resolutionStatus = "UNRESOLVED",
                canonicalPaperId = null,
                claimCount = 1,
            ),
        )

        val unsupportedTypePlan = fixture.service.preview(fixture.batch.id, fixture.request)

        assertFalse(unsupportedTypePlan.eligible)
        assertNull(unsupportedTypePlan.selectedEntries.single().assistedDoi)
        assertTrue(unsupportedTypePlan.blockers.contains("SELECTED_ENTRY_REFERENCE_TYPE_UNSUPPORTED"))
    }

    @Test
    fun `preview reports changed retrieval configuration as reprocess with a separate invalidation reason`() {
        val fixture = fixture(
            successorConfiguration = configuration().copy(
                embedding = ProviderSelection("different-embedding", "v2"),
            ),
        )

        val plan = fixture.service.preview(fixture.batch.id, fixture.request)
        val retrieval = plan.selectedEntries.single().stages.single { it.stage == "evidence-retrieval" }

        assertFalse(plan.eligible)
        assertTrue(plan.invalidationReasons.contains("EVIDENCE_RETRIEVAL_CONFIGURATION_CHANGED"))
        assertEquals(SuccessorWorkDisposition.REPROCESS, retrieval.disposition)
        assertEquals("SELECTED_ASSET_RETRIEVED_FOR_SUCCESSOR", retrieval.reasonCode)
    }

    @Test
    fun `stale batch revision is rejected before loading provider configuration or selection details`() {
        val fixture = fixture()
        val staleRequest = fixture.request.copy(expectedRevision = fixture.batch.revision - 1)

        val failure = assertThrows(RecoveryStagingException::class.java) {
            fixture.service.preview(fixture.batch.id, staleRequest)
        }

        assertEquals("RECOVERY_BATCH_REVISION_STALE", failure.code)
        Mockito.verifyNoInteractions(fixture.validationService, fixture.previewRepository, fixture.configurationFactory)
    }

    private fun fixture(
        successorConfiguration: AnalysisConfigurationSnapshot = configuration(),
        predecessorConfiguration: AnalysisConfigurationSnapshot = configuration().copy(
            referenceResolution = configuration().referenceResolution.copy(executionStatus = "COMPLETED"),
            aggregation = configuration().aggregation.copy(executionStatus = "COMPLETED"),
        ),
    ): Fixture {
        val batchId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val entryId = UUID.randomUUID()
        val uploadId = UUID.randomUUID()
        val attemptId = UUID.randomUUID()
        val now = Instant.parse("2026-10-13T00:00:00Z")
        val hash = "a".repeat(64)
        val selection = RecoveryUploadAssetSelection(
            id = UUID.randomUUID(),
            batchId = batchId,
            analysisRunId = runId,
            bibliographyEntryId = entryId,
            uploadId = uploadId,
            validationAttemptId = attemptId,
            contentSha256 = hash,
            selectionMethod = RecoveryAssetSelectionMethod.MACHINE_VALIDATED,
            selectedAt = now,
        )
        val upload = RecoveryUpload(
            id = uploadId,
            batchId = batchId,
            analysisRunId = runId,
            bibliographyEntryId = entryId,
            localReferenceKey = "ref1",
            idempotencyKey = UUID.randomUUID(),
            filename = "selected.pdf",
            expectedSize = 100,
            expectedSha256 = hash,
            stagingObjectKey = "staging/$uploadId",
            finalizedObjectKey = "finalized/$uploadId",
            status = RecoveryUploadStatus.STAGED,
            failureCode = null,
            presignedUrlExpiresAt = now.plusSeconds(300),
            actualSize = 100,
            actualSha256 = hash,
            createdAt = now,
            updatedAt = now,
            finalizedAt = now,
        )
        val batch = RecoveryBatch(
            id = batchId,
            analysisRunId = runId,
            status = RecoveryBatchStatus.OPEN,
            rightsDeclaration = RecoveryRightsDeclaration.CURRENT,
            rightsDeclaredAt = now,
            createdAt = now,
            lastActivityAt = now,
            expiresAt = now.plusSeconds(3_600),
            revision = 9,
            uploads = listOf(upload),
            selectedAssets = listOf(selection),
        )
        val attempt = RecoveryUploadValidationAttempt(
            id = attemptId,
            batchId = batchId,
            uploadId = uploadId,
            analysisRunId = runId,
            contentSha256 = hash,
            parserId = "docling",
            parserVersion = "1.30.0",
            metadataExtractionPolicyVersion = "metadata-v2",
            identityPolicyVersion = "identity-v2",
            parserOptions = emptyMap(),
            languageDetectorId = "optimaize",
            languageDetectorVersion = "0.6",
            minimumLanguageConfidence = 0.65,
            minimumExtractedCharacters = 100,
            languagePolicyFingerprint = "b".repeat(64),
            validationStatus = RecoveryValidationStatus.COMPLETED,
            identityOutcome = RecoveryIdentityOutcome.VALIDATED,
            identityReasonCode = "DOI_MATCH",
            metadataCandidates = emptyList<ParsedBibliographicMetadataCandidate>(),
            languageEligibility = RecoveryLanguageEligibility.ELIGIBLE,
            detectedLanguage = "en",
            languageConfidence = 0.99,
            languageReasonCode = "ENGLISH_DETECTED",
            failureCode = null,
            createdAt = now,
        )
        val batchRepository = Mockito.mock(RecoveryBatchRepository::class.java)
        Mockito.`when`(batchRepository.activeBatchById(anyUuid(), anyInstant())).thenReturn(batch)
        val validationService = Mockito.mock(RecoveryUploadValidationService::class.java)
        Mockito.`when`(validationService.latest(batchId, uploadId)).thenReturn(attempt)
        val previewRepository = Mockito.mock(SuccessorRunPreviewRepository::class.java)
        Mockito.`when`(previewRepository.predecessor(runId)).thenReturn(
            SuccessorPredecessorSnapshot(
                status = "COMPLETED",
                sourceContentSha256 = "c".repeat(64),
                configuration = predecessorConfiguration,
                hasSourceParse = true,
                bibliographyEntryCount = 2,
                claimCount = 1,
            ),
        )
        Mockito.`when`(previewRepository.selectedEntry(runId, entryId)).thenReturn(
            SuccessorEntrySnapshot(
                bibliographyEntryId = entryId,
                localReferenceKey = "ref1",
                entryOrder = 0,
                referenceType = "JOURNAL_ARTICLE",
                resolutionStatus = "RESOLVED",
                canonicalPaperId = UUID.randomUUID(),
                claimCount = 1,
            ),
        )
        val retainedEntryId = UUID.randomUUID()
        Mockito.`when`(previewRepository.referenceKeys(runId)).thenReturn(
            listOf(SuccessorReferenceKey(entryId, "ref1"), SuccessorReferenceKey(retainedEntryId, "ref2")),
        )
        val request = SuccessorRunPreviewRequest(
            expectedRevision = batch.revision,
            configuration = RunConfigurationRequest(),
        )
        val configurationFactory = Mockito.mock(RunConfigurationFactory::class.java)
        Mockito.`when`(configurationFactory.from(request.configuration)).thenReturn(successorConfiguration)
        val identityDecisionRepository = Mockito.mock(RecoveryUploadIdentityDecisionRepository::class.java)
        return Fixture(
            service = SuccessorRunService(batchRepository, validationService, previewRepository, configurationFactory, identityDecisionRepository),
            batchRepository = batchRepository,
            batch = batch,
            upload = upload,
            request = request,
            validationService = validationService,
            previewRepository = previewRepository,
            configurationFactory = configurationFactory,
            identityDecisionRepository = identityDecisionRepository,
        )
    }

    private fun configuration() = AnalysisConfigurationSnapshot(
        claimExtractor = ProviderSelection("heuristic", "v1"),
        embedding = ProviderSelection("feature-hash", "v1"),
        systemOne = ProviderSelection("mock", "v1"),
        sourceParser = ProviderSelection("grobid", "0.9.1-crf"),
        languageDetector = ProviderSelection("optimaize", "0.6"),
        validationLimits = ValidationLimitsSnapshot(1_000_000, 10, 100_000, 20_000, 100, 0.65),
        referenceResolution = ReferenceResolutionSnapshot(
            executionStatus = "PENDING",
            provider = ProviderSelection("recorded-fixtures", "v1"),
            scorePolicyVersion = "policy-v1",
            confidenceThreshold = 0.9,
            providerConfigurationFingerprint = "d".repeat(64),
        ),
        openAccess = ProviderSelection("recorded-fixtures", "v1"),
        aggregation = AggregationPolicySnapshot("PENDING", null, null, null),
        externalProviderConsents = emptyList(),
        citedPaperParser = ProviderSelection("docling", "1.30.0"),
        bibliographyNormalizationPolicy = BibliographyNormalizationPolicySelection.CURRENT,
    )

    private fun anyInstant(): Instant = Mockito.any(Instant::class.java) ?: Instant.EPOCH

    private fun anyUuid(): UUID = Mockito.any(UUID::class.java) ?: UUID(0L, 0L)

    private data class Fixture(
        val service: SuccessorRunService,
        val batchRepository: RecoveryBatchRepository,
        val batch: RecoveryBatch,
        val upload: RecoveryUpload,
        val request: SuccessorRunPreviewRequest,
        val validationService: RecoveryUploadValidationService,
        val previewRepository: SuccessorRunPreviewRepository,
        val configurationFactory: RunConfigurationFactory,
        val identityDecisionRepository: RecoveryUploadIdentityDecisionRepository,
    )
}
