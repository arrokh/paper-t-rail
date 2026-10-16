package com.papertrail.api.external.crossref

import com.papertrail.api.analysis.configuration.ProviderChainSnapshot
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionProviderLookupOutcomeCode
import com.papertrail.api.scholarly.references.client.ScholarlyWork
import java.util.UUID

data class CrossrefChainLookup(
    val outcomeCode: ReferenceResolutionProviderLookupOutcomeCode,
    val works: List<ScholarlyWork>,
    val originRunId: UUID,
) {
    init {
        require(outcomeCode == ReferenceResolutionProviderLookupOutcomeCode.FOUND ||
            outcomeCode == ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND
        ) { "Only authoritative Crossref results may be cached." }
        require((outcomeCode == ReferenceResolutionProviderLookupOutcomeCode.FOUND) == works.isNotEmpty()) {
            "A cached found result must contain works; a cached not-found result must be empty."
        }
    }

    companion object {
        const val ORIGIN_PROVIDER_ID = ProviderChainSnapshot.CROSSREF_PROVIDER_ID
    }
}
