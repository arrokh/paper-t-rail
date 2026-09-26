package com.papertrail.api.evidence.verification.domain

enum class EvidenceJudgementKind {
    DIRECT_SUPPORT,
    PARTIAL_SUPPORT,
    CONTRADICTS,
    UNRELATED,
    INSUFFICIENT,
}
