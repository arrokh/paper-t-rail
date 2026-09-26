package com.papertrail.api.evidence.embedding

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FeatureHashEmbeddingProviderTest {
    @Test
    fun `produces a deterministic unit vector for non-empty text`() {
        val provider = FeatureHashEmbeddingProvider()

        val first = provider.embed("The intervention improved student outcomes.")
        val repeated = provider.embed("The intervention improved student outcomes.")
        val magnitude = kotlin.math.sqrt(first.sumOf { it.toDouble() * it.toDouble() })

        assertEquals(provider.dimension, first.size)
        assertContentEquals(first, repeated)
        assertTrue(kotlin.math.abs(magnitude - 1.0) < 0.00001)
    }

    @Test
    fun `rejects text that has no searchable word features`() {
        assertFailsWith<IllegalArgumentException> { FeatureHashEmbeddingProvider().embed("... — !!!") }
    }

    @Test
    fun `different terms produce different vectors`() {
        val provider = FeatureHashEmbeddingProvider()

        val intervention = provider.embed("The intervention improved student outcomes.")
        val control = provider.embed("The control group reported no change.")

        assertTrue(intervention.indices.any { intervention[it] != control[it] })
    }
}
