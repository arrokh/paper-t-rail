package com.papertrail.api.analysis.recovery.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.analysis.configuration.ExternalProviderConsentSnapshot
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.analysis.recovery.domain.RecoveryHistoryEvent
import com.papertrail.api.analysis.recovery.domain.RecoveryRightsDeclaration
import com.papertrail.api.analysis.recovery.domain.RecoveryUpload
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadStatus
import com.papertrail.api.analysis.recovery.http.AcceptRecoveryRightsDeclarationRequest
import com.papertrail.api.analysis.recovery.http.ConfirmRecoveryIdentityRequest
import com.papertrail.api.analysis.recovery.http.CreateRecoveryBatchRequest
import com.papertrail.api.analysis.recovery.http.CreateRecoveryUploadRequest
import com.papertrail.api.analysis.recovery.http.RecoveryIdentifierAssistanceRequest
import com.papertrail.api.analysis.recovery.repository.RecoveryHistoryPage
import com.papertrail.api.analysis.recovery.service.RecoveryHistoryService
import com.papertrail.api.analysis.recovery.service.RecoveryStagingService
import com.papertrail.api.analysis.recovery.service.RecoveryUploadIdentityService
import com.papertrail.api.analysis.recovery.service.RecoveryUploadLimits
import com.papertrail.api.analysis.recovery.service.RecoveryUploadValidationService
import com.papertrail.api.analysis.successor.domain.SuccessorRunPlan
import com.papertrail.api.analysis.successor.domain.SuccessorStageDecision
import com.papertrail.api.analysis.successor.domain.SuccessorWorkDisposition
import com.papertrail.api.analysis.successor.http.SuccessorRunPreviewRequest
import com.papertrail.api.analysis.successor.http.SuccessorRunSubmissionRequest
import com.papertrail.api.analysis.successor.service.SuccessorRunService
import com.papertrail.api.analysis.successor.service.SuccessorRunSubmissionService
import com.papertrail.api.infrastructure.messaging.outbox.OutboxPublisher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

@SpringBootTest(properties = ["paper-trail.role=api", "paper-trail.recovery.submissions-enabled=false"])
@AutoConfigureMockMvc
class RecoverySubmissionGateIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockitoBean
    private lateinit var recoveryStagingService: RecoveryStagingService

    @MockitoBean
    private lateinit var recoveryUploadValidationService: RecoveryUploadValidationService

    @MockitoBean
    private lateinit var recoveryUploadIdentityService: RecoveryUploadIdentityService

    @MockitoBean
    private lateinit var recoveryHistoryService: RecoveryHistoryService

    @MockitoBean
    private lateinit var successorRunService: SuccessorRunService

    @MockitoBean
    private lateinit var successorRunSubmissionService: SuccessorRunSubmissionService

    @MockitoBean
    private lateinit var outboxPublisher: OutboxPublisher

    @BeforeEach
    fun resetMocks() {
        Mockito.reset(
            recoveryStagingService,
            recoveryUploadValidationService,
            recoveryUploadIdentityService,
            recoveryHistoryService,
            successorRunService,
            successorRunSubmissionService,
        )
    }

    @Test
    fun `disabled recovery writes return stable 503 without invoking feature services`() {
        val runId = UUID.randomUUID()
        val batchId = UUID.randomUUID()
        val uploadId = UUID.randomUUID()
        val attemptId = UUID.randomUUID()
        val sha256 = "a".repeat(64)
        val requests = listOf(
            post("/api/v1/analysis-runs/$runId/recovery-batches")
                .json(CreateRecoveryBatchRequest(UUID.randomUUID())),
            post("/api/v1/recovery-batches/$batchId/rights-declaration")
                .json(AcceptRecoveryRightsDeclarationRequest("recovery-rights-v1", true)),
            put("/api/v1/recovery-batches/$batchId/entries/ref-1/identifier-assistance")
                .json(RecoveryIdentifierAssistanceRequest("DOI", "10.1234/example", true)),
            post("/api/v1/recovery-batches/$batchId/entries/ref-1/uploads")
                .json(CreateRecoveryUploadRequest(UUID.randomUUID(), "candidate.pdf", 12, sha256)),
            post("/api/v1/recovery-batches/$batchId/uploads/$uploadId/finalize"),
            post("/api/v1/recovery-batches/$batchId/uploads/$uploadId/validate"),
            post("/api/v1/recovery-batches/$batchId/uploads/$uploadId/validation/$attemptId/confirm-identity")
                .json(ConfirmRecoveryIdentityRequest(confirmExactVersion = true)),
            post("/api/v1/recovery-batches/$batchId/uploads/$uploadId/select"),
            post("/api/v1/recovery-batches/$batchId/successor")
                .json(SuccessorRunSubmissionRequest(UUID.randomUUID(), 0, RunConfigurationRequest())),
        )

        requests.forEach { request ->
            val response = mockMvc.perform(request).andReturn().response
            assertEquals(503, response.status)
            val error = objectMapper.readTree(response.contentAsString)
            assertEquals("RECOVERY_SUBMISSIONS_DISABLED", error.path("code").asText())
            assertEquals("Recovery submissions are disabled.", error.path("message").asText())
        }

        Mockito.verifyNoInteractions(
            recoveryStagingService,
            recoveryUploadValidationService,
            recoveryUploadIdentityService,
            recoveryHistoryService,
            successorRunService,
            successorRunSubmissionService,
        )
    }

    @Test
    fun `disabled submissions keep capability reads previews and explicit removals available`() {
        val runId = UUID.randomUUID()
        val batchId = UUID.randomUUID()
        val uploadId = UUID.randomUUID()
        val previewRequest = SuccessorRunPreviewRequest(0)
        val now = Instant.parse("2026-10-09T00:00:00Z")
        val removedUpload = RecoveryUpload(
            id = uploadId,
            batchId = batchId,
            analysisRunId = runId,
            bibliographyEntryId = UUID.randomUUID(),
            localReferenceKey = "ref-1",
            idempotencyKey = UUID.randomUUID(),
            filename = "candidate.pdf",
            expectedSize = 12,
            expectedSha256 = "a".repeat(64),
            stagingObjectKey = "staging/object",
            finalizedObjectKey = "finalized/object",
            status = RecoveryUploadStatus.REMOVED,
            failureCode = null,
            presignedUrlExpiresAt = now.plusSeconds(300),
            actualSize = null,
            actualSha256 = null,
            createdAt = now,
            updatedAt = now,
            finalizedAt = null,
        )
        val previewPlan = SuccessorRunPlan(
            batchId = batchId,
            predecessorAnalysisRunId = runId,
            revision = 0,
            planFingerprint = "b".repeat(64),
            eligible = true,
            selectedEntries = emptyList(),
            identifierAssistanceEntries = emptyList(),
            retainedReferenceKeys = emptyList(),
            retainedDecision = SuccessorStageDecision("RESOLUTION", SuccessorWorkDisposition.RETAIN, "UNCHANGED"),
            retainedStageDecisions = emptyList(),
            invalidationReasons = emptyList(),
            blockers = emptyList(),
            expectedProviderWork = emptyList(),
            requiredConsents = emptyList<ExternalProviderConsentSnapshot>(),
        )
        Mockito.`when`(recoveryStagingService.currentRightsDeclaration()).thenReturn(RecoveryRightsDeclaration.CURRENT)
        Mockito.`when`(recoveryStagingService.currentLimits()).thenReturn(RecoveryUploadLimits(50_000_000, 10, 250_000_000, 300, 604_800))
        Mockito.`when`(recoveryStagingService.activeBatch(runId)).thenReturn(null)
        Mockito.`when`(recoveryHistoryService.page(runId, null, 50)).thenReturn(RecoveryHistoryPage(runId, emptyList<RecoveryHistoryEvent>(), null))
        Mockito.`when`(recoveryUploadValidationService.latest(batchId, uploadId)).thenReturn(null)
        Mockito.`when`(successorRunService.preview(batchId, previewRequest)).thenReturn(previewPlan)
        Mockito.`when`(recoveryStagingService.removeUpload(batchId, uploadId)).thenReturn(removedUpload)

        val rightsResponse = mockMvc.perform(get("/api/v1/recovery-rights-declaration"))
            .andReturn().response
        assertEquals(200, rightsResponse.status)
        assertEquals("no-store", rightsResponse.getHeader("Cache-Control"))
        assertFalse(objectMapper.readTree(rightsResponse.contentAsString).path("submissionsEnabled").asBoolean())

        assertEquals(200, mockMvc.perform(get("/api/v1/analysis-runs/$runId/recovery-batches")).andReturn().response.status)
        assertEquals(200, mockMvc.perform(get("/api/v1/analysis-runs/$runId/recovery-history")).andReturn().response.status)
        assertEquals(204, mockMvc.perform(get("/api/v1/recovery-batches/$batchId/uploads/$uploadId/validation")).andReturn().response.status)
        assertEquals(
            200,
            mockMvc.perform(
                post("/api/v1/recovery-batches/$batchId/successor/preview")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(previewRequest)),
            ).andReturn().response.status,
        )
        assertEquals(204, mockMvc.perform(delete("/api/v1/recovery-batches/$batchId/entries/ref-1/identifier-assistance")).andReturn().response.status)
        assertEquals(200, mockMvc.perform(delete("/api/v1/recovery-batches/$batchId/uploads/$uploadId")).andReturn().response.status)

        Mockito.verify(recoveryStagingService).currentRightsDeclaration()
        Mockito.verify(recoveryStagingService).currentLimits()
        Mockito.verify(recoveryStagingService).activeBatch(runId)
        Mockito.verify(recoveryHistoryService).page(runId, null, 50)
        Mockito.verify(recoveryUploadValidationService).latest(batchId, uploadId)
        Mockito.verify(successorRunService).preview(batchId, previewRequest)
        Mockito.verify(recoveryStagingService).removeIdentifierAssistance(batchId, "ref-1")
        Mockito.verify(recoveryStagingService).removeUpload(batchId, uploadId)
        Mockito.verifyNoInteractions(recoveryUploadIdentityService, successorRunSubmissionService)
    }

    private fun MockHttpServletRequestBuilder.json(body: Any): MockHttpServletRequestBuilder =
        contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsBytes(body))
}
