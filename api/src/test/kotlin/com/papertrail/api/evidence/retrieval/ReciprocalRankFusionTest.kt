package com.papertrail.api.evidence.retrieval

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ReciprocalRankFusionTest {
    @Test
    fun `breaks equal fusion scores by the ordered candidate ranks rather than random IDs`() {
        val vectorFirst = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
        val lexicalFirst = UUID.fromString("00000000-0000-0000-0000-000000000001")

        val results = ReciprocalRankFusion().fuse(
            vectorRankedIds = listOf(vectorFirst, lexicalFirst),
            lexicalRankedIds = listOf(lexicalFirst, vectorFirst),
            rankConstant = 60,
            limit = 2,
        )

        assertEquals(listOf(vectorFirst, lexicalFirst), results.map { it.chunkId })
        assertEquals(listOf(1, 2), results.map { it.fusedRank })
    }

    @Test
    fun `merges vector and lexical ranks with deterministic reciprocal rank fusion`() {
        val first = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val second = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val third = UUID.fromString("00000000-0000-0000-0000-000000000003")

        val results = ReciprocalRankFusion().fuse(
            vectorRankedIds = listOf(first, second),
            lexicalRankedIds = listOf(third, first),
            rankConstant = 60,
            limit = 2,
        )

        assertEquals(listOf(first, third), results.map { it.chunkId })
        assertEquals(listOf(1, 2), results.map { it.fusedRank })
        assertEquals(1, results[0].vectorRank)
        assertEquals(2, results[0].lexicalRank)
        assertEquals(null, results[1].vectorRank)
        assertEquals(1, results[1].lexicalRank)
        assertEquals(1.0 / 61.0 + 1.0 / 62.0, results[0].fusionScore)
        assertEquals(1.0 / 61.0, results[1].fusionScore)
    }
}
