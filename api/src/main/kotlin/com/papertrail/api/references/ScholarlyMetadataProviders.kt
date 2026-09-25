package com.papertrail.api.references

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.providers.DataCategory
import com.papertrail.api.providers.ProviderCallGate
import com.papertrail.api.providers.ProviderCallPayload
import com.papertrail.api.providers.SCHOLARLY_METADATA_ROLE
import com.papertrail.api.runs.AnalysisConfigurationSnapshot
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

interface ScholarlyMetadataLookupFactory {
    val providerId: String
    fun forRun(configuration: AnalysisConfigurationSnapshot): ScholarlyMetadataLookup
}

@Component
class RecordedFixtureScholarlyMetadataLookupFactory(
    objectMapper: ObjectMapper,
) : ScholarlyMetadataLookupFactory {
    private val works: List<ScholarlyWork> = objectMapper.readTree(ClassPathResource("provider-fixtures/recorded-scholarly-works.json").inputStream)
        .map { node ->
            ScholarlyWork(
                doi = node.path("doi").takeIf(JsonNode::isTextual)?.asText(),
                title = node.path("title").asText(),
                authors = node.path("authors").map(JsonNode::asText),
                year = node.path("year").takeIf(JsonNode::isIntegralNumber)?.asInt(),
            )
        }

    override val providerId: String = "recorded-fixtures"

    override fun forRun(configuration: AnalysisConfigurationSnapshot): ScholarlyMetadataLookup = object : ScholarlyMetadataLookup {
        override fun byDoi(doi: String): ScholarlyWork? = works.firstOrNull { DoiNormalizer.normalize(it.doi) == doi }

        override fun search(reference: BibliographyReference): List<ScholarlyWork> = works
    }
}

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
        .accept(org.springframework.http.MediaType.APPLICATION_JSON)
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

@Configuration
class CrossrefClientConfiguration {
    @Bean
    fun crossrefRestClient(): RestClient {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val requestFactory = JdkClientHttpRequestFactory(client).apply { setReadTimeout(Duration.ofSeconds(20)) }
        return RestClient.builder().baseUrl("https://api.crossref.org").requestFactory(requestFactory).build()
    }
}
