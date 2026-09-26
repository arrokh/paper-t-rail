package com.papertrail.api.evidence.verification.domain

enum class EvidenceRole(val strengthMultiplier: Double) {
    PRIMARY_FINDING(1.0),
    AUTHOR_SYNTHESIS(0.9),
    SECONDARY_REPORT(0.55),
}
