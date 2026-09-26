package com.papertrail.api.scholarly.acquisition.report

import com.papertrail.api.evidence.report.EvidencePassageReport
import java.util.UUID

data class CitedReferenceVerificationOutcome(
    val atomicClaimId: UUID,
    val claimText: String,
    val finalStatus: String?,
    val verificationScope: String,
    val terminalReason: String?,
    val evidencePassages: List<EvidencePassageReport> = emptyList(),
)
