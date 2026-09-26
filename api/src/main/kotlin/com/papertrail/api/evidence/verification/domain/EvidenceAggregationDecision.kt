package com.papertrail.api.evidence.verification.domain

import com.papertrail.api.scholarly.acquisition.domain.TerminalVerificationStatus

data class EvidenceAggregationDecision(
    val finalStatus: TerminalVerificationStatus,
    val evidenceConflict: Boolean,
    val strongestSupport: Double?,
    val strongestContradiction: Double?,
)
