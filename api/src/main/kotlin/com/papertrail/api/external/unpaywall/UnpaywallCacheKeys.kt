package com.papertrail.api.external.unpaywall

import com.papertrail.api.scholarly.references.normalization.DoiNormalizer

object UnpaywallCacheKeys {
    private const val DOI_PREFIX = "unpaywall:doi:v1:"

    fun doi(value: String): String? = DoiNormalizer.normalize(value)?.let { "$DOI_PREFIX$it" }
}
