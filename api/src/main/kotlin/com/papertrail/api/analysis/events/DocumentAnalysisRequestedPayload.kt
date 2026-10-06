package com.papertrail.api.analysis.events

import java.util.UUID

data class DocumentAnalysisRequestedPayload(
    val documentId: UUID,
    val sourceContentSha256: String,
)

const val DOCUMENT_ANALYSIS_REQUESTED = "DocumentAnalysisRequested"
const val DOCUMENT_ANALYSIS_HANDLER = "DocumentAnalysisRequestedHandler"
