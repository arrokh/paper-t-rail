package com.papertrail.api.analysis.configuration

data class AggregationPolicySnapshot(
    val executionStatus: String,
    val verificationPolicyVersion: String?,
    val aggregationPolicyVersion: String?,
    val thresholds: Map<String, Double>?,
)
