package com.papertrail.api.analysis.recovery.http

import com.papertrail.api.analysis.recovery.domain.RecoveryIdentifierAssistance
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "Current or newly recorded user-confirmed DOI assistance for one unresolved Bibliography Entry.")
data class RecoveryIdentifierAssistanceResponse(
    @field:Schema(description = "Predecessor Bibliography Entry identifier.", format = "uuid")
    val bibliographyEntryId: UUID,
    @field:Schema(description = "Run-scoped local Bibliography Entry key.")
    val localReferenceKey: String,
    @field:Schema(description = "Confirmed identifier type.", allowableValues = ["DOI"])
    val identifierType: String,
    @field:Schema(description = "Normalized DOI. This value is deliberately absent from recovery history.")
    val identifierValue: String,
    @field:Schema(description = "When this confirmation was recorded.")
    val recordedAt: Instant,
) {
    companion object {
        fun from(assistance: RecoveryIdentifierAssistance) = RecoveryIdentifierAssistanceResponse(
            bibliographyEntryId = assistance.bibliographyEntryId,
            localReferenceKey = assistance.localReferenceKey,
            identifierType = assistance.identifierType,
            identifierValue = assistance.identifierValue,
            recordedAt = assistance.recordedAt,
        )
    }
}
