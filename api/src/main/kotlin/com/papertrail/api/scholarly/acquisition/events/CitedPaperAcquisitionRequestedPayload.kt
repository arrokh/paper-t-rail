package com.papertrail.api.scholarly.acquisition.events

import java.util.UUID

const val CITED_PAPER_ACQUISITION_REQUESTED = "CitedPaperAcquisitionRequested"
const val CITED_PAPER_ACQUISITION_HANDLER = "cited-paper-acquisition-v1"

data class CitedPaperAcquisitionRequestedPayload(
    val documentId: UUID,
    val sourceContentSha256: String,
    val bibliographyEntryId: UUID,
)
