package com.papertrail.api.config

import com.papertrail.api.parsing.GrobidScientificDocumentParser
import com.papertrail.api.parsing.GrobidTeiParser
import com.papertrail.api.parsing.ScientificDocumentParser
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.time.Duration

@Configuration
class ScientificDocumentParserConfiguration {
    @Bean
    fun grobidRestClient(
        @Value("\${paper-trail.analysis.grobid-base-url}") baseUrl: String,
    ): RestClient {
        requireTrustedBaseUrl(baseUrl)
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(Duration.ofMinutes(5))
        }
        return RestClient.builder()
            .baseUrl(baseUrl.trimEnd('/'))
            .requestFactory(requestFactory)
            .build()
    }

    private fun requireTrustedBaseUrl(baseUrl: String) {
        val uri = runCatching { URI(baseUrl) }.getOrElse {
            throw IllegalArgumentException("The GROBID base URL must be a valid trusted service URL.")
        }
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "The GROBID base URL must be an HTTP(S) service URL without credentials, query, or fragment."
        }
        val hostname = uri.host.lowercase()
        val trustedHostnames = setOf("localhost", "grobid", "host.docker.internal")
        val resolvedAddresses = if (hostname in trustedHostnames) {
            emptyArray()
        } else {
            runCatching { InetAddress.getAllByName(hostname) }.getOrDefault(emptyArray())
        }
        val trusted = hostname in trustedHostnames ||
            (resolvedAddresses.isNotEmpty() && resolvedAddresses.all { address ->
                address.isLoopbackAddress || address.isSiteLocalAddress
            })
        require(trusted) { "GROBID must be self-hosted on localhost or a trusted private network." }
    }

    @Bean
    fun scientificDocumentParser(
        grobidRestClient: RestClient,
        @Value("\${paper-trail.analysis.parser-id}") parserId: String,
        @Value("\${paper-trail.analysis.parser-version}") parserVersion: String,
        @Value("\${paper-trail.upload.max-extracted-characters}") maximumCharacters: Int,
        @Value("\${paper-trail.analysis.parser-response-max-bytes}") maximumResponseBytes: Int,
    ): ScientificDocumentParser = GrobidScientificDocumentParser(
        grobidRestClient,
        GrobidTeiParser(parserId, parserVersion, maximumCharacters),
        maximumResponseBytes,
    )
}
