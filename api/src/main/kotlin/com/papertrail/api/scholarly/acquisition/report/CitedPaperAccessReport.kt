package com.papertrail.api.scholarly.acquisition.report

import com.papertrail.api.evidence.report.CitedReferenceVerificationOutcome
import com.papertrail.api.evidence.report.EvidenceIndexingReport
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessOrigin
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class CitedPaperAccessReport(
    @field:Schema(description = "Persisted access outcome; FULL_TEXT_AVAILABLE means a full-text asset was stored, not that it supports a claim.")
    val accessStatus: String,
    @field:Schema(description = "Provenance category for this access record.")
    val accessOrigin: CitedPaperAccessOrigin,
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
    @field:Schema(description = "Automatic provider; null for a user-supplied asset.", nullable = true)
    val providerId: String?,
    @field:Schema(description = "Automatic provider's source location; null for a user-supplied asset.", nullable = true)
    val sourceUrl: String?,
    val license: String?,
    val version: String?,
    val hostType: String?,
    @field:Schema(description = "Timestamp when automatic location discovery occurred or the user-supplied access record was created.")
    val discoveredAt: Instant,
    val contentSha256: String?,
    @field:Schema(description = "Persisted full-text media type; user-supplied assets require application/pdf.", nullable = true)
    val mediaType: String?,
    val language: String?,
    val languageDetectorVersion: String?,
    @field:Schema(description = "Immutable copied source-use rights declaration. It is attribution only and does not grant publication or external-transfer rights.", nullable = true)
    val rightsDeclarationVersion: String?,
    @field:Schema(description = "Exact declaration text copied from the originating Recovery Batch; it does not grant publication or external-transfer rights.", nullable = true)
    val rightsDeclarationText: String?,
    val rightsDeclaredAt: Instant?,
    val originatingAnalysisRunId: UUID?,
    val originatingRecoveryBatchId: UUID?,
    val originatingRecoveryUploadId: UUID?,
    val verificationOutcomes: List<CitedReferenceVerificationOutcome> = emptyList(),
    val evidenceIndexing: EvidenceIndexingReport? = null,
)
