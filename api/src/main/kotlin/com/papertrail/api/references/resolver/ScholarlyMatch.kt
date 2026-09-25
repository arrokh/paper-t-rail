package com.papertrail.api.references.resolver

import com.papertrail.api.references.client.ScholarlyWork

data class ScholarlyMatch(
    val candidate: ScholarlyWork?,
    val score: Double?,
    val reasonCode: String,
)
