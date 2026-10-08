package com.papertrail.api.scholarly.references.resolver

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "A bounded candidate comparison. Its presence alone does not establish identity; consult the surrounding resolution status and method.")
data class ScholarlyCandidateEvidence(
    val doi: String?,
    val title: String,
    val authors: List<String>,
    val year: Int?,
    @field:Schema(description = "Uncalibrated score used by the run-pinned matching policy; it is not a probability or calibrated confidence.")
    val rankingScore: Double?,
    @field:Schema(description = "Content-free component outcomes explaining comparison with the Bibliography Entry.")
    val reasonCodes: List<String>,
)
