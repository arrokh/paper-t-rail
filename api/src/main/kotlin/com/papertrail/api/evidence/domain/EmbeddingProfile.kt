package com.papertrail.api.evidence.domain

import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.evidence.embedding.OllamaEmbeddingSettings
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

        fun from(selection: ProviderSelection): EmbeddingProfile = when (selection.provider) {
            LOCAL_PROVIDER_ID -> {
                require(selection.model == MODEL_ID && selection.version == VERSION) {
                    "The pinned embedding profile is not available in this runtime."
                }
                EmbeddingProfile(
                    providerId = selection.provider,
                    modelId = MODEL_ID,
                    version = selection.version,
                    dimension = DIMENSION,
                    profileHash = localProfileHash(selection.provider, MODEL_ID, selection.version, DIMENSION),
                )
            }
            OllamaEmbeddingSettings.PROVIDER_ID -> {
                val model = selection.model?.takeIf(String::isNotBlank)
                    ?: throw IllegalArgumentException("The pinned Ollama embedding model is unavailable.")
                val dimension = selection.embeddingDimension
                    ?.takeIf { it in 1..OllamaEmbeddingSettings.MAX_DIMENSION }
                    ?: throw IllegalArgumentException("The pinned Ollama embedding dimension is unavailable.")
                val configurationFingerprint = selection.configurationFingerprint
                    ?.takeIf { PROFILE_HASH_PATTERN.matches(it) }
                    ?: throw IllegalArgumentException("The pinned Ollama endpoint configuration is unavailable.")
                require(selection.version == OllamaEmbeddingSettings.VERSION) {
                    "The pinned Ollama embedding profile is not available in this runtime."
                }
                EmbeddingProfile(
                    providerId = selection.provider,
                    modelId = model,
                    version = selection.version,
                    dimension = dimension,
                    profileHash = ollamaProfileHash(model, selection.version, dimension, configurationFingerprint),
                )
            }
            else -> throw IllegalArgumentException("The pinned embedding profile is not available in this runtime.")
        }

        fun forDiagnosis(selection: ProviderSelection, configuredProfileHash: String): EmbeddingProfile {
            if (selection.provider == LOCAL_PROVIDER_ID && selection.model == MODEL_ID && selection.version == VERSION) {
                return from(selection)
            }
            if (selection.provider == OllamaEmbeddingSettings.PROVIDER_ID &&
                selection.version == OllamaEmbeddingSettings.VERSION &&
                !selection.model.isNullOrBlank() &&
                selection.embeddingDimension in 1..OllamaEmbeddingSettings.MAX_DIMENSION &&
                selection.configurationFingerprint?.let(PROFILE_HASH_PATTERN::matches) == true
            ) {
                return EmbeddingProfile(
                    selection.provider,
                    selection.model,
                    selection.version,
                    selection.embeddingDimension!!,
                    configuredProfileHash.takeIf { PROFILE_HASH_PATTERN.matches(it) }
                        ?: ollamaProfileHash(
                            selection.model,
                            selection.version,
                            selection.embeddingDimension,
                            selection.configurationFingerprint,
                        ),
                )
            }
            val model = selection.model?.takeIf(String::isNotBlank) ?: "unconfigured-model"
            val hash = configuredProfileHash.takeIf { PROFILE_HASH_PATTERN.matches(it) }
                ?: sha256Hex("unsupported|${selection.provider}|$model|${selection.version}".toByteArray(Charsets.UTF_8))
            return EmbeddingProfile(selection.provider, model, selection.version, 0, hash)
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

        private fun ollamaProfileHash(model: String, version: String, dimension: Int, configurationFingerprint: String): String {
            val profileDescriptor = listOf(
                "paper-trail-embedding-profile-v1",
                OllamaEmbeddingSettings.PROVIDER_ID,
                model,
                version,
                dimension.toString(),
                configurationFingerprint,
                "ollama-api-embed",
                "truncate-false",
                "raw-vector",
            ).joinToString("\n")
            return sha256Hex(profileDescriptor.toByteArray(Charsets.UTF_8))
        }

        private val PROFILE_HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}
