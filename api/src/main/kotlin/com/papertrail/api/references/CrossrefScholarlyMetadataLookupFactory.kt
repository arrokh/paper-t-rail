package com.papertrail.api.references

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.providers.ProviderCallGate
import com.papertrail.api.runs.AnalysisConfigurationSnapshot
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
class CrossrefScholarlyMetadataLookupFactory(
    @Qualifier("crossrefRestClient") private val client: RestClient,
    private val objectMapper: ObjectMapper,
    private val callGate: ProviderCallGate,
    @Value("\${paper-trail.providers.crossref.contact-email:}") private val contactEmail: String,
) : ScholarlyMetadataLookupFactory {
    override val providerId: String = "crossref"

    override fun forRun(configuration: AnalysisConfigurationSnapshot): ScholarlyMetadataLookup = CrossrefScholarlyMetadataLookup(
        client = client,
        objectMapper = objectMapper,
        callGate = callGate,
        configuration = configuration,
        contactEmail = contactEmail.takeIf(String::isNotBlank),
    )
}
