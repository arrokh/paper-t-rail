package com.papertrail.api.evidence.verification.domain

/** Synthetic threshold values for behavior tests only; these are not human-calibrated or release-ready. */
internal object TestEvidenceAggregationThresholds {
    val values = EvidenceAggregationThresholds(
        directSupport = 0.8,
        partialSupport = 0.7,
        contradiction = 0.8,
        comparabilityMargin = 0.08,
    )
}
