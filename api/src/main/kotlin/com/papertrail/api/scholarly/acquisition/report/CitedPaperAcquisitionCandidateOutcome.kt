package com.papertrail.api.scholarly.acquisition.report

import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Content-minimized outcome for one discovered full-text location. URL paths, query strings, credentials and content are never included.")
data class CitedPaperAcquisitionCandidateOutcome(
    @field:Schema(description = "One-based ordinal in the provider's original location order.", minimum = "1")
    val ordinal: Int,
    @field:Schema(description = "Sanitized hostname only; never a full URL or network address decision transcript.", nullable = true)
    val host: String?,
    @field:Schema(description = "Sanitized license identifier.", nullable = true)
    val license: String?,
    @field:Schema(description = "Sanitized version label.", nullable = true)
    val version: String?,
    @field:Schema(description = "Sanitized repository host-type label.", nullable = true)
    val hostType: String?,
    @field:Schema(
        description = "Outcome for this location.",
        allowableValues = [
            "REJECTED", "DOWNLOAD_FAILED", "FORMAT_UNSUPPORTED", "PARSE_FAILED", "IDENTITY_UNVERIFIED",
            "IDENTITY_MISMATCH", "IDENTITY_VALIDATION_FAILED", "ACQUIRED", "NOT_ATTEMPTED",
        ],
    )
    val outcome: String,
    @field:ArraySchema(
        arraySchema = Schema(description = "Safe reason codes only; no provider error message, URL, response body or document text."),
        schema = Schema(
            allowableValues = [
                "FULL_TEXT_LOCATION_LICENSE_MISSING", "FULL_TEXT_LOCATION_LICENSE_REJECTED", "FULL_TEXT_LOCATION_URL_REJECTED",
                "FULL_TEXT_DOWNLOAD_FAILED", "FULL_TEXT_FORMAT_UNSUPPORTED", "FULL_TEXT_PARSE_FAILED",
                "FULL_TEXT_IDENTITY_UNVERIFIED", "FULL_TEXT_IDENTITY_MISMATCH", "FULL_TEXT_IDENTITY_VALIDATION_FAILED",
                "MAX_FETCH_ATTEMPTS_REACHED", "EARLIER_CANDIDATE_ACQUIRED",
            ],
        ),
    )
    val reasonCodes: List<String> = emptyList(),
)
