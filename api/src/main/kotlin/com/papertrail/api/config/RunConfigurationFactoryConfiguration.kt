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
    fun providerCatalog(): ProviderCatalog = ProviderCatalog.safeDefaults()

    @Bean
    fun providerCallGate(providerCatalog: ProviderCatalog): ProviderCallGate = ProviderCallGate(providerCatalog)

    @Bean
    fun runConfigurationFactory(
        objectMapper: ObjectMapper,
        providerCatalog: ProviderCatalog,
        validator: PdfDocumentValidator,
        @Value("\${paper-trail.validation.parser-version}") parserVersion: String,
        @Value("\${paper-trail.validation.language-detector-version}") languageDetectorVersion: String,
    ): RunConfigurationFactory = RunConfigurationFactory(
        objectMapper = objectMapper,
        providerCatalog = providerCatalog,
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
    )
}
