package com.papertrail.api.evidence.verification.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.document.repository.SourceDocumentRepository
import com.papertrail.api.evidence.verification.domain.AtomicClaimForJudgement
import com.papertrail.api.evidence.verification.domain.LayaEvidencePassageSpanPlanner
import com.papertrail.api.evidence.verification.domain.SemanticJudgementRequest
import com.papertrail.api.evidence.verification.provider.SystemOneProvider
import com.papertrail.api.evidence.verification.repository.ClaimReferenceVerificationRepository
import com.papertrail.api.evidence.verification.repository.EvidenceJudgementRepository
import com.papertrail.api.evidence.verification.repository.EvidencePassageSpanRepository
import com.papertrail.api.evidence.verification.repository.PendingJudgement
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.logging.logback.StructuredLogEncoder
import org.springframework.core.env.Environment
import org.springframework.core.env.StandardEnvironment
import java.util.UUID

class EvidenceVerificationLoggingTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `verification lifecycle emits ECS records with or without worker correlation`(workerContext: Boolean) {
        val runId = UUID.randomUUID()
        val referenceId = UUID.randomUUID()
        val verificationId = UUID.randomUUID()
        val claimId = UUID.randomUUID()
        val configuration = RunConfigurationFactory(
            providerCatalog = ProviderCatalog.safeDefaults(),
            parserId = "grobid",
            parserVersion = "v1",
            languageDetectorVersion = "v1",
            limits = ValidationLimitsSnapshot(52_428_800, 500, 5_000_000, 100_000, 100, 0.65),
        ).from(RunConfigurationRequest()).copy(systemOne = ProviderSelection("test", "v1"))
        val repository = Mockito.mock(EvidenceJudgementRepository::class.java)
        Mockito.`when`(repository.loadProcessingConfiguration(runId)).thenReturn(configuration)
        Mockito.`when`(repository.pendingRequests(runId, referenceId)).thenReturn(listOf(
            PendingJudgement(verificationId, SemanticJudgementRequest(
                AtomicClaimForJudgement(claimId, "private claim marker"), emptyList(),
            )),
        ))
        Mockito.`when`(repository.persistAndLoad(runId, referenceId, verificationId, "test", null, "v1", emptyList()))
            .thenReturn(emptyList())
        val provider = Mockito.mock(SystemOneProvider::class.java)
        Mockito.`when`(provider.providerId).thenReturn("test")
        Mockito.`when`(provider.version).thenReturn("v1")
        val service = EvidenceVerificationService(
            Mockito.mock(SourceDocumentRepository::class.java),
            Mockito.mock(ProviderCallGate::class.java),
            listOf(provider), repository,
            Mockito.mock(EvidencePassageSpanRepository::class.java),
            Mockito.mock(LayaEvidencePassageSpanPlanner::class.java),
            Mockito.mock(ClaimReferenceVerificationRepository::class.java),
        )
        val logger = LoggerFactory.getLogger(EvidenceVerificationService::class.java) as Logger
        val previousLevel = logger.level
        val previousContext = MDC.getCopyOfContextMap()
        val appender = object : ListAppender<ILoggingEvent>() {
            override fun append(event: ILoggingEvent) {
                event.prepareForDeferredProcessing()
                super.append(event)
            }
        }
        appender.context = logger.loggerContext
        appender.start()
        val encoderContext = LoggerContext().apply {
            putObject(Environment::class.java.name, StandardEnvironment())
        }
        val encoder = StructuredLogEncoder().apply {
            context = encoderContext
            setFormat("ecs")
            start()
        }
        logger.level = Level.INFO
        logger.addAppender(appender)
        try {
            MDC.clear()
            if (workerContext) {
                MDC.put("analysisRunId", runId.toString())
                MDC.put("eventId", "test-event")
                MDC.put("correlationId", runId.toString())
            }
            service.verifyReference(runId, referenceId)
            val encodedRecords = appender.list.map(encoder::encode)
            encodedRecords.forEach { encoded ->
                assertEquals(1, Regex("\"analysisRunId\"\\s*:").findAll(encoded.decodeToString()).count())
            }
            val records = encodedRecords.map { jacksonObjectMapper().readTree(it) }
            assertEquals(listOf("System One verification started", "System One judgements persisted"),
                records.map { it.path("message").asText() })
            records.forEach { record ->
                assertEquals(runId.toString(), record.path("analysisRunId").asText())
                assertEquals(verificationId.toString(), record.path("verificationId").asText())
                assertEquals(referenceId.toString(), record.path("bibliographyEntryId").asText())
                if (workerContext) assertEquals("test-event", record.path("eventId").asText())
                assertFalse(record.toString().contains("private claim marker"))
            }
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
            encoder.stop()
            encoderContext.stop()
            if (previousContext == null) MDC.clear() else MDC.setContextMap(previousContext)
        }
    }
}
