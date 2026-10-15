package com.papertrail.api.scholarly.references.report

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "Safe, bounded wire-attempt history; internal owner tokens and raw provider payloads are excluded.")
data class ReferenceResolutionProviderAttemptReport(
    @field:Schema(description = "Provider called by the fixed chain.", allowableValues = ["crossref", "datacite"])
    val providerId: String,
    @field:Schema(description = "Global wire-attempt ordinal for this Bibliography Entry.", minimum = "1", maximum = "3")
    val attemptOrdinal: Int,
    @field:Schema(description = "Content-free persisted wire-attempt state.", allowableValues = ["RESERVED", "IN_FLIGHT", "COMPLETED", "UNKNOWN"])
    val state: String,
    @field:Schema(description = "Content-free provider result code; null while reserved or in flight.", allowableValues = ["FOUND", "NOT_FOUND", "UNUSABLE", "RETRYABLE_FAILURE", "PERMANENT_FAILURE", "UNKNOWN", "NOT_SENT"])
    val outcomeCode: String?,
    @field:Schema(description = "Time the durable wire slot was reserved.")
    val reservedAt: Instant,
    @field:Schema(description = "Time the request was durably authorized to start; null when it was not sent.")
    val startedAt: Instant?,
    @field:Schema(description = "Time a response or observed request failure was durably completed.")
    val completedAt: Instant?,
    @field:Schema(description = "Time an in-flight request became outcome-unknown after ownership expired.")
    val unknownAt: Instant?,
    @field:Schema(description = "HTTP response status when one was observed.", minimum = "100", maximum = "599")
    val httpStatus: Int?,
    @field:Schema(description = "Duration of the bounded HTTP request in milliseconds.", minimum = "0")
    val durationMillis: Long?,
    @field:Schema(description = "Decoded response byte count when known; no response content is included.", minimum = "0")
    val responseBytes: Long?,
    @field:Schema(description = "Database-clock retry not-before time. Null means no retry was scheduled.")
    val nextAttemptNotBefore: Instant?,
)
