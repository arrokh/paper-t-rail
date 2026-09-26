package com.papertrail.api.evidence.verification.domain

data class EvidenceAggregationThresholds(
    val directSupport: Double,
    val partialSupport: Double,
    val contradiction: Double,
    val comparabilityMargin: Double,
) {
    init {
        require(listOf(directSupport, partialSupport, contradiction, comparabilityMargin).all { it in 0.0..1.0 }) {
            "Evidence aggregation thresholds must be between zero and one."
        }
    }

    fun asMap(): Map<String, Double> = mapOf(
        DIRECT_SUPPORT to directSupport,
        PARTIAL_SUPPORT to partialSupport,
        CONTRADICTION to contradiction,
        COMPARABILITY_MARGIN to comparabilityMargin,
    )

    companion object {
        const val DIRECT_SUPPORT = "directSupport"
        const val PARTIAL_SUPPORT = "partialSupport"
        const val CONTRADICTION = "contradiction"
        const val COMPARABILITY_MARGIN = "comparabilityMargin"

        val CALIBRATED_V1 = EvidenceAggregationThresholds(
            directSupport = 0.8,
            partialSupport = 0.7,
            contradiction = 0.8,
            comparabilityMargin = 0.08,
        )
    }
}
