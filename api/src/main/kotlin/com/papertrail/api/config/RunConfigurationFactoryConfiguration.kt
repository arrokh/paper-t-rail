package com.papertrail.api.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.document.validation.PdfDocumentValidator
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.evidence.embedding.OllamaEmbeddingSettings
import com.papertrail.api.evidence.verification.domain.EvidenceAggregationThresholds
import com.papertrail.api.evidence.verification.provider.JevSystemOneSettings
import com.papertrail.api.evidence.verification.provider.LayaSystemOneSettings
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RunConfigurationFactoryConfiguration {
    @Bean
    fun ollamaEmbeddingSettings(
        @Value("\${paper-trail.providers.ollama.enabled}") enabled: Boolean,
        @Value("\${paper-trail.providers.ollama.base-url}") baseUrl: String,
        @Value("\${paper-trail.providers.ollama.model}") model: String,
        @Value("\${paper-trail.providers.ollama.dimension}") dimension: Int,
        @Value("\${paper-trail.providers.ollama.api-key}") apiKey: String,
        @Value("\${paper-trail.providers.ollama.trusted-hosts}") trustedHosts: String,
        @Value("\${paper-trail.providers.ollama.request-timeout-millis}") requestTimeoutMillis: Long,
        @Value("\${paper-trail.providers.ollama.external-retention-disclosure}") retentionDisclosure: String,
    ): OllamaEmbeddingSettings = OllamaEmbeddingSettings(
        enabled = enabled,
        baseUrl = baseUrl,
        modelId = model,
        dimension = dimension,
        apiKey = apiKey.trim().takeIf(String::isNotEmpty),
        trustedHosts = trustedHosts.split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
        requestTimeoutMillis = requestTimeoutMillis,
        retentionDisclosure = retentionDisclosure.takeIf(String::isNotBlank),
    )

    @Bean
    fun layaSystemOneSettings(
        @Value("\${paper-trail.providers.laya.enabled}") enabled: Boolean,
        @Value("\${paper-trail.providers.laya.base-url}") baseUrl: String,
        @Value("\${paper-trail.providers.laya.api-key}") apiKey: String,
        @Value("\${paper-trail.providers.laya.trusted-hosts}") trustedHosts: String,
        @Value("\${paper-trail.providers.laya.request-timeout-millis}") requestTimeoutMillis: Long,
    ): LayaSystemOneSettings = LayaSystemOneSettings(
        enabled = enabled,
        baseUrl = baseUrl,
        apiKey = apiKey.trim().takeIf(String::isNotEmpty),
        trustedHosts = trustedHosts.split(',').map(String::trim).filter(String::isNotEmpty).toSet(),
        requestTimeoutMillis = requestTimeoutMillis,
    )

    @Bean
    fun jevSystemOneSettings(
        @Value("\${paper-trail.providers.jev.api-key}") apiKey: String,
        @Value("\${paper-trail.providers.jev.model}") modelId: String,
        @Value("\${paper-trail.providers.jev.base-url}") baseUrl: String,
        @Value("\${paper-trail.providers.jev.request-timeout-millis}") requestTimeoutMillis: Long,
        @Value("\${paper-trail.providers.jev.retention-disclosure}") retentionDisclosure: String,
    ): JevSystemOneSettings = JevSystemOneSettings(
        apiKey = apiKey.trim().takeIf(String::isNotEmpty),
        modelId = modelId,
        baseUrl = baseUrl,
        requestTimeoutMillis = requestTimeoutMillis,
        retentionDisclosure = retentionDisclosure.takeIf(String::isNotBlank),
    )

    @Bean
    fun providerCatalog(
        ollamaEmbeddingSettings: OllamaEmbeddingSettings,
        layaSystemOneSettings: LayaSystemOneSettings,
        jevSystemOneSettings: JevSystemOneSettings,
        @Value("\${paper-trail.providers.crossref.enabled}") crossrefEnabled: Boolean,
        @Value("\${paper-trail.providers.crossref.retention-disclosure}") crossrefRetentionDisclosure: String,
        @Value("\${paper-trail.providers.crossref.contact-email}") crossrefContactEmail: String,
        @Value("\${paper-trail.providers.unpaywall.enabled}") unpaywallEnabled: Boolean,
        @Value("\${paper-trail.providers.unpaywall.retention-disclosure}") unpaywallRetentionDisclosure: String,
        @Value("\${paper-trail.providers.unpaywall.contact-email}") unpaywallContactEmail: String,
    ): ProviderCatalog = ProviderCatalog.safeDefaults(
        crossrefEnabled = crossrefEnabled,
        crossrefRetentionDisclosure = crossrefRetentionDisclosure.takeIf(String::isNotBlank),
        crossrefContactEmail = crossrefContactEmail.takeIf(String::isNotBlank),
        unpaywallEnabled = unpaywallEnabled,
        unpaywallRetentionDisclosure = unpaywallRetentionDisclosure.takeIf(String::isNotBlank),
        unpaywallContactEmail = unpaywallContactEmail.takeIf(String::isNotBlank),
        ollamaEmbeddingSettings = ollamaEmbeddingSettings,
        layaSystemOneSettings = layaSystemOneSettings,
        jevSystemOneSettings = jevSystemOneSettings,
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
        @Value("\${paper-trail.analysis.cited-paper-parser-id}") citedPaperParserId: String,
        @Value("\${paper-trail.analysis.cited-paper-parser-version}") citedPaperParserVersion: String,
        @Value("\${paper-trail.validation.language-detector-version}") languageDetectorVersion: String,
        @Value("\${paper-trail.analysis.reference-resolution-confidence-threshold}") referenceResolutionConfidenceThreshold: Double,
        @Value("\${paper-trail.analysis.retrieval.profile-id}") retrievalProfileId: String,
        @Value("\${paper-trail.analysis.retrieval.vector-candidates}") vectorCandidateLimit: Int,
        @Value("\${paper-trail.analysis.retrieval.lexical-candidates}") lexicalCandidateLimit: Int,
        @Value("\${paper-trail.analysis.retrieval.final-candidates}") finalCandidateLimit: Int,
        @Value("\${paper-trail.analysis.retrieval.rrf-constant}") reciprocalRankFusionConstant: Int,
        @Value("\${paper-trail.upload.max-claim-citation-pairs}") maxClaimCitationPairs: Int,
        @Value("\${paper-trail.providers.system-one.default-provider}") defaultSystemOneProvider: String,
        @Value("\${paper-trail.analysis.system-one-aggregation.enabled}") systemOneAggregationEnabled: Boolean,
        @Value("\${paper-trail.analysis.system-one-aggregation.direct-support-threshold}") directSupportThreshold: Double,
        @Value("\${paper-trail.analysis.system-one-aggregation.partial-support-threshold}") partialSupportThreshold: Double,
        @Value("\${paper-trail.analysis.system-one-aggregation.contradiction-threshold}") contradictionThreshold: Double,
        @Value("\${paper-trail.analysis.system-one-aggregation.comparability-margin}") comparabilityMargin: Double,
    ): RunConfigurationFactory {
        val aggregationThresholds = EvidenceAggregationThresholds(
            directSupport = directSupportThreshold,
            partialSupport = partialSupportThreshold,
            contradiction = contradictionThreshold,
            comparabilityMargin = comparabilityMargin,
        )
        return RunConfigurationFactory(
            objectMapper = objectMapper,
            providerCatalog = providerCatalog,
            parserId = parserId,
            parserVersion = parserVersion,
            languageDetectorVersion = languageDetectorVersion,
            citedPaperParserId = citedPaperParserId,
            citedPaperParserVersion = citedPaperParserVersion,
            limits = ValidationLimitsSnapshot(
                maxUploadBytes = validator.limits.maxBytes,
                maxPages = validator.limits.maxPages,
                maxExtractedCharacters = validator.limits.maxExtractedCharacters,
                maxExtractedCharactersPerPage = validator.limits.maxExtractedCharactersPerPage,
                minimumExtractedCharacters = validator.limits.minimumExtractedCharacters,
                minimumLanguageConfidence = validator.limits.minimumLanguageConfidence,
                maxClaimCitationPairs = maxClaimCitationPairs,
            ),
            referenceResolutionConfidenceThreshold = referenceResolutionConfidenceThreshold,
            retrievalProfileId = retrievalProfileId,
            vectorCandidateLimit = vectorCandidateLimit,
            lexicalCandidateLimit = lexicalCandidateLimit,
            finalCandidateLimit = finalCandidateLimit,
            reciprocalRankFusionConstant = reciprocalRankFusionConstant,
            evidenceAggregationThresholds = aggregationThresholds.takeIf { systemOneAggregationEnabled },
            systemOneAggregationEnabled = systemOneAggregationEnabled,
            defaultSystemOneProvider = defaultSystemOneProvider,
        )
    }
}
