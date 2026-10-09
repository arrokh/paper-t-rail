package com.papertrail.api.scholarly.acquisition.report

import com.papertrail.api.evidence.report.CitedReferenceVerificationOutcome
import com.papertrail.api.evidence.report.EvidenceIndexingReport
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

data class CitedPaperAccessReport(
    val accessStatus: String,
    val accessReason: String?,
    @field:ArraySchema(
        arraySchema = Schema(description = "Detailed persisted reasons for access or assessment ineligibility. Empty when no cause applies or for legacy rows where detailed attribution was not stored."),
        schema = Schema(
            allowableValues = [
                "NO_ACCESSIBLE_METADATA", "NO_FULL_TEXT_LOCATION_RETURNED", "FULL_TEXT_LOCATION_LICENSE_MISSING",
                "FULL_TEXT_LOCATION_LICENSE_REJECTED", "FULL_TEXT_LOCATION_URL_REJECTED", "FULL_TEXT_DOWNLOAD_FAILED",
                "FULL_TEXT_FORMAT_UNSUPPORTED", "FULL_TEXT_PARSE_FAILED", "FULL_TEXT_IDENTITY_UNVERIFIED",
                "FULL_TEXT_IDENTITY_MISMATCH", "FULL_TEXT_IDENTITY_VALIDATION_FAILED", "LANGUAGE_UNSUPPORTED",
            ],
        ),
    )
    val accessReasons: List<String> = emptyList(),
    val providerId: String,
    val sourceUrl: String?,
    val license: String?,
    val version: String?,
    val hostType: String?,
    val discoveredAt: Instant,
    val contentSha256: String?,
    val language: String?,
    val languageDetectorVersion: String?,
    val verificationOutcomes: List<CitedReferenceVerificationOutcome> = emptyList(),
    val evidenceIndexing: EvidenceIndexingReport? = null,
)
