package com.papertrail.api.evidence.report

import com.papertrail.api.review.domain.HumanReview
import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class CitedReferenceVerificationOutcome(
    val id: UUID,
    val atomicClaimId: UUID,
    val claimText: String,
    val claimSourceStartOffset: Int,
    val claimSourceEndOffset: Int,
    val citationContextText: String,
    val citationMarkers: List<String>,
    val associationKind: String,
    val processingStatus: String,
    @field:Schema(description = "Sanitized content-free failure code for an incomplete pair. Jev HTTP failures use SYSTEM_ONE_HTTP_<status>; response validation failures use specific SYSTEM_ONE_RESPONSE_* codes. This value never contains provider response bodies or claim/evidence content.")
    val processingFailureReason: String?,
    val finalStatus: String?,
    val verificationScope: String,
    val terminalReason: String?,
    val evidenceConflict: Boolean,
    val aggregatorVersion: String?,
    val evidencePassages: List<EvidencePassageReport> = emptyList(),
    val humanReviews: List<HumanReview> = emptyList(),
)
