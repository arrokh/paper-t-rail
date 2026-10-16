package com.papertrail.api.external.datacite

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

@Configuration(proxyBeanMethods = false)
class DataCiteClientConfiguration {
    @Bean
    fun dataCiteRestClient(): RestClient {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply {
            setReadTimeout(READ_TIMEOUT)
        }
        return RestClient.builder()
            .baseUrl(BASE_URL)
            .requestFactory(requestFactory)
            .build()
    }

    companion object {
        private const val BASE_URL = "https://api.datacite.org"
        private val CONNECT_TIMEOUT = Duration.ofSeconds(5)
        private val READ_TIMEOUT = Duration.ofSeconds(20)
    }
}
