package com.papertrail.api.queue

import java.time.Instant
import java.util.UUID

data class DocumentAnalysisRequestedPayload(
    val documentId: UUID,
    val sourceContentSha256: String,
)

data class PipelineEvent<T>(
    val eventId: UUID,
    val eventType: String,
    val schemaVersion: Int,
    val analysisRunId: UUID,
    val correlationId: UUID,
    val causationId: UUID?,
    val occurredAt: Instant,
    val attempt: Int,
    val payload: T,
)

const val DOCUMENT_ANALYSIS_REQUESTED = "DocumentAnalysisRequested"
const val DOCUMENT_ANALYSIS_HANDLER = "DocumentAnalysisRequestedHandler"
