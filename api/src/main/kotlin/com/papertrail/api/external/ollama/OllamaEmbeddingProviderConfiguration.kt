package com.papertrail.api.external.ollama

import com.papertrail.api.infrastructure.providers.ProviderCallGate
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OllamaEmbeddingProviderConfiguration {
    @Bean
    fun legacyNomicEmbeddingProvider(
        settings: OllamaEmbeddingSettings,
        providerCallGate: ProviderCallGate,
    ): OllamaEmbeddingProvider = OllamaEmbeddingProvider(
        settings.forLegacyNomicCompatibility(),
        providerCallGate,
    )
}
