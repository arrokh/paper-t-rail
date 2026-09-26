package com.papertrail.api.evidence.retrieval

import com.papertrail.api.evidence.domain.RankedEvidenceChunk
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class ReciprocalRankFusion {
    fun fuse(
        vectorRankedIds: List<UUID>,
        lexicalRankedIds: List<UUID>,
        rankConstant: Int,
        limit: Int,
    ): List<RankedEvidenceChunk> {
        require(rankConstant > 0) { "Reciprocal-rank fusion constant must be positive." }
        require(limit > 0) { "Evidence passage limit must be positive." }
        val vectorRanks = vectorRankedIds.withIndex().associate { (index, id) -> id to index + 1 }
        val lexicalRanks = lexicalRankedIds.withIndex().associate { (index, id) -> id to index + 1 }
        return (vectorRanks.keys + lexicalRanks.keys)
            .map { id ->
                val vectorRank = vectorRanks[id]
                val lexicalRank = lexicalRanks[id]
                val score = (vectorRank?.let { 1.0 / (rankConstant + it) } ?: 0.0) +
                    (lexicalRank?.let { 1.0 / (rankConstant + it) } ?: 0.0)
                RankedEvidenceChunk(
                    chunkId = id,
                    vectorRank = vectorRank,
                    lexicalRank = lexicalRank,
                    fusedRank = 0,
                    fusionScore = score,
                )
            }
            .sortedWith(
                compareByDescending<RankedEvidenceChunk> { it.fusionScore }
                    .thenBy { it.vectorRank ?: Int.MAX_VALUE }
                    .thenBy { it.lexicalRank ?: Int.MAX_VALUE },
            )
            .take(limit)
            .mapIndexed { index, candidate -> candidate.copy(fusedRank = index + 1) }
    }
}
