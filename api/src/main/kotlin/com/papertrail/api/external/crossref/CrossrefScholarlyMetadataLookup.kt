package com.papertrail.api.external.crossref

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ProviderChainSnapshot
import com.papertrail.api.analysis.execution.service.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.service.ExecutionCaptureSanitizer
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallPayload
import com.papertrail.api.infrastructure.providers.SCHOLARLY_METADATA_ROLE
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptOutcomeCode
import com.papertrail.api.scholarly.references.client.ProviderHttpFailurePolicy
import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.net.URI
import java.time.Clock

import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.client.ProviderChainMetadataLookup
import com.papertrail.api.scholarly.references.client.ProviderChainMetadataResponse
import com.papertrail.api.scholarly.references.client.ScholarlyWork

class CrossrefScholarlyMetadataLookup(
    private val client: RestClient,
    private val callGate: ProviderCallGate,
    private val configuration: AnalysisConfigurationSnapshot,
    private val contactEmail: String?,
    private val cache: CrossrefLookupCache,
    private val executionService: AnalysisRunExecutionService? = null,
    private val clock: Clock = Clock.systemUTC(),
) : ScholarlyMetadataLookup, ProviderChainMetadataLookup {
    override fun byDoi(doi: String): ScholarlyWork? {
        val payload = payload(
            DataCategory.BIBLIOGRAPHIC_METADATA to JsonUtil.toTree(mapOf("doi" to doi)),
        )
        if (configuration.referenceResolution.providerChain != null) {
            val normalized = DoiNormalizer.normalize(doi) ?: return null
            return byDoiForChain(normalized) {}.value
        }
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
            val works = response.body?.path("message")?.let(::toWork)?.let(::listOf).orEmpty()
            cache.storeByDoi(requestDoi, works)
            works.firstOrNull()
        }
    }

    override fun search(reference: BibliographyReference): List<ScholarlyWork> {
        val chainMode = configuration.referenceResolution.providerChain != null
        val searchText = if (chainMode) {
            reference.title?.trim().orEmpty()
        } else {
            listOfNotNull(
                reference.title?.takeIf(String::isNotBlank),
                reference.authors.takeIf(List<String>::isNotEmpty)?.joinToString(" "),
                reference.year?.toString(),
            ).joinToString(" ").trim()
        }
        if (searchText.isBlank()) return emptyList()
        val payloadCategories = mutableMapOf<DataCategory, JsonNode>(
            DataCategory.BIBLIOGRAPHIC_METADATA to JsonUtil.toTree(mapOf("bibliographicQuery" to searchText)),
        )
        contactEmail?.let { payloadCategories[DataCategory.PROVIDER_CONTACT_EMAIL] = JsonUtil.toTree(it) }
        val payload = ProviderCallPayload(payloadCategories)
        if (chainMode) {
            return searchByTitleForChain(
                title = searchText,
                candidateLimit = requireNotNull(configuration.referenceResolution.providerChain).limits.crossrefCandidateLimit,
                beforeWire = {},
            ).value
        }
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
            val works = response.body?.path("message")?.path("items")?.mapNotNull(::toWork).orEmpty()
            cache.storeSearch(query, works)
            works
        }
    }

    override fun byDoiForChain(
        doi: String,
        beforeWire: () -> Unit,
    ): ProviderChainMetadataResponse<ScholarlyWork?> {
        val normalizedDoi = DoiNormalizer.normalize(doi)
            ?: throw IllegalArgumentException("A chain DOI lookup requires a valid DOI.")
        val payload = payload(
            DataCategory.BIBLIOGRAPHIC_METADATA to JsonUtil.toTree(mapOf("doi" to normalizedDoi)),
        )
        return callGate.callProviderChain(
            ProviderChainSnapshot.CHAIN_ID,
            ProviderChainSnapshot.CROSSREF_PROVIDER_ID,
            payload,
            configuration,
        ) { actualPayload ->
            beforeWire()
            val requestData = actualPayload.contentByCategory.getValue(DataCategory.BIBLIOGRAPHIC_METADATA)
            val requestDoi = requestData.path("doi").asText().let { DoiNormalizer.normalize(it) ?: it }
            val uriBuilder = UriComponentsBuilder.fromPath("/works/{doi}")
            actualPayload.contentByCategory[DataCategory.PROVIDER_CONTACT_EMAIL]
                ?.takeIf(JsonNode::isTextual)
                ?.asText()
                ?.let { uriBuilder.queryParam("mailto", it) }
            val uri = uriBuilder.buildAndExpand(requestDoi).encode().toUri()
            val response = exchange(uri, "crossref-doi-lookup", "/works")
            val work = try {
                parseChainDoiResponse(response.body)
            } catch (failure: CrossrefChainProviderException) {
                throw CrossrefChainProviderException(
                    outcomeCode = failure.outcomeCode,
                    httpStatus = response.httpStatus,
                    retryAfterMillis = failure.retryAfterMillis,
                    responseBytes = response.responseBytes,
                )
            }
            ProviderChainMetadataResponse(work, response.httpStatus, response.responseBytes)
        }
    }

    override fun searchByTitleForChain(
        title: String,
        candidateLimit: Int,
        beforeWire: () -> Unit,
    ): ProviderChainMetadataResponse<List<ScholarlyWork>> {
        val chain = configuration.referenceResolution.providerChain
            ?: throw IllegalStateException("A Crossref chain search requires a pinned provider chain.")
        require(candidateLimit == chain.limits.crossrefCandidateLimit) {
            "Crossref candidate limit must match the pinned provider-chain budget."
        }
        val query = title.trim().replace(WHITESPACE, " ")
        require(query.isNotBlank()) { "A chain title lookup must not be blank." }
        val payload = ProviderCallPayload(
            buildMap {
                put(DataCategory.BIBLIOGRAPHIC_METADATA, JsonUtil.toTree(mapOf("bibliographicQuery" to query)))
                contactEmail?.let { put(DataCategory.PROVIDER_CONTACT_EMAIL, JsonUtil.toTree(it)) }
            },
        )
        return callGate.callProviderChain(
            ProviderChainSnapshot.CHAIN_ID,
            ProviderChainSnapshot.CROSSREF_PROVIDER_ID,
            payload,
            configuration,
        ) { actualPayload ->
            beforeWire()
            val actualQuery = actualPayload.contentByCategory.getValue(DataCategory.BIBLIOGRAPHIC_METADATA)
                .path("bibliographicQuery").asText()
            val uriBuilder = UriComponentsBuilder.fromPath("/works")
                .queryParam("query.bibliographic", actualQuery)
                .queryParam("rows", candidateLimit)
            actualPayload.contentByCategory[DataCategory.PROVIDER_CONTACT_EMAIL]
                ?.takeIf(JsonNode::isTextual)
                ?.asText()
                ?.let { uriBuilder.queryParam("mailto", it) }
            val response = exchange(uriBuilder.build().encode().toUri(), "crossref-bibliographic-search", "/works")
            val works = try {
                parseChainSearchResponse(response.body)
            } catch (failure: CrossrefChainProviderException) {
                throw CrossrefChainProviderException(
                    outcomeCode = failure.outcomeCode,
                    httpStatus = response.httpStatus,
                    retryAfterMillis = failure.retryAfterMillis,
                    responseBytes = response.responseBytes,
                )
            }
            ProviderChainMetadataResponse(works, response.httpStatus, response.responseBytes)
        }
    }

    private fun parseChainDoiResponse(response: JsonNode?): ScholarlyWork? {
        if (response == null) return null
        val message = response.get("message")?.takeIf(JsonNode::isObject)
            ?: throw CrossrefChainProviderException()
        return toWork(message) ?: throw CrossrefChainProviderException()
    }

    private fun parseChainSearchResponse(response: JsonNode?): List<ScholarlyWork> {
        if (response == null) return emptyList()
        val message = response.get("message")?.takeIf(JsonNode::isObject)
            ?: throw CrossrefChainProviderException()
        val items = message.get("items")?.takeIf(JsonNode::isArray)
            ?: throw CrossrefChainProviderException()
        val works = items.mapNotNull(::toWork)
        if (items.size() > 0 && works.isEmpty()) throw CrossrefChainProviderException()
        return works
    }

    private fun payload(vararg categories: Pair<DataCategory, JsonNode>): ProviderCallPayload {
        val content = categories.toMap().toMutableMap()
        contactEmail?.let { content[DataCategory.PROVIDER_CONTACT_EMAIL] = JsonUtil.toTree(it) }
        return ProviderCallPayload(content)
    }

    private fun exchange(uri: URI, operationKey: String, route: String): CrossrefExchangeResponse {
        val maxDecodedJsonBytes = configuration.referenceResolution.providerChain?.limits?.maxDecodedJsonBytes
        val request = {
            try {
                if (maxDecodedJsonBytes == null) {
                    executionService?.captureCurrentCrossrefRequest(uri)
                } else {
                    executionService?.omitCurrentBody("REQUEST", "crossref-request-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
                }
                val requestSpec = client.get()
                    .uri(uri)
                    .accept(MediaType.APPLICATION_JSON)
                if (maxDecodedJsonBytes != null) {
                    requestSpec.header(HttpHeaders.ACCEPT_ENCODING, IDENTITY_ENCODING)
                }
                requestSpec.exchange { _, response ->
                    when {
                        response.statusCode == HttpStatus.NOT_FOUND -> CrossrefExchangeResponse(
                            body = null,
                            httpStatus = response.statusCode.value(),
                            responseBytes = responseContentLength(response.headers[HttpHeaders.CONTENT_LENGTH]),
                        )
                        !response.statusCode.is2xxSuccessful -> {
                            if (maxDecodedJsonBytes == null) {
                                throw IllegalStateException("Crossref returned HTTP ${response.statusCode.value()}.")
                            }
                            throw failureForStatus(
                                response.statusCode.value(),
                                response.headers[HttpHeaders.RETRY_AFTER],
                                responseContentLength(response.headers[HttpHeaders.CONTENT_LENGTH]),
                            )
                        }
                        maxDecodedJsonBytes == null -> CrossrefExchangeResponse(
                            body = readCrossrefResponse(response.body),
                            httpStatus = response.statusCode.value(),
                            responseBytes = responseContentLength(response.headers[HttpHeaders.CONTENT_LENGTH]),
                        )
                        else -> {
                            try {
                                requireSupportedEncoding(response.headers[HttpHeaders.CONTENT_ENCODING])
                                val parsed = readChainCrossrefResponse(response.body, maxDecodedJsonBytes)
                                CrossrefExchangeResponse(
                                    body = parsed.body,
                                    httpStatus = response.statusCode.value(),
                                    responseBytes = parsed.responseBytes,
                                )
                            } catch (failure: CrossrefChainProviderException) {
                                throw CrossrefChainProviderException(
                                    outcomeCode = failure.outcomeCode,
                                    httpStatus = failure.httpStatus ?: response.statusCode.value(),
                                    retryAfterMillis = failure.retryAfterMillis,
                                    responseBytes = failure.responseBytes,
                                )
                            } catch (_: ResourceAccessException) {
                                throw CrossrefChainProviderException(
                                    outcomeCode = ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE,
                                    httpStatus = response.statusCode.value(),
                                )
                            } catch (_: IOException) {
                                throw CrossrefChainProviderException(
                                    outcomeCode = ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE,
                                    httpStatus = response.statusCode.value(),
                                )
                            } catch (_: Exception) {
                                throw CrossrefChainProviderException(
                                    httpStatus = response.statusCode.value(),
                                )
                            }
                        }
                    }
                }
            } catch (exception: CrossrefChainProviderException) {
                throw exception
            } catch (exception: ResourceAccessException) {
                if (maxDecodedJsonBytes == null) throw exception
                throw CrossrefChainProviderException(ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE)
            }
        }
        return if (executionService == null) {
            requireNotNull(request())
        } else {
            requireNotNull(executionService.recordCurrentProviderCall(
                operationKey = operationKey,
                name = "Crossref scholarly metadata request",
                providerId = "crossref",
                modelId = null,
                attributes = mapOf("httpRoute" to route),
                operation = request,
            ))
        }
    }

    private fun failureForStatus(
        status: Int,
        retryAfterValues: List<String>?,
        responseBytes: Long?,
    ): CrossrefChainProviderException {
        val failure = ProviderHttpFailurePolicy.classify(status, retryAfterValues, clock.instant())
        return CrossrefChainProviderException(
            outcomeCode = failure.outcomeCode,
            httpStatus = status,
            retryAfterMillis = failure.retryAfterMillis,
            responseBytes = responseBytes,
        )
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

    private fun readChainCrossrefResponse(body: InputStream, maximumDecodedJsonBytes: Int): ParsedCrossrefResponse {
        val bytes = body.readNBytes(maximumDecodedJsonBytes + 1)
        if (bytes.size > maximumDecodedJsonBytes) {
            executionService?.omitCurrentBody("RESPONSE", "crossref-response-v1", "ARTIFACT_TOO_LARGE")
            throw CrossrefChainProviderException(responseBytes = bytes.size.toLong())
        }
        val parsed = try {
            JsonUtil.parseStrictTree(bytes)
        } catch (_: Exception) {
            throw CrossrefChainProviderException(responseBytes = bytes.size.toLong())
        } ?: throw CrossrefChainProviderException(responseBytes = bytes.size.toLong())
        if (bytes.size > ExecutionCaptureSanitizer.DEFAULT_MAX_ARTIFACT_BYTES) {
            executionService?.omitCurrentBody("RESPONSE", "crossref-response-v1", "ARTIFACT_TOO_LARGE")
        } else {
            executionService?.omitCurrentBody("RESPONSE", "crossref-response-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        }
        return ParsedCrossrefResponse(parsed, bytes.size.toLong())
    }

    private fun responseContentLength(values: List<String>?): Long? = values
        ?.firstOrNull()
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }

    private fun requireSupportedEncoding(contentEncodings: List<String>?) {
        if (contentEncodings.isNullOrEmpty()) return
        if (contentEncodings.size != 1 || !contentEncodings.single().trim().equals(IDENTITY_ENCODING, ignoreCase = true)) {
            throw CrossrefChainProviderException()
        }
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

    private data class CrossrefExchangeResponse(
        val body: JsonNode?,
        val httpStatus: Int,
        val responseBytes: Long?,
    )

    private data class ParsedCrossrefResponse(val body: JsonNode, val responseBytes: Long)

    companion object {
        const val MAX_RESULTS = 10
        private const val IDENTITY_ENCODING = "identity"
        private val WHITESPACE = Regex("\\s+")
    }
}
