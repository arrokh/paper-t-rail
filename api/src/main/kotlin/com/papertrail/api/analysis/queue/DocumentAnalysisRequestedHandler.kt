package com.papertrail.api.analysis.queue

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.papertrail.api.analysis.service.AnalysisRunProcessingService
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class DocumentAnalysisRequestedHandler(
    private val objectMapper: ObjectMapper,
    private val analysisRunProcessingService: AnalysisRunProcessingService,
) {
    fun handle(serializedEvent: String): UUID {
        val event: PipelineEvent<DocumentAnalysisRequestedPayload> = objectMapper.readValue(serializedEvent)
        require(event.eventType == DOCUMENT_ANALYSIS_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        return analysisRunProcessingService.process(event)
    }

    fun markFailed(event: PipelineEvent<DocumentAnalysisRequestedPayload>, reason: String) {
        analysisRunProcessingService.markFailed(event, reason)
    }
}
