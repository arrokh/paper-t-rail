package com.papertrail.api.scholarly.references.identity

data class CitedWorkIdentityDecision(
    val outcome: CitedWorkIdentityOutcome,
    val reasonCode: String,
)
