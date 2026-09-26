package com.papertrail.api.evidence.verification.domain

data class SemanticJudgementRequest(
    val atomicClaim: AtomicClaimForJudgement,
    val evidencePassages: List<EvidencePassageForJudgement>,
)
