package com.papertrail.api.external.unpaywall

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.execution.service.AnalysisRunExecutionService
import com.papertrail.api.analysis.execution.ExecutionSpanArtifactSpec
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.OPEN_ACCESS_ROLE
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallPayload
import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText
import com.papertrail.api.scholarly.acquisition.domain.LegalOpenAccessLocationPolicy
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessDiscovery
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessLocation
import com.papertrail.api.scholarly.acquisition.domain.PublicInternetAddressPolicy
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.InetAddress
import java.net.URI
import java.time.Instant

import com.papertrail.api.scholarly.acquisition.client.OpenAccessProvider
import com.papertrail.api.scholarly.acquisition.client.OpenAccessProviderFactory

@Component
class UnpaywallOpenAccessProviderFactory(
    private val objectMapper: ObjectMapper,
    private val providerCallGate: ProviderCallGate,
    private val discoveryCache: UnpaywallDiscoveryCache,
    @Qualifier("unpaywallRestClient") private val unpaywallClient: RestClient,
    @Qualifier("openAccessContentRestClient") private val contentClient: RestClient,
    @Value("\${paper-trail.providers.unpaywall.contact-email:}") private val contactEmail: String,
    @Value("\${paper-trail.analysis.cited-paper-max-bytes:52428800}") private val maximumBytes: Int,
    private val executionService: AnalysisRunExecutionService? = null,
) : OpenAccessProviderFactory {
    override val providerId: String = UNPAYWALL_PROVIDER
    private val locationPolicy = LegalOpenAccessLocationPolicy()

    init {
        require(maximumBytes in 1..MAX_CONFIGURED_DOWNLOAD_BYTES) {
            "Cited-paper download limit must be between 1 byte and 100 MiB."
        }
    }

    override fun forRun(configuration: AnalysisConfigurationSnapshot): OpenAccessProvider = object : OpenAccessProvider {
        override fun discover(reference: BibliographyReference): OpenAccessDiscovery? {
            val doi = DoiNormalizer.normalize(reference.doi) ?: return null
            val email = contactEmail.takeIf(String::isNotBlank)
            val actualPayload = mutableMapOf<DataCategory, JsonNode>(
                DataCategory.BIBLIOGRAPHIC_METADATA to objectMapper.valueToTree(mapOf("doi" to doi)),
            )
            if (email != null) actualPayload[DataCategory.PROVIDER_CONTACT_EMAIL] = objectMapper.valueToTree(email)

            return providerCallGate.call(
                OPEN_ACCESS_ROLE,
                providerId,
                ProviderCallPayload(actualPayload),
                configuration,
            ) { authorizedPayload ->
                val authorizedDoi = authorizedPayload.contentByCategory[DataCategory.BIBLIOGRAPHIC_METADATA]
                    ?.path("doi")?.takeIf(JsonNode::isTextual)?.asText()
                    ?: throw IllegalStateException("Authorized Unpaywall request omitted its DOI.")
                val authorizedEmail = authorizedPayload.contentByCategory[DataCategory.PROVIDER_CONTACT_EMAIL]
                    ?.takeIf(JsonNode::isTextual)?.asText()
                discoveryCache.findByDoi(authorizedDoi)?.let { return@call it.toDiscovery() }
                val networkRequest = {
                    unpaywallClient.get()
                        .uri { builder ->
                            builder.pathSegment("v2", authorizedDoi)
                            if (!authorizedEmail.isNullOrBlank()) builder.queryParam("email", authorizedEmail)
                            builder.build().also { executionService?.captureCurrentUnpaywallRequest(it) }
                        }
                        .accept(MediaType.APPLICATION_JSON)
                        .exchange { _, discoveryResponse ->
                            val bytes = discoveryResponse.body.readNBytes(MAX_DISCOVERY_RESPONSE_BYTES + 1)
                            if (bytes.size <= MAX_DISCOVERY_RESPONSE_BYTES) {
                                executionService?.captureCurrentUnpaywallResponse(bytes)
                            } else {
                                executionService?.omitCurrentBody("RESPONSE", "unpaywall-response-v1", "ARTIFACT_TOO_LARGE")
                            }
                            if (discoveryResponse.statusCode.value() == 404) return@exchange null
                            require(discoveryResponse.statusCode.is2xxSuccessful) {
                                "Unpaywall returned HTTP ${discoveryResponse.statusCode.value()}."
                            }
                            require(discoveryResponse.headers.contentType?.isCompatibleWith(MediaType.APPLICATION_JSON) == true) {
                                "Unpaywall discovery response must be JSON."
                            }
                            require(bytes.isNotEmpty() && bytes.size <= MAX_DISCOVERY_RESPONSE_BYTES) {
                                "Unpaywall discovery response is empty or exceeds the configured limit."
                            }
                            objectMapper.readTree(bytes)
                        }
                }
                val response = if (executionService == null) {
                    networkRequest()
                } else {
                    executionService.recordCurrentProviderCall(
                        operationKey = "unpaywall-discovery",
                        name = "Unpaywall discovery request",
                        providerId = providerId,
                        modelId = null,
                        attributes = mapOf("httpRoute" to "/v2"),
                        operation = networkRequest,
                    )
                } ?: run {
                        discoveryCache.storeByDoi(authorizedDoi, null, Instant.now())
                        return@call null
                    }
                val foundDoi = response.path("doi").takeIf(JsonNode::isTextual)?.asText()
                if (DoiNormalizer.normalize(foundDoi) != doi) {
                    discoveryCache.storeByDoi(authorizedDoi, null, Instant.now())
                    return@call null
                }
                val fetchedAt = Instant.now()
                val locations = response.path("oa_locations").mapNotNull(::locationFrom)
                val discovery = OpenAccessDiscovery(
                    metadataAvailable = true,
                    abstractAvailable = response.path("abstract").takeIf(JsonNode::isTextual)?.asText()?.isNotBlank() == true,
                    locations = locations,
                    providerId = providerId,
                    discoveredAt = fetchedAt,
                )
                discoveryCache.storeByDoi(authorizedDoi, discovery, fetchedAt)
                discovery
            }
        }

        override fun fetch(location: OpenAccessLocation): AcquiredFullText {
            require(location.providerId == providerId) { "Open-access location belongs to a different discovery provider." }
            require(locationPolicy.isUsable(location)) { "Open-access location does not meet the legal acquisition policy." }
            val target = objectMapper.valueToTree<JsonNode>(mapOf("url" to location.url))
            return providerCallGate.call(
                OPEN_ACCESS_ROLE,
                providerId,
                ProviderCallPayload(mapOf(DataCategory.CITED_PAPER_LOCATION to target)),
                configuration,
            ) { authorizedPayload ->
                val authorizedUrl = authorizedPayload.contentByCategory[DataCategory.CITED_PAPER_LOCATION]
                    ?.path("url")?.takeIf(JsonNode::isTextual)?.asText()
                    ?: throw IllegalStateException("Authorized content-host request omitted its URL.")
                requirePublicContentHost(authorizedUrl)
                val networkRequest = {
                    executionService?.captureCurrentOpenAccessRequest(URI(authorizedUrl))
                    contentClient.get()
                        .uri(authorizedUrl)
                        .accept(MediaType.APPLICATION_PDF, MediaType.TEXT_PLAIN)
                        .exchange { _, response ->
                            val responseSchema = "open-access-content-metadata-v1"
                            val httpStatus = response.statusCode.value()
                            if (!response.statusCode.is2xxSuccessful) {
                                executionService?.omitCurrentBody("RESPONSE", responseSchema, "UNSUPPORTED_OR_UNSAFE_FIELDS")
                                executionService?.captureCurrent(
                                    ExecutionSpanArtifactSpec("RESULT", responseSchema, mapOf("httpStatus" to httpStatus)),
                                )
                                throw IllegalStateException("Open-access content host returned HTTP $httpStatus.")
                            }
                            val mediaType = response.headers.contentType
                                ?.let { "${it.type}/${it.subtype}" }
                            if (mediaType == null || mediaType !in ALLOWED_MEDIA_TYPES) {
                                executionService?.omitCurrentBody("RESPONSE", responseSchema, "UNSUPPORTED_OR_UNSAFE_FIELDS")
                                executionService?.captureCurrent(
                                    ExecutionSpanArtifactSpec("RESULT", responseSchema, mapOf("httpStatus" to httpStatus)),
                                )
                                throw IllegalStateException("Open-access content response must be a PDF or plain text file.")
                            }
                            val bytes = response.body.readNBytes(maximumBytes + 1)
                            if (bytes.isEmpty() || bytes.size > maximumBytes) {
                                executionService?.omitCurrentBody(
                                    "RESPONSE",
                                    responseSchema,
                                    if (bytes.isEmpty()) "UNSUPPORTED_OR_UNSAFE_FIELDS" else "ARTIFACT_TOO_LARGE",
                                )
                                executionService?.captureCurrent(
                                    ExecutionSpanArtifactSpec(
                                        "RESULT",
                                        responseSchema,
                                        mapOf("httpStatus" to httpStatus, "mediaType" to mediaType),
                                    ),
                                )
                                throw IllegalStateException("Open-access content is empty or exceeds the configured download limit.")
                            }
                            executionService?.omitCurrentBody("RESPONSE", responseSchema, "BINARY_ASSET_REFERENCE")
                            executionService?.captureCurrent(
                                ExecutionSpanArtifactSpec(
                                    "RESULT",
                                    responseSchema,
                                    mapOf("httpStatus" to httpStatus, "mediaType" to mediaType, "byteCount" to bytes.size),
                                ),
                            )
                            AcquiredFullText(bytes, mediaType, location)
                        }
                }
                val acquired = if (executionService == null) {
                    networkRequest()
                } else {
                    executionService.recordCurrentProviderCall(
                        operationKey = "unpaywall-full-text",
                        name = "Acquire open-access full text",
                        providerId = providerId,
                        modelId = null,
                        attributes = mapOf("httpRoute" to "/open-access-content"),
                        operation = networkRequest,
                    )
                }
                acquired ?: throw IllegalStateException("Open-access content host returned no response.")
            }
        }

        private fun requirePublicContentHost(url: String) {
            val uri = runCatching { URI(url) }.getOrElse {
                throw IllegalArgumentException("Open-access content URL is invalid.")
            }
            val host = uri.host?.removeSurrounding("[", "]")
                ?: throw IllegalArgumentException("Open-access content URL has no host.")
            val addresses = InetAddress.getAllByName(host)
            require(addresses.isNotEmpty() && addresses.all(PublicInternetAddressPolicy::isPublic)) {
                "Open-access content host must resolve only to public addresses."
            }
        }

        private fun locationFrom(node: JsonNode): OpenAccessLocation? {
            val url = node.text("url_for_pdf") ?: node.text("url") ?: return null
            return OpenAccessLocation(
                url = url,
                license = node.text("license"),
                version = node.text("version"),
                hostType = node.text("host_type"),
                providerId = providerId,
            )
        }

        private fun JsonNode.text(field: String): String? = path(field)
            .takeIf(JsonNode::isTextual)
            ?.asText()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    companion object {
        const val UNPAYWALL_PROVIDER = "unpaywall"
        private const val MAX_DISCOVERY_RESPONSE_BYTES = 1_048_576
        private const val MAX_CONFIGURED_DOWNLOAD_BYTES = 100 * 1_048_576
        private val ALLOWED_MEDIA_TYPES = setOf("application/pdf", "text/plain")
    }
}
