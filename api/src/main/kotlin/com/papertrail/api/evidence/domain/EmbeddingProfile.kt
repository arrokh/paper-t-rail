package com.papertrail.api.evidence.domain

import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.infrastructure.crypto.sha256Hex

/** Stable identity of the exact local vectorizer configuration used by one Analysis Run. */
data class EmbeddingProfile(
    val providerId: String,
    val modelId: String,
    val version: String,
    val dimension: Int,
    val profileHash: String,
) {
    companion object {
        const val LOCAL_PROVIDER_ID = "local"
        const val MODEL_ID = "feature-hash-384-v1"
        const val VERSION = "v1"
        const val DIMENSION = 384

        fun from(selection: ProviderSelection): EmbeddingProfile {
            require(selection.provider == LOCAL_PROVIDER_ID && selection.model == MODEL_ID && selection.version == VERSION) {
                "The pinned embedding profile is not available in this runtime."
            }
            return EmbeddingProfile(
                providerId = selection.provider,
                modelId = MODEL_ID,
                version = selection.version,
                dimension = DIMENSION,
                profileHash = profileHash(selection.provider, MODEL_ID, selection.version, DIMENSION),
            )
        }

        fun forDiagnosis(selection: ProviderSelection, configuredProfileHash: String): EmbeddingProfile {
            if (selection.provider == LOCAL_PROVIDER_ID && selection.model == MODEL_ID && selection.version == VERSION) {
                return from(selection)
            }
            val model = selection.model?.takeIf(String::isNotBlank) ?: "unconfigured-model"
            val hash = configuredProfileHash.takeIf { PROFILE_HASH_PATTERN.matches(it) }
                ?: sha256Hex("unsupported|${selection.provider}|$model|${selection.version}".toByteArray(Charsets.UTF_8))
            return EmbeddingProfile(selection.provider, model, selection.version, 0, hash)
        }

        private fun profileHash(provider: String, model: String, version: String, dimension: Int): String {
            val profileDescriptor = listOf(
                "paper-trail-embedding-profile-v1",
                provider,
                model,
                version,
                dimension.toString(),
                "word-unigram-bigram-feature-hash",
                "unit-l2",
            ).joinToString("\n")
            return sha256Hex(profileDescriptor.toByteArray(Charsets.UTF_8))
        }

        private val PROFILE_HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}
