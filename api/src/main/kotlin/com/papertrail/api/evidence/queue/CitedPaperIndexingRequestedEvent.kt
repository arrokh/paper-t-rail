package com.papertrail.api.evidence.queue

import java.util.UUID

const val CITED_PAPER_INDEXING_REQUESTED = "CitedPaperIndexingRequested"
const val CITED_PAPER_INDEXING_HANDLER = "cited-paper-indexing-v1"

data class CitedPaperIndexingRequestedPayload(
    val documentId: UUID,
    val sourceContentSha256: String,
    val bibliographyEntryId: UUID,
)
