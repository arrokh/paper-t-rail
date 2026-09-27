package com.papertrail.api.scholarly.references.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.scholarly.references.client.CrossrefLookupCache
import com.papertrail.api.scholarly.references.client.CrossrefScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookupFactory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class CrossrefScholarlyMetadataLookupFactory(
    @Qualifier("crossrefRestClient") private val client: RestClient,
    private val objectMapper: ObjectMapper,
    private val callGate: ProviderCallGate,
    private val cache: CrossrefLookupCache,
    @Value("\${paper-trail.providers.crossref.contact-email:}") private val contactEmail: String,
) : ScholarlyMetadataLookupFactory {
    override val providerId: String = "crossref"

    override fun forRun(configuration: AnalysisConfigurationSnapshot): ScholarlyMetadataLookup = CrossrefScholarlyMetadataLookup(
        client = client,
        objectMapper = objectMapper,
        callGate = callGate,
        configuration = configuration,
        contactEmail = contactEmail.takeIf(String::isNotBlank),
        cache = cache,
    )
}
