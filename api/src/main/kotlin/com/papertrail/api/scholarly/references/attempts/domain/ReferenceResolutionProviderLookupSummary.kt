package com.papertrail.api.scholarly.references.attempts.domain

import java.time.Instant
import java.util.UUID

data class ReferenceResolutionProviderLookupSummary(
    val analysisRunId: UUID,
    val bibliographyEntryId: UUID,
    val lookupOrdinal: Int,
    val providerId: String,
    val source: ReferenceResolutionProviderLookupSource,
    val outcomeCode: ReferenceResolutionProviderLookupOutcomeCode,
    val recordedAt: Instant,
)
