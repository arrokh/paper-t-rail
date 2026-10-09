package com.papertrail.api.scholarly.acquisition.client

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.OPEN_ACCESS_ROLE
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallPayload
import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessDiscovery
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessLocation
import com.papertrail.api.scholarly.references.client.BibliographyReference
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class RecordedFixtureOpenAccessProviderFactory(
    private val providerCallGate: ProviderCallGate,
) : OpenAccessProviderFactory {
    private val records = JsonUtil.parseTree(ClassPathResource("provider-fixtures/open-access-records.json").inputStream)

    override val providerId: String = RECORDED_FIXTURES_PROVIDER

    override fun forRun(configuration: AnalysisConfigurationSnapshot): OpenAccessProvider = object : OpenAccessProvider {
        override fun discover(reference: BibliographyReference): OpenAccessDiscovery? {
            val metadata = bibliographicMetadata(reference)
            return providerCallGate.call(
                OPEN_ACCESS_ROLE,
                providerId,
                ProviderCallPayload(mapOf(DataCategory.BIBLIOGRAPHIC_METADATA to metadata)),
                configuration,
            ) {
                val matching = records.firstOrNull { node -> node.path("doi").asText().equals(reference.doi, ignoreCase = true) }
                    ?: return@call null
                val locations = matching.path("locations").map { location ->
                    OpenAccessLocation(
                        url = location.path("url").asText(),
                        license = location.path("license").takeIf(JsonNode::isTextual)?.asText(),
                        version = location.path("version").takeIf(JsonNode::isTextual)?.asText(),
                        hostType = location.path("hostType").takeIf(JsonNode::isTextual)?.asText(),
                        providerId = providerId,
                    )
                }
                OpenAccessDiscovery(
                    metadataAvailable = true,
                    abstractAvailable = matching.path("abstract").takeIf(JsonNode::isTextual)?.asText()?.isNotBlank() == true,
                    locations = locations,
                    providerId = providerId,
                    discoveredAt = Instant.now(),
                )
            }
        }

        override fun fetch(location: OpenAccessLocation): AcquiredFullText {
            val payload = JsonUtil.toTree(mapOf("url" to location.url))
            return providerCallGate.call(
                OPEN_ACCESS_ROLE,
                providerId,
                ProviderCallPayload(mapOf(DataCategory.CITED_PAPER_LOCATION to payload)),
                configuration,
            ) {
                val matching = records.flatMap { it.path("locations").toList() }
                    .firstOrNull { it.path("url").asText() == location.url }
                    ?: throw IllegalArgumentException("Recorded full-text location is not present in the fixture set.")
                val resource = matching.path("pdfResource").takeIf(JsonNode::isTextual)?.asText()
                    ?: matching.path("textResource").asText()
                val mediaType = matching.path("mediaType").takeIf(JsonNode::isTextual)?.asText()
                    ?: "text/plain"
                AcquiredFullText(
                    bytes = ClassPathResource(resource).inputStream.use { it.readBytes() },
                    mediaType = mediaType,
                    location = location,
                )
            }
        }
    }

    private fun bibliographicMetadata(reference: BibliographyReference): JsonNode {
        val node = JsonUtil.objectNode()
        reference.doi?.takeIf(String::isNotBlank)?.let { node.put("doi", it) }
        reference.title?.takeIf(String::isNotBlank)?.let { node.put("title", it) }
        if (reference.authors.isNotEmpty()) node.set<JsonNode>("authors", JsonUtil.toTree(reference.authors))
        reference.year?.let { node.put("year", it) }
        return node
    }

    companion object {
        const val RECORDED_FIXTURES_PROVIDER = "recorded-fixtures"
    }
}
