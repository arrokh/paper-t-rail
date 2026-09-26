package com.papertrail.api.evidence.verification.domain

import java.util.UUID

data class EvidencePassageForJudgement(
    val id: UUID,
    val text: String,
    val sectionHeading: String?,
)
