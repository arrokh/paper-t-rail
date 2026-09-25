package com.papertrail.api.references.resolver

import com.papertrail.api.references.client.ScholarlyWork

data class ReferenceResolutionDecision(
    val status: ReferenceResolutionStatus,
    val reasonCode: String,
    val work: ScholarlyWork? = null,
    val score: Double? = null,
    val matchMethod: String? = null,
)
