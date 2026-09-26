package com.papertrail.api.scholarly.acquisition.report

data class EvidenceRetrievalProfileReport(
    val profileId: String,
    val vectorCandidateLimit: Int,
    val lexicalCandidateLimit: Int,
    val finalCandidateLimit: Int,
    val reciprocalRankFusionConstant: Int,
    val embeddingProvider: String,
    val embeddingModel: String,
    val embeddingVersion: String,
    val embeddingDimension: Int,
    val embeddingProfileHash: String,
)
