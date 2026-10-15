package com.papertrail.api.external.crossref

import com.papertrail.api.analysis.configuration.ProviderChainSnapshot
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.http.ExternalProviderConsentRequest
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.infrastructure.cache.ProviderCacheStore
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionProviderLookupOutcomeCode
import com.papertrail.api.scholarly.references.client.ScholarlyWork
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID

class RedisCrossrefChainLookupCacheTest {
    @Test
    fun `chain cache hashes title queries and binds reusable results to pinned consent`() {
        val configuration = providerChainConfiguration()
        val store = RecordingProviderCacheStore()
        val cache = RedisCrossrefChainLookupCache(store)
        val query = CrossrefChainLookupQuery.title("  private   bibliography title  ")
        val originRunId = UUID.randomUUID()
        val expected = CrossrefChainLookup(
            ReferenceResolutionProviderLookupOutcomeCode.FOUND,
            listOf(ScholarlyWork("10.5555/provider-result", "Provider result title", listOf("A. Author"), 2024)),
            originRunId,
        )

        cache.store(configuration, query, expected)

        assertEquals(expected, cache.find(configuration, query))
        assertEquals(1, store.values.size)
        val (key, value) = store.values.entries.single()
        assertFalse(key.contains("private", ignoreCase = true))
        assertFalse(value.contains("private bibliography title", ignoreCase = true))
        assertTrue(key.startsWith("paper-trail:provider-chain:crossref:v2:"))
        assertTrue(value.contains(originRunId.toString()))
        assertEquals(Duration.ofDays(30), store.ttls.single())

        val mismatchedDisclosure = configuration.copy(
            externalProviderConsents = configuration.externalProviderConsents.map { consent ->
                if (consent.providerId == ProviderChainSnapshot.CROSSREF_PROVIDER_ID) {
                    consent.copy(retentionDisclosure = "A different disclosure")
                } else {
                    consent
                }
            },
        )
        assertNull(cache.find(mismatchedDisclosure, query))
        store.values[key] = value.replace("\"originRunId\":\"$originRunId\"", "\"originRunId\":null")
        assertNull(cache.find(configuration, query), "Entries without a source run must be treated as cache misses.")
    }

    @Test
    fun `chain cache stores authoritative Crossref negative results with the shorter TTL`() {
        val configuration = providerChainConfiguration()
        val store = RecordingProviderCacheStore()
        val cache = RedisCrossrefChainLookupCache(store)
        val query = CrossrefChainLookupQuery.doi("https://doi.org/10.5555/not-found")
        val originRunId = UUID.randomUUID()

        cache.store(
            configuration,
            query,
            CrossrefChainLookup(ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND, emptyList(), originRunId),
        )

        assertEquals(
            CrossrefChainLookup(ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND, emptyList(), originRunId),
            cache.find(configuration, query),
        )
        assertEquals(Duration.ofHours(1), store.ttls.single())
        assertFalse(store.values.keys.single().contains("10.5555/not-found"))
    }

    private fun providerChainConfiguration() = providerChainCatalog().let { catalog ->
        RunConfigurationFactory(
            providerCatalog = catalog,
            parserId = "grobid",
            parserVersion = "0.9.1-crf",
            languageDetectorVersion = "0.6",
            limits = ValidationLimitsSnapshot(1_000_000, 20, 100_000, 100_000, 100, 0.65),
        ).from(
            RunConfigurationRequest(
                providerChainId = ProviderChainSnapshot.CHAIN_ID,
                scholarlyMetadataProvider = null,
                externalProviderConsents = ProviderChainSnapshot.PROVIDER_IDS.map { providerId ->
                    val registration = catalog.requireProviderChain(ProviderChainSnapshot.CHAIN_ID)
                        .single { it.providerId == providerId }
                    ExternalProviderConsentRequest(
                        providerId = providerId,
                        dataCategories = registration.dataCategories.map(DataCategory::id).sorted(),
                        retentionDisclosureFingerprint = requireNotNull(registration.consentDisclosureFingerprint()),
                    )
                },
            ),
        )
    }

    private fun providerChainCatalog() = ProviderCatalog.safeDefaults(
        providerChainEnabled = true,
        crossrefEnabled = true,
        crossrefRetentionDisclosure = "Crossref exact DOI chain test disclosure.",
        dataCiteEnabled = true,
        dataCiteRetentionDisclosure = "DataCite exact DOI chain test disclosure.",
    )

    private class RecordingProviderCacheStore : ProviderCacheStore {
        val values = linkedMapOf<String, String>()
        val ttls = mutableListOf<Duration>()

        override fun find(key: String): String? = values[key]

        override fun store(key: String, value: String, ttl: Duration) {
            values[key] = value
            ttls += ttl
        }

        override fun invalidate(key: String): Boolean = values.remove(key) != null
    }
}
