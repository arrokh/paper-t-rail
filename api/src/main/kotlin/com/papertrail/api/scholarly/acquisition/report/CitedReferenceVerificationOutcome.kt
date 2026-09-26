package com.papertrail.api.scholarly.acquisition.report

import com.papertrail.api.evidence.report.EvidencePassageReport
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
    val processingFailureReason: String?,
    val finalStatus: String?,
    val verificationScope: String,
    val terminalReason: String?,
    val evidenceConflict: Boolean,
    val aggregatorVersion: String?,
    val evidencePassages: List<EvidencePassageReport> = emptyList(),
)
