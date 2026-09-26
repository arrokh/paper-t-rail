package com.papertrail.api.evidence.domain

import java.util.UUID

data class RankedEvidenceChunk(
    val chunkId: UUID,
    val vectorRank: Int?,
    val lexicalRank: Int?,
    val fusedRank: Int,
    val fusionScore: Double,
)
