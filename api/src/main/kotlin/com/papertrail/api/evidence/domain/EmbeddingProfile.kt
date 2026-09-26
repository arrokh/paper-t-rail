package com.papertrail.api.evidence.domain

import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.infrastructure.crypto.sha256Hex

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
        const val MAX_VECTOR_DIMENSION = 16_000

        fun from(selection: ProviderSelection): EmbeddingProfile {
            if (selection.provider == LOCAL_PROVIDER_ID) {
                require(selection.model == MODEL_ID && selection.version == VERSION) {
                    "The pinned embedding profile is not available in this runtime."
                }
                return EmbeddingProfile(
                    providerId = selection.provider,
                    modelId = MODEL_ID,
                    version = selection.version,
                    dimension = DIMENSION,
                    profileHash = localProfileHash(selection.provider, MODEL_ID, selection.version, DIMENSION),
                )
            }

            val model = selection.model?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("The pinned embedding model is unavailable.")
            val dimension = selection.embeddingDimension
                ?.takeIf { it in 1..MAX_VECTOR_DIMENSION }
                ?: throw IllegalArgumentException("The pinned embedding dimension is unavailable.")
            val configurationFingerprint = selection.configurationFingerprint
                ?.takeIf { PROFILE_HASH_PATTERN.matches(it) }
                ?: throw IllegalArgumentException("The pinned embedding configuration is unavailable.")
            require(selection.provider.isNotBlank() && selection.version.isNotBlank()) {
                "The pinned embedding provider identity is unavailable."
            }
            return EmbeddingProfile(
                providerId = selection.provider,
                modelId = model,
                version = selection.version,
                dimension = dimension,
                profileHash = hashConfiguredProfile(
                    selection.provider,
                    model,
                    selection.version,
                    dimension,
                    configurationFingerprint,
                ),
            )
        }

        fun forDiagnosis(selection: ProviderSelection, storedProfileHash: String): EmbeddingProfile {
            if (selection.provider == LOCAL_PROVIDER_ID && selection.model == MODEL_ID && selection.version == VERSION) {
                return from(selection)
            }
            val model = selection.model?.takeIf(String::isNotBlank)
            val dimension = selection.embeddingDimension?.takeIf { it in 1..MAX_VECTOR_DIMENSION }
            val fingerprint = selection.configurationFingerprint?.takeIf(PROFILE_HASH_PATTERN::matches)
            if (selection.provider.isNotBlank() && selection.version.isNotBlank() &&
                model != null && dimension != null && fingerprint != null
            ) {
                return EmbeddingProfile(
                    selection.provider,
                    model,
                    selection.version,
                    dimension,
                    storedProfileHash.takeIf { PROFILE_HASH_PATTERN.matches(it) }
                        ?: hashConfiguredProfile(selection.provider, model, selection.version, dimension, fingerprint),
                )
            }
            val safeModel = model ?: "unconfigured-model"
            val hash = storedProfileHash.takeIf { PROFILE_HASH_PATTERN.matches(it) }
                ?: sha256Hex("unsupported|${selection.provider}|$safeModel|${selection.version}".toByteArray(Charsets.UTF_8))
            return EmbeddingProfile(selection.provider, safeModel, selection.version, 0, hash)
        }

        private fun localProfileHash(provider: String, model: String, version: String, dimension: Int): String {
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

        private fun hashConfiguredProfile(
            provider: String,
            model: String,
            version: String,
            dimension: Int,
            configurationFingerprint: String,
        ): String {
            val profileDescriptor = listOf(
                "paper-trail-embedding-profile-v1",
                provider,
                model,
                version,
                dimension.toString(),
                configurationFingerprint,
            ).joinToString("\n")
            return sha256Hex(profileDescriptor.toByteArray(Charsets.UTF_8))
        }

        private val PROFILE_HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}
