package com.papertrail.api.external.crossref

import com.papertrail.api.scholarly.references.normalization.DoiNormalizer

class CrossrefChainLookupQuery private constructor(
    val kind: Kind,
    internal val normalizedValue: String,
) {
    enum class Kind { DOI, TITLE }

    override fun toString(): String = "CrossrefChainLookupQuery(kind=$kind, value=<redacted>)"

    companion object {
        fun doi(value: String): CrossrefChainLookupQuery {
            val normalized = DoiNormalizer.normalize(value)
                ?: throw IllegalArgumentException("A chain-cache DOI query must be valid.")
            return CrossrefChainLookupQuery(Kind.DOI, normalized)
        }

        fun title(value: String): CrossrefChainLookupQuery {
            val normalized = value.trim().replace(WHITESPACE, " ")
            require(normalized.isNotEmpty()) { "A chain-cache title query must not be blank." }
            return CrossrefChainLookupQuery(Kind.TITLE, normalized)
        }

        private val WHITESPACE = Regex("\\s+")
    }
}
