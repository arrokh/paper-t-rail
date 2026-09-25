package com.papertrail.api.citation.claims.service

import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.reviewedExternalProviderCatalog
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ClaimExtractionServiceTest {
    @Test
    fun `rejects external claim extractors until their send path is consent-gated`() {
        val service = ClaimExtractionService(
            providerCatalog = reviewedExternalProviderCatalog(),
            providers = listOf(HeuristicClaimExtractor()),
        )

        assertThrows(IllegalArgumentException::class.java) {
            service.extract(providerId = "reviewed-llm", version = "v1", contexts = emptyList())
        }
    }

    @Test
    fun `rejects a claim extractor version that no longer matches its pinned provider`() {
        val service = ClaimExtractionService(
            providerCatalog = ProviderCatalog.safeDefaults(),
            providers = listOf(HeuristicClaimExtractor()),
        )

        assertThrows(IllegalArgumentException::class.java) {
            service.extract(providerId = "heuristic", version = "v2", contexts = emptyList())
        }
    }
}
