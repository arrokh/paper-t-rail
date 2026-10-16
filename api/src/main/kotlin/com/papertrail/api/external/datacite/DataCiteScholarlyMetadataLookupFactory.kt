package com.papertrail.api.external.datacite

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookupFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class DataCiteScholarlyMetadataLookupFactory(
    @Qualifier("dataCiteRestClient") private val client: RestClient,
    private val callGate: ProviderCallGate,
) : ScholarlyMetadataLookupFactory {
    override val providerId: String = DataCiteScholarlyMetadataLookup.PROVIDER_ID

    override fun forRun(configuration: AnalysisConfigurationSnapshot): ScholarlyMetadataLookup =
        DataCiteScholarlyMetadataLookup(client, callGate, configuration)
}
