package com.papertrail.api.scholarly.references.client

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot

interface ScholarlyMetadataLookupFactory {
    val providerId: String
    fun forRun(configuration: AnalysisConfigurationSnapshot): ScholarlyMetadataLookup
}
