package com.papertrail.api.analysis.configuration

/** Legacy snapshots retain 10/10/5 defaults; new runs pin deployment-configured candidate limits. */
data class RetrievalConfigurationSnapshot(
    val profileId: String = "postgres-hybrid-rrf-v1",
    val vectorCandidateLimit: Int = 10,
    val lexicalCandidateLimit: Int = 10,
    val finalCandidateLimit: Int = 5,
    val reciprocalRankFusionConstant: Int = 60,
    val embeddingProfileHash: String = "",
)
