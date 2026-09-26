package com.papertrail.api.scholarly.acquisition.client

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot

interface OpenAccessProviderFactory {
    val providerId: String
    fun forRun(configuration: AnalysisConfigurationSnapshot): OpenAccessProvider
}
