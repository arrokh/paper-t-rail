package com.papertrail.api.external.crossref

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ProviderChainSnapshot
import com.papertrail.api.infrastructure.cache.ProviderCacheStore
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionProviderLookupOutcomeCode
import com.papertrail.api.scholarly.references.client.ScholarlyWork
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import com.papertrail.api.utils.JsonUtil
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

@Component
class RedisCrossrefChainLookupCache(
    private val store: ProviderCacheStore,
) : CrossrefChainLookupCache {
    override fun find(
        configuration: AnalysisConfigurationSnapshot,
        query: CrossrefChainLookupQuery,
    ): CrossrefChainLookup? {
        val expected = envelope(configuration, query, null) ?: return null
        val serialized = store.find(cacheKey(expected.queryFingerprint, expected.cacheScopeFingerprint)) ?: return null
        val cached = runCatching {
            JsonUtil.fromJson(serialized, Envelope::class.java)
        }.getOrNull() ?: return null
        if (!cached.matches(expected)) return null
        val outcome = cached.outcomeCode?.let { code ->
            runCatching { ReferenceResolutionProviderLookupOutcomeCode.valueOf(code) }.getOrNull()
        } ?: return null
        val originRunId = cached.originRunId ?: return null
        val lookup = runCatching { CrossrefChainLookup(outcome, cached.works, originRunId) }.getOrNull() ?: return null
        if (lookup.works.size > maximumCandidateCount(query, configuration)) return null
        if (query.kind == CrossrefChainLookupQuery.Kind.DOI &&
            lookup.works.any { it.doi?.let(DoiNormalizer::normalize) != query.normalizedValue }
        ) return null
        return lookup
    }

    override fun store(
        configuration: AnalysisConfigurationSnapshot,
        query: CrossrefChainLookupQuery,
        lookup: CrossrefChainLookup,
    ) {
        if (lookup.works.size > maximumCandidateCount(query, configuration)) return
        if (query.kind == CrossrefChainLookupQuery.Kind.DOI &&
            lookup.works.any { it.doi?.let(DoiNormalizer::normalize) != query.normalizedValue }
        ) return
        val envelope = envelope(configuration, query, lookup) ?: return
        val ttl = when (lookup.outcomeCode) {
            ReferenceResolutionProviderLookupOutcomeCode.FOUND -> POSITIVE_TTL
            ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND -> NEGATIVE_TTL
            else -> return
        }
        store.store(
            cacheKey(envelope.queryFingerprint, envelope.cacheScopeFingerprint),
            JsonUtil.toJson(envelope),
            ttl,
        )
    }

    private fun envelope(
        configuration: AnalysisConfigurationSnapshot,
        query: CrossrefChainLookupQuery,
        lookup: CrossrefChainLookup?,
    ): Envelope? {
        val chain = configuration.referenceResolution.providerChain ?: return null
        if (chain.chainId != ProviderChainSnapshot.CHAIN_ID) return null
        val selection = chain.providers.singleOrNull { it.provider == ProviderChainSnapshot.CROSSREF_PROVIDER_ID }
            ?: return null
        val consent = configuration.externalProviderConsents.singleOrNull { it.providerId == selection.provider }
            ?: return null
        if (selection.retentionDisclosureFingerprint.isNullOrBlank() ||
            consent.dataCategories.distinct().size != consent.dataCategories.size ||
            consent.dataCategories.toSet() != selection.dataCategories.toSet() ||
            consent.retentionDisclosure != selection.retentionDisclosure
        ) return null

        val categories = selection.dataCategories.sorted()
        val scopeFingerprint = digest(
            listOf(
                chain.chainId,
                ProviderChainSnapshot.CROSSREF_PROVIDER_ID,
                selection.version,
                selection.configurationFingerprint ?: "<no-payload-configuration>",
                chain.mappingVersion,
                chain.policyVersion,
                categories.joinToString(","),
                selection.retentionDisclosureFingerprint,
            ).joinToString("\u0000"),
        )
        val queryFingerprint = digest("${query.kind.name}\u0000${query.normalizedValue}")
        return Envelope(
            schemaVersion = CACHE_SCHEMA_VERSION,
            chainId = chain.chainId,
            providerId = ProviderChainSnapshot.CROSSREF_PROVIDER_ID,
            providerVersion = selection.version,
            providerConfigurationFingerprint = selection.configurationFingerprint,
            mappingVersion = chain.mappingVersion,
            policyVersion = chain.policyVersion,
            dataCategories = categories,
            retentionDisclosureFingerprint = selection.retentionDisclosureFingerprint,
            queryFingerprint = queryFingerprint,
            cacheScopeFingerprint = scopeFingerprint,
            originRunId = lookup?.originRunId,
            outcomeCode = lookup?.outcomeCode?.name,
            works = lookup?.works.orEmpty(),
        )
    }

    private fun maximumCandidateCount(
        query: CrossrefChainLookupQuery,
        configuration: AnalysisConfigurationSnapshot,
    ): Int = if (query.kind == CrossrefChainLookupQuery.Kind.DOI) {
        1
    } else {
        configuration.referenceResolution.providerChain?.limits?.crossrefCandidateLimit ?: 0
    }

    private fun cacheKey(queryFingerprint: String, cacheScopeFingerprint: String): String =
        "$KEY_PREFIX:$cacheScopeFingerprint:$queryFingerprint"

    private fun Envelope.matches(expected: Envelope): Boolean =
        schemaVersion == expected.schemaVersion &&
            chainId == expected.chainId &&
            providerId == expected.providerId &&
            providerVersion == expected.providerVersion &&
            providerConfigurationFingerprint == expected.providerConfigurationFingerprint &&
            mappingVersion == expected.mappingVersion &&
            policyVersion == expected.policyVersion &&
            dataCategories == expected.dataCategories &&
            retentionDisclosureFingerprint == expected.retentionDisclosureFingerprint &&
            queryFingerprint == expected.queryFingerprint &&
            cacheScopeFingerprint == expected.cacheScopeFingerprint &&
            originRunId != null &&
            outcomeCode != null

    private data class Envelope(
        val schemaVersion: Int,
        val chainId: String,
        val providerId: String,
        val providerVersion: String,
        val providerConfigurationFingerprint: String?,
        val mappingVersion: String,
        val policyVersion: String,
        val dataCategories: List<String>,
        val retentionDisclosureFingerprint: String,
        val queryFingerprint: String,
        val cacheScopeFingerprint: String,
        val originRunId: UUID?,
        val outcomeCode: String?,
        val works: List<ScholarlyWork>,
    )

    companion object {
        private const val CACHE_SCHEMA_VERSION = 2
        private const val KEY_PREFIX = "paper-trail:provider-chain:crossref:v2"
        private val POSITIVE_TTL: Duration = Duration.ofDays(30)
        private val NEGATIVE_TTL: Duration = Duration.ofHours(1)

        private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
