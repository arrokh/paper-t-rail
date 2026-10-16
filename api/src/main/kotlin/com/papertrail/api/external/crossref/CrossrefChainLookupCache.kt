package com.papertrail.api.external.crossref

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot

interface CrossrefChainLookupCache {
    fun find(
        configuration: AnalysisConfigurationSnapshot,
        query: CrossrefChainLookupQuery,
    ): CrossrefChainLookup?

    fun store(
        configuration: AnalysisConfigurationSnapshot,
        query: CrossrefChainLookupQuery,
        lookup: CrossrefChainLookup,
    )
}
