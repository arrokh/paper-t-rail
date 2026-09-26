package com.papertrail.api.evidence.embedding

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.infrastructure.providers.DataCategory

data class EmbeddingRequestContext(
    val configuration: AnalysisConfigurationSnapshot,
    val inputCategory: DataCategory,
) {
    companion object {
        internal val SUPPORTED_INPUT_CATEGORIES = setOf(DataCategory.CITED_PAPER_CHUNKS, DataCategory.ATOMIC_CLAIMS)
    }
}
