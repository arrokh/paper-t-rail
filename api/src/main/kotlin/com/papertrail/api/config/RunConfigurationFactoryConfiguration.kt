package com.papertrail.api.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.document.validation.PdfDocumentValidator
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.evidence.embedding.OllamaEmbeddingSettings
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RunConfigurationFactoryConfiguration {
    @Bean
    fun ollamaEmbeddingSettings(
        @Value("\${paper-trail.providers.ollama.enabled:false}") enabled: Boolean,
        @Value("\${paper-trail.providers.ollama.base-url:}") baseUrl: String,
        @Value("\${paper-trail.providers.ollama.model:}") model: String,
        @Value("\${paper-trail.providers.ollama.dimension:768}") dimension: Int,
        @Value("\${paper-trail.providers.ollama.api-key:}") apiKey: String,
        @Value("\${paper-trail.providers.ollama.trusted-hosts:localhost,127.0.0.1}") trustedHosts: String,
        @Value("\${paper-trail.providers.ollama.request-timeout-millis:60000}") requestTimeoutMillis: Long,
        @Value("\${paper-trail.providers.ollama.external-enablement-reviewed:false}") enablementReviewed: Boolean,
        @Value("\${paper-trail.providers.ollama.external-retention-disclosure:}") retentionDisclosure: String,
    ): OllamaEmbeddingSettings = OllamaEmbeddingSettings(
        enabled = enabled,
        baseUrl = baseUrl,
        modelId = model,
        dimension = dimension,
        apiKey = apiKey.trim().takeIf(String::isNotEmpty),
        trustedHosts = trustedHosts.split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
        requestTimeoutMillis = requestTimeoutMillis,
        enablementReviewed = enablementReviewed,
        retentionDisclosure = retentionDisclosure.takeIf(String::isNotBlank),
    )

    @Bean
    fun providerCatalog(
        ollamaEmbeddingSettings: OllamaEmbeddingSettings,
        @Value("\${paper-trail.providers.crossref.enabled:false}") crossrefEnabled: Boolean,
        @Value("\${paper-trail.providers.crossref.enablement-reviewed:false}") crossrefEnablementReviewed: Boolean,
        @Value("\${paper-trail.providers.crossref.retention-disclosure:}") crossrefRetentionDisclosure: String,
        @Value("\${paper-trail.providers.crossref.contact-email:}") crossrefContactEmail: String,
        @Value("\${paper-trail.providers.unpaywall.enabled:false}") unpaywallEnabled: Boolean,
        @Value("\${paper-trail.providers.unpaywall.enablement-reviewed:false}") unpaywallEnablementReviewed: Boolean,
        @Value("\${paper-trail.providers.unpaywall.retention-disclosure:}") unpaywallRetentionDisclosure: String,
        @Value("\${paper-trail.providers.unpaywall.contact-email:}") unpaywallContactEmail: String,
    ): ProviderCatalog = ProviderCatalog.safeDefaults(
        crossrefEnabled = crossrefEnabled,
        crossrefEnablementReviewed = crossrefEnablementReviewed,
        crossrefRetentionDisclosure = crossrefRetentionDisclosure.takeIf(String::isNotBlank),
        crossrefContactEmail = crossrefContactEmail.takeIf(String::isNotBlank),
        unpaywallEnabled = unpaywallEnabled,
        unpaywallEnablementReviewed = unpaywallEnablementReviewed,
        unpaywallRetentionDisclosure = unpaywallRetentionDisclosure.takeIf(String::isNotBlank),
        unpaywallContactEmail = unpaywallContactEmail.takeIf(String::isNotBlank),
        ollamaEmbeddingSettings = ollamaEmbeddingSettings,
    )

    @Bean
    fun providerCallGate(providerCatalog: ProviderCatalog): ProviderCallGate = ProviderCallGate(providerCatalog)

    @Bean
    fun runConfigurationFactory(
        objectMapper: ObjectMapper,
        providerCatalog: ProviderCatalog,
        validator: PdfDocumentValidator,
        @Value("\${paper-trail.analysis.parser-id}") parserId: String,
        @Value("\${paper-trail.analysis.parser-version}") parserVersion: String,
        @Value("\${paper-trail.validation.language-detector-version}") languageDetectorVersion: String,
        @Value("\${paper-trail.analysis.reference-resolution-confidence-threshold}") referenceResolutionConfidenceThreshold: Double,
        @Value("\${paper-trail.analysis.retrieval.profile-id}") retrievalProfileId: String,
        @Value("\${paper-trail.analysis.retrieval.vector-candidates}") vectorCandidateLimit: Int,
        @Value("\${paper-trail.analysis.retrieval.lexical-candidates}") lexicalCandidateLimit: Int,
        @Value("\${paper-trail.analysis.retrieval.final-candidates}") finalCandidateLimit: Int,
        @Value("\${paper-trail.analysis.retrieval.rrf-constant}") reciprocalRankFusionConstant: Int,
    ): RunConfigurationFactory = RunConfigurationFactory(
        objectMapper = objectMapper,
        providerCatalog = providerCatalog,
        parserId = parserId,
        parserVersion = parserVersion,
        languageDetectorVersion = languageDetectorVersion,
        limits = ValidationLimitsSnapshot(
            maxUploadBytes = validator.limits.maxBytes,
            maxPages = validator.limits.maxPages,
            maxExtractedCharacters = validator.limits.maxExtractedCharacters,
            maxExtractedCharactersPerPage = validator.limits.maxExtractedCharactersPerPage,
            minimumExtractedCharacters = validator.limits.minimumExtractedCharacters,
            minimumLanguageConfidence = validator.limits.minimumLanguageConfidence,
        ),
        referenceResolutionConfidenceThreshold = referenceResolutionConfidenceThreshold,
        retrievalProfileId = retrievalProfileId,
        vectorCandidateLimit = vectorCandidateLimit,
        lexicalCandidateLimit = lexicalCandidateLimit,
        finalCandidateLimit = finalCandidateLimit,
        reciprocalRankFusionConstant = reciprocalRankFusionConstant,
    )
}
