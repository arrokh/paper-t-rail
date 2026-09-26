package com.papertrail.api.evidence.embedding

import com.papertrail.api.evidence.domain.EmbeddingProfile
import com.papertrail.api.infrastructure.providers.DataCategory
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ln1p
import kotlin.math.sqrt

/** Deterministic local word-feature vectors; this is lexical retrieval, not a trained semantic model. */
@Component
class FeatureHashEmbeddingProvider : EmbeddingProvider {
    override val providerId = EmbeddingProfile.LOCAL_PROVIDER_ID
    override val modelId = EmbeddingProfile.MODEL_ID
    override val version = EmbeddingProfile.VERSION
    override val dimension = EmbeddingProfile.DIMENSION

    override fun embed(text: String, context: EmbeddingRequestContext): FloatArray {
        require(context.inputCategory in setOf(DataCategory.CITED_PAPER_CHUNKS, DataCategory.ATOMIC_CLAIMS)) {
            "Local embedding input category is not supported."
        }
        return embed(text)
    }

    fun embed(text: String): FloatArray {
        val terms = TERM_PATTERN.findAll(text.lowercase(Locale.ROOT))
            .map(MatchResult::value)
            .filterNot(String::isBlank)
            .toList()
        val features = buildList {
            terms.forEach { add("word:$it") }
            terms.zipWithNext().forEach { (left, right) -> add("pair:$left:$right") }
        }
        require(features.isNotEmpty()) { "Text contains no word features for local embedding." }
        val vector = FloatArray(dimension)
        features.groupingBy { it }.eachCount().forEach { (feature, frequency) ->
            val digest = MessageDigest.getInstance("SHA-256").digest(feature.toByteArray(Charsets.UTF_8))
            val bucket = (((digest[0].toInt() and 0xff) shl 24) or
                ((digest[1].toInt() and 0xff) shl 16) or
                ((digest[2].toInt() and 0xff) shl 8) or
                (digest[3].toInt() and 0xff)).toLong() and 0x7fffffffL
            val sign = if ((digest[4].toInt() and 1) == 0) 1f else -1f
            vector[(bucket % dimension).toInt()] += sign * ln1p(frequency.toDouble()).toFloat()
        }
        val norm = sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
        require(norm > 0.0 && norm.isFinite()) { "Text features did not produce a usable local embedding." }
        vector.indices.forEach { vector[it] = (vector[it] / norm).toFloat() }
        return vector
    }

    companion object {
        private val TERM_PATTERN = Regex("[\\p{L}\\p{N}]+")
    }
}
