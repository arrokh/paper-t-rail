package com.papertrail.api.external.crossref

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

@Configuration
class CrossrefClientConfiguration {
    @Bean
    fun crossrefRestClient(): RestClient {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
        val requestFactory = JdkClientHttpRequestFactory(client).apply { setReadTimeout(Duration.ofSeconds(20)) }
        return RestClient.builder().baseUrl("https://api.crossref.org").requestFactory(requestFactory).build()
    }
}
