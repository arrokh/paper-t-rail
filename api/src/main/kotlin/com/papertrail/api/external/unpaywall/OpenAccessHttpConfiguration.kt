package com.papertrail.api.external.unpaywall

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import org.springframework.http.client.JdkClientHttpRequestFactory
import java.net.http.HttpClient
import java.time.Duration

@Configuration
class OpenAccessHttpConfiguration {
    @Bean("unpaywallRestClient")
    fun unpaywallRestClient(): RestClient = RestClient.builder()
        .baseUrl("https://api.unpaywall.org")
        .requestFactory(requestFactory())
        .build()

    @Bean("openAccessContentRestClient")
    fun openAccessContentRestClient(): RestClient = RestClient.builder()
        .requestFactory(requestFactory())
        .build()

    private fun requestFactory(): JdkClientHttpRequestFactory = JdkClientHttpRequestFactory(
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
    ).apply { setReadTimeout(Duration.ofSeconds(30)) }
}
