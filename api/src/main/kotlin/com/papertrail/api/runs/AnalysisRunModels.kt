package com.papertrail.api.runs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

data class RunConfigurationRequest(
    val claimExtractorProvider: String = "heuristic",
    val embeddingProvider: String = "local",
    val systemOneProvider: String = "mock",
)

data class ProviderSelection(
    val provider: String,
    val version: String,
    val model: String? = null,
)

data class ValidationLimitsSnapshot(
    val maxUploadBytes: Long,
    val maxPages: Int,
    val maxExtractedCharacters: Int,
    val maxExtractedCharactersPerPage: Int,
    val minimumExtractedCharacters: Int,
    val minimumLanguageConfidence: Double,
)

data class ReferenceResolutionSnapshot(
    val executionStatus: String,
    val scorePolicyVersion: String?,
    val confidenceThreshold: Double?,
)

data class AggregationPolicySnapshot(
    val executionStatus: String,
    val verificationPolicyVersion: String?,
    val aggregationPolicyVersion: String?,
    val thresholds: Map<String, Double>?,
)

data class ExternalProviderConsentSnapshot(
    val providerId: String,
    val dataCategories: List<String>,
)

data class AnalysisConfigurationSnapshot(
    val claimExtractor: ProviderSelection,
    val embedding: ProviderSelection,
    val systemOne: ProviderSelection,
    val sourceParser: ProviderSelection,
    val languageDetector: ProviderSelection,
    val validationLimits: ValidationLimitsSnapshot,
    val referenceResolution: ReferenceResolutionSnapshot,
    val aggregation: AggregationPolicySnapshot,
    val externalProviderConsents: List<ExternalProviderConsentSnapshot>,
)

data class SourceDocumentSummary(
    val id: UUID,
    val filename: String,
    val sha256: String,
    val pageCount: Int,
    val language: String,
    val createdAt: Instant,
)

data class AnalysisRunSummary(
    val id: UUID,
    val documentId: UUID,
    val filename: String,
    val sourceContentSha256: String,
    val status: String,
    val progress: JsonNode,
    val configuration: JsonNode,
    val createdAt: Instant,
    val startedAt: Instant?,
    val failureReason: String?,
)

data class CreatedAnalysisRunResponse(
    val documentId: UUID,
    val analysisRunId: UUID,
    val filename: String,
    val sourceContentSha256: String,
    val status: String,
    val createdAt: Instant,
)

class RunConfigurationFactory(
    private val objectMapper: ObjectMapper,
    private val parserVersion: String,
    private val languageDetectorVersion: String,
    private val limits: ValidationLimitsSnapshot,
) {
    fun parseRequest(node: JsonNode?): RunConfigurationRequest {
        if (node == null || node.isNull) return RunConfigurationRequest()
        require(node.isObject) { "Analysis configuration must be a JSON object." }
        val allowed = setOf("claimExtractorProvider", "embeddingProvider", "systemOneProvider")
        val supplied = node.fieldNames().asSequence().toSet()
        require(supplied.all { it in allowed }) { "Analysis configuration contains unsupported fields." }
        fun provider(name: String, default: String): String {
            val value = node.get(name) ?: return default
            require(value.isTextual && value.asText().isNotBlank()) { "Analysis configuration field '$name' must be a non-empty string." }
            return value.asText()
        }
        return RunConfigurationRequest(
            claimExtractorProvider = provider("claimExtractorProvider", "heuristic"),
            embeddingProvider = provider("embeddingProvider", "local"),
            systemOneProvider = provider("systemOneProvider", "mock"),
        )
    }

    fun from(request: RunConfigurationRequest): AnalysisConfigurationSnapshot {
        require(request.claimExtractorProvider == "heuristic") {
            "Claim extractor '${request.claimExtractorProvider}' is unavailable; select heuristic."
        }
        require(request.embeddingProvider == "local") {
            "Embedding provider '${request.embeddingProvider}' is unavailable; select local."
        }
        require(request.systemOneProvider == "mock") {
            "System One provider '${request.systemOneProvider}' is unavailable; select mock."
        }
        return AnalysisConfigurationSnapshot(
            claimExtractor = ProviderSelection("heuristic", "v1"),
            embedding = ProviderSelection("local", "v1", "e5-small-v2"),
            systemOne = ProviderSelection("mock", "v1", "mock-v1"),
            sourceParser = ProviderSelection("pdfbox", parserVersion),
            languageDetector = ProviderSelection("optimaize", languageDetectorVersion),
            validationLimits = limits,
            referenceResolution = ReferenceResolutionSnapshot(
                executionStatus = "NOT_RUN",
                scorePolicyVersion = null,
                confidenceThreshold = null,
            ),
            aggregation = AggregationPolicySnapshot(
                executionStatus = "NOT_RUN",
                verificationPolicyVersion = null,
                aggregationPolicyVersion = null,
                thresholds = null,
            ),
            externalProviderConsents = emptyList(),
        )
    }

    fun toJson(snapshot: AnalysisConfigurationSnapshot): String = objectMapper.writeValueAsString(snapshot)
}
