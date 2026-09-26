package com.papertrail.api.evidence.embedding

interface EmbeddingProvider {
    val providerId: String
    val modelId: String
    val version: String
    val dimension: Int

    fun embed(text: String, context: EmbeddingRequestContext): FloatArray
}
