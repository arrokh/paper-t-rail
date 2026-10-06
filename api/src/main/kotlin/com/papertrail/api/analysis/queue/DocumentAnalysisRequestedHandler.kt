package com.papertrail.api.analysis.queue

import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.analysis.events.DOCUMENT_ANALYSIS_REQUESTED
import com.papertrail.api.analysis.events.DocumentAnalysisRequestedPayload
import com.papertrail.api.analysis.service.AnalysisRunProcessingService
import com.papertrail.api.infrastructure.messaging.events.PipelineEvent
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class DocumentAnalysisRequestedHandler(
    private val analysisRunProcessingService: AnalysisRunProcessingService,
) {
    fun handle(serializedEvent: String): UUID {
        val event: PipelineEvent<DocumentAnalysisRequestedPayload> = JsonUtil.fromJson(serializedEvent)
        require(event.eventType == DOCUMENT_ANALYSIS_REQUESTED) { "Unsupported event type '${event.eventType}'." }
        return analysisRunProcessingService.process(event)
    }

    fun markFailed(event: PipelineEvent<DocumentAnalysisRequestedPayload>, reason: String) {
        analysisRunProcessingService.markFailed(event, reason)
    }
}
