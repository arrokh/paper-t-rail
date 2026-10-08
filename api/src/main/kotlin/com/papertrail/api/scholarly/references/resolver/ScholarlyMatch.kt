package com.papertrail.api.scholarly.references.resolver

import com.papertrail.api.scholarly.references.client.ScholarlyWork

data class ScholarlyMatch(
    val candidate: ScholarlyWork?,
    val score: Double?,
    val reasonCode: String,
    val candidateEvidence: List<ScholarlyCandidateEvidence> = emptyList(),
)
