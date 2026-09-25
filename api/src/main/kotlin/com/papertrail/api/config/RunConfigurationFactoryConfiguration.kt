package com.papertrail.api.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.documents.PdfDocumentValidator
import com.papertrail.api.providers.ProviderCallGate
import com.papertrail.api.providers.ProviderCatalog
import com.papertrail.api.runs.RunConfigurationFactory
import com.papertrail.api.runs.ValidationLimitsSnapshot
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class RunConfigurationFactoryConfiguration {
    @Bean
    fun providerCatalog(
        @Value("\${paper-trail.providers.crossref.enabled:false}") crossrefEnabled: Boolean,
        @Value("\${paper-trail.providers.crossref.enablement-reviewed:false}") crossrefEnablementReviewed: Boolean,
        @Value("\${paper-trail.providers.crossref.retention-disclosure:}") crossrefRetentionDisclosure: String,
        @Value("\${paper-trail.providers.crossref.contact-email:}") crossrefContactEmail: String,
    ): ProviderCatalog = ProviderCatalog.safeDefaults(
        crossrefEnabled = crossrefEnabled,
        crossrefEnablementReviewed = crossrefEnablementReviewed,
        crossrefRetentionDisclosure = crossrefRetentionDisclosure.takeIf(String::isNotBlank),
        crossrefContactEmail = crossrefContactEmail.takeIf(String::isNotBlank),
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
    )
}
