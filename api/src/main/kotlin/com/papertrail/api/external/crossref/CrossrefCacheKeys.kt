package com.papertrail.api.external.crossref

import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale

object CrossrefCacheKeys {
    private const val DOI_PREFIX = "crossref:doi:v1:"
    private const val SEARCH_PREFIX = "crossref:search:v1:"
    private val whitespace = Regex("\\s+")

    fun doi(value: String): String? = DoiNormalizer.normalize(value)?.let { "$DOI_PREFIX$it" }

    fun search(query: String): String? {
        val normalized = normalizeQuery(query)
        if (normalized.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "$SEARCH_PREFIX$digest"
    }

    fun normalizeQuery(query: String): String = Normalizer.normalize(query, Normalizer.Form.NFKC)
        .trim()
        .replace(whitespace, " ")
        .lowercase(Locale.ROOT)
}
