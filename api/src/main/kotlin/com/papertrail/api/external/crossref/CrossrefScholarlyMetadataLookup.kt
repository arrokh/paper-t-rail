package com.papertrail.api.external.crossref

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.execution.service.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.service.ExecutionCaptureSanitizer
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallPayload
import com.papertrail.api.infrastructure.providers.SCHOLARLY_METADATA_ROLE
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.URI

import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.client.ScholarlyWork

class CrossrefScholarlyMetadataLookup(
    private val client: RestClient,
    private val callGate: ProviderCallGate,
    private val configuration: AnalysisConfigurationSnapshot,
    private val contactEmail: String?,
    private val cache: CrossrefLookupCache,
    private val executionService: AnalysisRunExecutionService? = null,
) : ScholarlyMetadataLookup {
    override fun byDoi(doi: String): ScholarlyWork? {
        val payload = payload(
            DataCategory.BIBLIOGRAPHIC_METADATA to JsonUtil.toTree(mapOf("doi" to doi)),
        )
        return callGate.call(SCHOLARLY_METADATA_ROLE, "crossref", payload, configuration) { actualPayload ->
            val requestData = actualPayload.contentByCategory.getValue(DataCategory.BIBLIOGRAPHIC_METADATA)
            val requestDoi = requestData.path("doi").asText().let { DoiNormalizer.normalize(it) ?: it }
            cache.findByDoi(requestDoi)?.let { return@call it.firstOrNull() }
            val uriBuilder = UriComponentsBuilder.fromPath("/works/{doi}")
            actualPayload.contentByCategory[DataCategory.PROVIDER_CONTACT_EMAIL]
                ?.takeIf(JsonNode::isTextual)
                ?.asText()
                ?.let { uriBuilder.queryParam("mailto", it) }
            val uri = uriBuilder.buildAndExpand(requestDoi).encode().toUri()
            val response = exchange(uri, "crossref-doi-lookup", "/works")
            val works = response?.path("message")?.let(::toWork)?.let(::listOf).orEmpty()
            cache.storeByDoi(requestDoi, works)
            works.firstOrNull()
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
            DataCategory.BIBLIOGRAPHIC_METADATA to JsonUtil.toTree(mapOf("bibliographicQuery" to searchText)),
        )
        contactEmail?.let { payloadCategories[DataCategory.PROVIDER_CONTACT_EMAIL] = JsonUtil.toTree(it) }
        val payload = ProviderCallPayload(payloadCategories)
        return callGate.call(SCHOLARLY_METADATA_ROLE, "crossref", payload, configuration) { actualPayload ->
            val query = actualPayload.contentByCategory.getValue(DataCategory.BIBLIOGRAPHIC_METADATA)
                .path("bibliographicQuery").asText()
            cache.findBySearch(query)?.let { return@call it }
            val uriBuilder = UriComponentsBuilder.fromPath("/works")
                .queryParam("query.bibliographic", query)
                .queryParam("rows", MAX_RESULTS)
            actualPayload.contentByCategory[DataCategory.PROVIDER_CONTACT_EMAIL]
                ?.takeIf(JsonNode::isTextual)
                ?.asText()
                ?.let { uriBuilder.queryParam("mailto", it) }
            val response = exchange(uriBuilder.build().encode().toUri(), "crossref-bibliographic-search", "/works")
            val works = response?.path("message")?.path("items")?.mapNotNull(::toWork).orEmpty()
            cache.storeSearch(query, works)
            works
        }
    }

    private fun payload(vararg categories: Pair<DataCategory, JsonNode>): ProviderCallPayload {
        val content = categories.toMap().toMutableMap()
        contactEmail?.let { content[DataCategory.PROVIDER_CONTACT_EMAIL] = JsonUtil.toTree(it) }
        return ProviderCallPayload(content)
    }

    private fun exchange(uri: URI, operationKey: String, route: String): JsonNode? {
        val request = {
            executionService?.captureCurrentCrossrefRequest(uri)
            client.get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .exchange { _, response ->
                    when {
                        response.statusCode == HttpStatus.NOT_FOUND -> null
                        !response.statusCode.is2xxSuccessful -> throw IllegalStateException("Crossref returned HTTP ${response.statusCode.value()}.")
                        else -> readCrossrefResponse(response.body)
                    }
                }
        }
        return if (executionService == null) {
            request()
        } else {
            executionService.recordCurrentProviderCall(
                operationKey = operationKey,
                name = "Crossref scholarly metadata request",
                providerId = "crossref",
                modelId = null,
                attributes = mapOf("httpRoute" to route),
                operation = request,
            )
        }
    }

    private fun readCrossrefResponse(body: InputStream): JsonNode {
        val capturedPrefix = body.readNBytes(ExecutionCaptureSanitizer.DEFAULT_MAX_ARTIFACT_BYTES + 1)
        if (capturedPrefix.size > ExecutionCaptureSanitizer.DEFAULT_MAX_ARTIFACT_BYTES) {
            executionService?.omitCurrentBody("RESPONSE", "crossref-response-v1", "ARTIFACT_TOO_LARGE")
            return JsonUtil.parseTree(SequenceInputStream(ByteArrayInputStream(capturedPrefix), body))
        }
        executionService?.captureCurrentCrossrefResponse(capturedPrefix)
        return JsonUtil.parseTree(capturedPrefix)
    }

    private fun normalizeField(value: String): String? = value.trim().replace(WHITESPACE, " ").takeIf(String::isNotEmpty)

    private fun toWork(node: JsonNode): ScholarlyWork? {
        val doi = DoiNormalizer.normalize(node.path("DOI").takeIf(JsonNode::isTextual)?.asText()) ?: return null
        val title = node.path("title").firstOrNull()?.asText()?.let(::normalizeField) ?: return null
        val authors = node.path("author").mapNotNull { author ->
            author.path("name").takeIf(JsonNode::isTextual)?.asText()?.let(::normalizeField)
                ?: listOfNotNull(
                    author.path("given").takeIf(JsonNode::isTextual)?.asText()?.let(::normalizeField),
                    author.path("family").takeIf(JsonNode::isTextual)?.asText()?.let(::normalizeField),
                ).joinToString(" ").takeIf(String::isNotBlank)
        }
        val year = sequenceOf("published-print", "published-online", "issued")
            .mapNotNull { field -> node.path(field).path("date-parts").firstOrNull()?.firstOrNull()?.takeIf(JsonNode::isIntegralNumber)?.asInt() }
            .firstOrNull()
        return ScholarlyWork(doi, title, authors, year)
    }

    companion object {
        const val MAX_RESULTS = 10
        private val WHITESPACE = Regex("\\s+")
    }
}
