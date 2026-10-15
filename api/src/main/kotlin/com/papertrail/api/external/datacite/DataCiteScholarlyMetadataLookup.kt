package com.papertrail.api.external.datacite

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ProviderChainSnapshot
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptOutcomeCode
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallPayload
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.ProviderHttpFailurePolicy
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.client.ProviderChainMetadataLookup
import com.papertrail.api.scholarly.references.client.ProviderChainMetadataResponse
import com.papertrail.api.scholarly.references.client.ScholarlyWork
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import com.papertrail.api.utils.JsonUtil
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.util.UriComponentsBuilder
import java.io.IOException
import java.io.InputStream
import java.time.Clock

class DataCiteScholarlyMetadataLookup(
    private val client: RestClient,
    private val callGate: ProviderCallGate,
    private val configuration: AnalysisConfigurationSnapshot,
    private val clock: Clock = Clock.systemUTC(),
) : ScholarlyMetadataLookup, ProviderChainMetadataLookup {
    override fun byDoi(doi: String): ScholarlyWork? = DoiNormalizer.normalize(doi)?.let { byDoiForChain(it) {}.value }

    override fun byDoiForChain(
        doi: String,
        beforeWire: () -> Unit,
    ): ProviderChainMetadataResponse<ScholarlyWork?> {
        val normalizedDoi = DoiNormalizer.normalize(doi)
            ?: throw IllegalArgumentException("A chain DOI lookup requires a valid DOI.")
        val payload = ProviderCallPayload(
            mapOf(
                DataCategory.BIBLIOGRAPHIC_METADATA to JsonUtil.toTree(mapOf("doi" to normalizedDoi)),
            ),
        )
        return requireNotNull(callGate.callProviderChain(ProviderChainSnapshot.CHAIN_ID, PROVIDER_ID, payload, configuration) {
            beforeWire()
            try {
                fetch(normalizedDoi)
            } catch (exception: DataCiteProviderException) {
                throw exception
            } catch (_: Exception) {
                throw DataCiteProviderException()
            }
        })
    }

    override fun search(reference: BibliographyReference): List<ScholarlyWork> = emptyList()

    override fun searchByTitleForChain(
        title: String,
        candidateLimit: Int,
        beforeWire: () -> Unit,
    ): ProviderChainMetadataResponse<List<ScholarlyWork>> =
        throw UnsupportedOperationException("DataCite does not support title search.")

    private fun fetch(requestedDoi: String): ProviderChainMetadataResponse<ScholarlyWork?> {
        val uri = UriComponentsBuilder.fromPath("/dois/{doi}")
            .encode()
            .buildAndExpand(requestedDoi)
            .toUri()
        return try {
            requireNotNull(client.get()
                .uri(uri)
                .accept(DATA_CITE_JSON_API_MEDIA_TYPE)
                .header(HttpHeaders.ACCEPT_ENCODING, IDENTITY_ENCODING)
                .exchange { _, response ->
                    when {
                        response.statusCode == HttpStatus.NOT_FOUND -> ProviderChainMetadataResponse(
                            value = null,
                            httpStatus = response.statusCode.value(),
                            responseBytes = responseContentLength(response.headers[HttpHeaders.CONTENT_LENGTH]),
                        )
                        !response.statusCode.is2xxSuccessful -> throw failureForStatus(
                            response.statusCode.value(),
                            response.headers[HttpHeaders.RETRY_AFTER],
                            responseContentLength(response.headers[HttpHeaders.CONTENT_LENGTH]),
                        )
                        else -> {
                            var observedResponseBytes: Long? = null
                            try {
                                requireSupportedEncoding(response.headers[HttpHeaders.CONTENT_ENCODING])
                                val body = readResponse(response.body)
                                observedResponseBytes = body.responseBytes
                                val work = toWork(body.json)
                                ProviderChainMetadataResponse(work, response.statusCode.value(), body.responseBytes)
                            } catch (failure: ResourceAccessException) {
                                throw DataCiteProviderException(
                                    outcomeCode = ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE,
                                    httpStatus = response.statusCode.value(),
                                    responseBytes = observedResponseBytes,
                                )
                            } catch (_: IOException) {
                                throw DataCiteProviderException(
                                    outcomeCode = ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE,
                                    httpStatus = response.statusCode.value(),
                                    responseBytes = observedResponseBytes,
                                )
                            } catch (failure: DataCiteProviderException) {
                                throw DataCiteProviderException(
                                    outcomeCode = failure.outcomeCode,
                                    httpStatus = failure.httpStatus ?: response.statusCode.value(),
                                    retryAfterMillis = failure.retryAfterMillis,
                                    responseBytes = failure.responseBytes ?: observedResponseBytes,
                                )
                            } catch (_: Exception) {
                                throw DataCiteProviderException(
                                    httpStatus = response.statusCode.value(),
                                    responseBytes = observedResponseBytes,
                                )
                            }
                        }
                    }
                })
        } catch (_: ResourceAccessException) {
            throw DataCiteProviderException(ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE)
        }
    }

    private fun failureForStatus(
        status: Int,
        retryAfterValues: List<String>?,
        responseBytes: Long?,
    ): DataCiteProviderException {
        val failure = ProviderHttpFailurePolicy.classify(status, retryAfterValues, clock.instant())
        return DataCiteProviderException(
            outcomeCode = failure.outcomeCode,
            httpStatus = status,
            retryAfterMillis = failure.retryAfterMillis,
            responseBytes = responseBytes,
        )
    }

    private fun requireSupportedEncoding(contentEncodings: List<String>?) {
        if (contentEncodings.isNullOrEmpty()) return
        if (contentEncodings.size != 1 || !contentEncodings.single().trim().equals(IDENTITY_ENCODING, ignoreCase = true)) {
            throw DataCiteProviderException()
        }
    }

    private fun readResponse(body: InputStream): ParsedDataCiteResponse {
        val bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1)
        if (bytes.size > MAX_RESPONSE_BYTES) {
            throw DataCiteProviderException(responseBytes = bytes.size.toLong())
        }
        val parsed = try {
            JsonUtil.parseStrictTree(bytes) ?: throw DataCiteProviderException(responseBytes = bytes.size.toLong())
        } catch (_: DataCiteProviderException) {
            throw DataCiteProviderException(responseBytes = bytes.size.toLong())
        } catch (_: Exception) {
            throw DataCiteProviderException(responseBytes = bytes.size.toLong())
        }
        return ParsedDataCiteResponse(parsed, bytes.size.toLong())
    }

    private fun toWork(response: JsonNode): ScholarlyWork? {
        val data = response.path("data")
        val attributes = data.path("attributes")
        if (!data.isObject || !attributes.isObject) throw DataCiteProviderException()
        val responseDoi = attributes.path("doi").takeIf(JsonNode::isTextual)?.asText()
            ?.let(DoiNormalizer::normalize)

        val title = attributes.path("titles").takeIf(JsonNode::isArray)
            ?.firstOrNull()
            ?.path("title")
            ?.takeIf(JsonNode::isTextual)
            ?.asText()
            ?.let(::normalizeField)
            ?: throw DataCiteProviderException()
        val creatorsNode = attributes.path("creators")
        if (!creatorsNode.isMissingNode && !creatorsNode.isNull && !creatorsNode.isArray) {
            throw DataCiteProviderException()
        }
        val authors = if (creatorsNode.isArray) creatorsNode.mapNotNull(::creatorName) else emptyList()
        val year = attributes.path("publicationYear")
            .takeIf { it.isIntegralNumber && it.canConvertToInt() }
            ?.asInt()
        return ScholarlyWork(responseDoi, title, authors, year)
    }

    private fun creatorName(creator: JsonNode): String? {
        if (!creator.isObject) throw DataCiteProviderException()
        val name = creator.path("name").takeIf(JsonNode::isTextual)?.asText()?.let(::normalizeField)
        if (name != null) return name
        return listOfNotNull(
            creator.path("givenName").takeIf(JsonNode::isTextual)?.asText()?.let(::normalizeField),
            creator.path("familyName").takeIf(JsonNode::isTextual)?.asText()?.let(::normalizeField),
        ).joinToString(" ").takeIf(String::isNotBlank)
    }

    private fun normalizeField(value: String): String? = value.trim().replace(WHITESPACE, " ").takeIf(String::isNotEmpty)

    private fun responseContentLength(values: List<String>?): Long? = values
        ?.firstOrNull()
        ?.toLongOrNull()
        ?.takeIf { it >= 0 }

    private data class ParsedDataCiteResponse(val json: JsonNode, val responseBytes: Long)

    companion object {
        const val PROVIDER_ID = "datacite"
        const val MAX_RESPONSE_BYTES = 1_048_576
        private const val IDENTITY_ENCODING = "identity"
        private val DATA_CITE_JSON_API_MEDIA_TYPE = MediaType.parseMediaType("application/vnd.api+json")
        private val WHITESPACE = Regex("\\s+")
    }
}
