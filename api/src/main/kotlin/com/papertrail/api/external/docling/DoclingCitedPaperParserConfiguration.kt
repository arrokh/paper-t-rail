package com.papertrail.api.external.docling

import com.papertrail.api.external.docling.DoclingCitedPaperPdfParser
import com.papertrail.api.evidence.parsing.CitedPaperPdfParser
import org.springframework.beans.factory.annotation.Qualifier
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
class DoclingCitedPaperParserConfiguration {
    @Bean
    fun doclingRestClient(
        @Value("\${paper-trail.analysis.docling-base-url}") baseUrl: String,
        @Value("\${paper-trail.analysis.docling-request-timeout-millis}") requestTimeoutMillis: Long,
    ): RestClient {
        requireTrustedBaseUrl(baseUrl)
        require(requestTimeoutMillis > 0) { "The Docling request timeout must be positive." }
        val httpClient = HttpClient.newBuilder()
            // Docling's Uvicorn endpoint rejects the JDK client's cleartext h2c multipart upgrade.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(Duration.ofMillis(requestTimeoutMillis))
        }
        return RestClient.builder()
            .baseUrl(baseUrl.trimEnd('/'))
            .requestFactory(requestFactory)
            .build()
    }

    @Bean
    fun doclingCitedPaperPdfParser(
        @Qualifier("doclingRestClient") client: RestClient,
        @Value("\${paper-trail.analysis.cited-paper-parser-version}") parserVersion: String,
        @Value("\${paper-trail.analysis.docling-response-max-bytes}") maximumResponseBytes: Int,
        @Value("\${paper-trail.analysis.cited-paper-max-extracted-characters}") maximumCharacters: Int,
    ): CitedPaperPdfParser = DoclingCitedPaperPdfParser(
        client = client,
        parserVersion = parserVersion,
        maximumResponseBytes = maximumResponseBytes,
        maximumCharacters = maximumCharacters,
    )

    private fun requireTrustedBaseUrl(baseUrl: String) {
        val uri = runCatching { URI(baseUrl) }.getOrElse {
            throw IllegalArgumentException("The Docling base URL must be a valid trusted service URL.")
        }
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "The Docling base URL must be an HTTP(S) service URL without credentials, query, or fragment."
        }
        val hostname = uri.host.lowercase()
        val trustedHostnames = setOf("localhost", "docling", "host.docker.internal")
        val resolvedAddresses = if (hostname in trustedHostnames) {
            emptyArray()
        } else {
            runCatching { InetAddress.getAllByName(hostname) }.getOrDefault(emptyArray())
        }
        val trusted = hostname in trustedHostnames ||
            (resolvedAddresses.isNotEmpty() && resolvedAddresses.all { address ->
                address.isLoopbackAddress || address.isSiteLocalAddress
            })
        require(trusted) { "Docling must be self-hosted on localhost or a trusted private network." }
    }
}
