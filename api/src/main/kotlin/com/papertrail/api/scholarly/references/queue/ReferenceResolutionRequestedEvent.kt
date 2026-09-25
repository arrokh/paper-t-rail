package com.papertrail.api.scholarly.references.queue

import java.util.UUID

data class ReferenceResolutionRequestedPayload(
    val documentId: UUID,
    val sourceContentSha256: String,
    val bibliographyEntryId: UUID,
)

const val REFERENCE_RESOLUTION_REQUESTED = "ReferenceResolutionRequested"
const val REFERENCE_RESOLUTION_HANDLER = "ReferenceResolutionRequestedHandler"
