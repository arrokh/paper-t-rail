package com.papertrail.api.scholarly.references.normalization

import java.util.Locale

object DoiNormalizer {
    private val doiPattern = Regex("^10\\.\\d{4,9}/[-._;()/:A-Z0-9]+$", RegexOption.IGNORE_CASE)

    fun normalize(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val normalized = value.trim()
            .replace(Regex("^https?://(?:dx\\.)?doi\\.org/", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^doi:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        return normalized.takeIf { doiPattern.matches(it) }?.lowercase(Locale.ROOT)
    }
}
