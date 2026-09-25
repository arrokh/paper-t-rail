package com.papertrail.api.scholarly.references.client

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallPayload
import com.papertrail.api.infrastructure.providers.SCHOLARLY_METADATA_ROLE
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI

class CrossrefScholarlyMetadataLookup(
    private val client: RestClient,
    private val objectMapper: ObjectMapper,
    private val callGate: ProviderCallGate,
    private val configuration: AnalysisConfigurationSnapshot,
    private val contactEmail: String?,
) : ScholarlyMetadataLookup {
    override fun byDoi(doi: String): ScholarlyWork? {
        val payload = payload(
            DataCategory.BIBLIOGRAPHIC_METADATA to objectMapper.valueToTree(mapOf("doi" to doi)),
        )
        return callGate.call(SCHOLARLY_METADATA_ROLE, "crossref", payload, configuration) { actualPayload ->
            val requestData = actualPayload.contentByCategory.getValue(DataCategory.BIBLIOGRAPHIC_METADATA)
            val requestDoi = requestData.path("doi").asText()
            val uriBuilder = UriComponentsBuilder.fromPath("/works/{doi}")
            actualPayload.contentByCategory[DataCategory.PROVIDER_CONTACT_EMAIL]
                ?.takeIf(JsonNode::isTextual)
                ?.asText()
                ?.let { uriBuilder.queryParam("mailto", it) }
            val uri = uriBuilder.buildAndExpand(requestDoi).encode().toUri()
            val response = exchange(uri) ?: return@call null
            response.path("message")?.let(::toWork)
        }
    }

    override fun search(reference: BibliographyReference): List<ScholarlyWork> {
        val searchText = listOfNotNull(
            reference.title?.takeIf(String::isNotBlank),
            reference.authors.takeIf(List<String>::isNotEmpty)?.joinToString(" "),
            reference.year?.toString(),
        ).joinToString(" ").trim()
        if (searchText.isBlank()) return emptyList()
        val payloadCategories = mutableMapOf<DataCategory, JsonNode>(
            DataCategory.BIBLIOGRAPHIC_METADATA to objectMapper.valueToTree<JsonNode>(mapOf("bibliographicQuery" to searchText)),
        )
        contactEmail?.let { payloadCategories[DataCategory.PROVIDER_CONTACT_EMAIL] = objectMapper.valueToTree<JsonNode>(it) }
        val payload = ProviderCallPayload(payloadCategories)
        return callGate.call(SCHOLARLY_METADATA_ROLE, "crossref", payload, configuration) { actualPayload ->
            val query = actualPayload.contentByCategory.getValue(DataCategory.BIBLIOGRAPHIC_METADATA)
                .path("bibliographicQuery").asText()
            val uriBuilder = UriComponentsBuilder.fromPath("/works")
                .queryParam("query.bibliographic", query)
                .queryParam("rows", MAX_RESULTS)
            actualPayload.contentByCategory[DataCategory.PROVIDER_CONTACT_EMAIL]
                ?.takeIf(JsonNode::isTextual)
                ?.asText()
                ?.let { uriBuilder.queryParam("mailto", it) }
            val response = exchange(uriBuilder.build().encode().toUri()) ?: return@call emptyList()
            response.path("message").path("items").mapNotNull(::toWork)
        }
    }

    private fun payload(vararg categories: Pair<DataCategory, JsonNode>): ProviderCallPayload {
        val content = categories.toMap().toMutableMap()
        contactEmail?.let { content[DataCategory.PROVIDER_CONTACT_EMAIL] = objectMapper.valueToTree(it) }
        return ProviderCallPayload(content)
    }

    private fun exchange(uri: URI): JsonNode? = client.get()
        .uri(uri)
        .accept(MediaType.APPLICATION_JSON)
        .exchange { _, response ->
            when {
                response.statusCode == HttpStatus.NOT_FOUND -> null
                !response.statusCode.is2xxSuccessful -> throw IllegalStateException("Crossref returned HTTP ${response.statusCode.value()}.")
                else -> objectMapper.readTree(response.body)
            }
        }

    private fun toWork(node: JsonNode): ScholarlyWork? {
        val doi = node.path("DOI").takeIf(JsonNode::isTextual)?.asText() ?: return null
        val title = node.path("title").firstOrNull()?.asText()?.takeIf(String::isNotBlank) ?: return null
        val authors = node.path("author").mapNotNull { author ->
            author.path("name").takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank)
                ?: listOfNotNull(
                    author.path("given").takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank),
                    author.path("family").takeIf(JsonNode::isTextual)?.asText()?.takeIf(String::isNotBlank),
                ).joinToString(" ").takeIf(String::isNotBlank)
        }
        val year = sequenceOf("published-print", "published-online", "issued")
            .mapNotNull { field -> node.path(field).path("date-parts").firstOrNull()?.firstOrNull()?.takeIf(JsonNode::isIntegralNumber)?.asInt() }
            .firstOrNull()
        return ScholarlyWork(doi, title, authors, year)
    }

    companion object {
        const val MAX_RESULTS = 10
    }
}
