package com.papertrail.api.runs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.providers.CLAIM_EXTRACTOR_ROLE
import com.papertrail.api.providers.DataCategory
import com.papertrail.api.providers.EMBEDDING_ROLE
import com.papertrail.api.providers.ProviderCatalog
import com.papertrail.api.providers.ProviderRegistration
import com.papertrail.api.providers.ProviderTrustBoundary
import com.papertrail.api.providers.SCHOLARLY_METADATA_ROLE
import com.papertrail.api.providers.SYSTEM_ONE_ROLE
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

@Schema(description = "Provider selections and explicit per-run external-provider consent.")
data class RunConfigurationRequest(
    @field:Schema(description = "Claim extractor provider.", defaultValue = "heuristic", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val claimExtractorProvider: String = "heuristic",
    @field:Schema(description = "Embedding provider.", defaultValue = "local", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val embeddingProvider: String = "local",
    @field:Schema(description = "System One verification provider.", defaultValue = "mock", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val systemOneProvider: String = "mock",
    @field:Schema(description = "Scholarly metadata provider used for conservative bibliography resolution.", defaultValue = "recorded-fixtures", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val scholarlyMetadataProvider: String = "recorded-fixtures",
    @field:Schema(description = "Provider-specific data categories explicitly approved for this run.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    val externalProviderConsents: List<ExternalProviderConsentSnapshot> = emptyList(),
)

data class ProviderSelection(
    val provider: String,
    val version: String,
    val model: String? = null,
    val trustBoundary: String = ProviderTrustBoundary.LOCAL.id,
    val dataCategories: List<String> = emptyList(),
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
    val executionStatus: String = "NOT_RUN",
    val provider: ProviderSelection? = null,
    val scorePolicyVersion: String? = null,
    val confidenceThreshold: Double? = null,
    val providerConfigurationFingerprint: String? = null,
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
    val referenceResolution: ReferenceResolutionSnapshot = ReferenceResolutionSnapshot(),
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

@Schema(description = "Persisted stage, optional completion percentage, and user-facing progress message.")
data class AnalysisRunProgress(
    @field:Schema(description = "Current persisted processing stage.")
    val stage: String,
    @field:Schema(description = "Completion percentage when the stage provides one.")
    val percent: Int? = null,
    @field:Schema(description = "Human-readable progress or failure message.")
    val message: String,
)

data class AnalysisRunSummary(
    val id: UUID,
    val documentId: UUID,
    val filename: String,
    val sourceContentSha256: String,
    @field:Schema(description = "PARSED means parsed citation structure, Atomic Claims, inferred Citation Target links, and conservative bibliography-resolution outcomes are persisted; evidence verification has not run.")
    val status: String,
    @field:Schema(implementation = AnalysisRunProgress::class, description = "Persisted run progress.")
    val progress: JsonNode,
    @field:Schema(implementation = AnalysisConfigurationSnapshot::class, description = "Immutable configuration and provenance snapshot for this run.")
    val configuration: JsonNode,
    val createdAt: Instant,
    val startedAt: Instant?,
    val failureReason: String?,
)

@Schema(description = "One page of Analysis Runs ordered by creation time descending, then ID descending.")
data class AnalysisRunPage(
    @field:Schema(description = "Analysis Runs in this page.")
    val items: List<AnalysisRunSummary>,
    @field:Schema(description = "Opaque cursor for the next, older page; null when there are no more runs.")
    val nextCursor: String?,
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
    private val providerCatalog: ProviderCatalog,
    private val parserId: String,
    private val parserVersion: String,
    private val languageDetectorVersion: String,
    private val limits: ValidationLimitsSnapshot,
    private val referenceResolutionPolicyVersion: String = com.papertrail.api.references.ScholarlyMetadataMatcher.POLICY_VERSION,
    private val referenceResolutionConfidenceThreshold: Double = 0.9,
) {
    init {
        require(referenceResolutionPolicyVersion.isNotBlank()) { "Reference resolution policy version must be configured." }
        require(referenceResolutionConfidenceThreshold in 0.0..1.0) { "Reference resolution threshold must be between zero and one." }
    }
    fun parseRequest(node: JsonNode?): RunConfigurationRequest {
        if (node == null || node.isNull) return RunConfigurationRequest()
        require(node.isObject) { "Analysis configuration must be a JSON object." }
        val allowed = setOf("claimExtractorProvider", "embeddingProvider", "systemOneProvider", "scholarlyMetadataProvider", "externalProviderConsents")
        val supplied = node.fieldNames().asSequence().toSet()
        require(supplied.all { it in allowed }) { "Analysis configuration contains unsupported fields." }
        fun provider(name: String, default: String): String {
            val value = node.get(name) ?: return default
            require(value.isTextual && value.asText().isNotBlank()) { "Analysis configuration field '$name' must be a non-empty string." }
            return value.asText()
        }
        val providerConsents = node.get("externalProviderConsents")?.let { consents ->
            require(consents.isArray) { "Analysis configuration field 'externalProviderConsents' must be an array." }
            consents.map { consent ->
                require(consent.isObject) { "Each external provider consent must be an object." }
                val consentFields = consent.fieldNames().asSequence().toSet()
                require(consentFields == setOf("providerId", "dataCategories")) {
                    "External provider consent must contain only providerId and dataCategories."
                }
                val providerId = consent.get("providerId")
                require(providerId.isTextual && providerId.asText().isNotBlank()) {
                    "External provider consent providerId must be a non-empty string."
                }
                val categories = consent.get("dataCategories")
                require(categories.isArray && categories.all { it.isTextual && it.asText().isNotBlank() }) {
                    "External provider consent dataCategories must be an array of non-empty strings."
                }
                ExternalProviderConsentSnapshot(providerId.asText(), categories.map { it.asText() })
            }
        } ?: emptyList()
        return RunConfigurationRequest(
            claimExtractorProvider = provider("claimExtractorProvider", "heuristic"),
            embeddingProvider = provider("embeddingProvider", "local"),
            systemOneProvider = provider("systemOneProvider", "mock"),
            scholarlyMetadataProvider = provider("scholarlyMetadataProvider", "recorded-fixtures"),
            externalProviderConsents = providerConsents,
        )
    }

    fun from(request: RunConfigurationRequest): AnalysisConfigurationSnapshot {
        val selected = listOf(
            providerCatalog.requireSelectable(CLAIM_EXTRACTOR_ROLE, request.claimExtractorProvider),
            providerCatalog.requireSelectable(EMBEDDING_ROLE, request.embeddingProvider),
            providerCatalog.requireSelectable(SYSTEM_ONE_ROLE, request.systemOneProvider),
            providerCatalog.requireSelectable(SCHOLARLY_METADATA_ROLE, request.scholarlyMetadataProvider),
        )
        val requiredConsents = selected
            .filter { it.trustBoundary == ProviderTrustBoundary.EXTERNAL }
            .groupBy(ProviderRegistration::providerId)
            .mapValues { (_, providers) -> providers.flatMap(ProviderRegistration::dataCategories).toSet() }
        val suppliedConsents = request.externalProviderConsents.associateBy { it.providerId }
        require(suppliedConsents.size == request.externalProviderConsents.size) {
            "External provider consent must be listed once per provider."
        }
        require(suppliedConsents.keys == requiredConsents.keys) {
            "External provider consent must match the external providers selected for this Analysis Run."
        }
        val consentSnapshots = requiredConsents.map { (providerId, requiredCategories) ->
            val consent = suppliedConsents.getValue(providerId)
            val suppliedCategories = consent.dataCategories.map { id ->
                DataCategory.fromId(id)
                    ?: throw IllegalArgumentException("Unknown data category '$id' in provider consent.")
            }
            require(suppliedCategories.distinct().size == suppliedCategories.size) {
                "Provider consent data categories must not contain duplicates."
            }
            require(suppliedCategories.toSet() == requiredCategories) {
                "Provider '$providerId' consent must exactly match its declared payload categories."
            }
            ExternalProviderConsentSnapshot(providerId, requiredCategories.map(DataCategory::id).sorted())
        }.sortedBy(ExternalProviderConsentSnapshot::providerId)
        return AnalysisConfigurationSnapshot(
            claimExtractor = selected[0].toSelection(),
            embedding = selected[1].toSelection(),
            systemOne = selected[2].toSelection(),
            sourceParser = ProviderSelection(parserId, parserVersion),
            languageDetector = ProviderSelection("optimaize", languageDetectorVersion),
            validationLimits = limits,
            referenceResolution = ReferenceResolutionSnapshot(
                executionStatus = "PENDING",
                provider = selected[3].toSelection(),
                providerConfigurationFingerprint = selected[3].payloadConfigurationFingerprint,
                scorePolicyVersion = referenceResolutionPolicyVersion,
                confidenceThreshold = referenceResolutionConfidenceThreshold,
            ),
            aggregation = AggregationPolicySnapshot(
                executionStatus = "NOT_RUN",
                verificationPolicyVersion = null,
                aggregationPolicyVersion = null,
                thresholds = null,
            ),
            externalProviderConsents = consentSnapshots,
        )
    }

    private fun ProviderRegistration.toSelection(): ProviderSelection = ProviderSelection(
        provider = providerId,
        version = version,
        model = model,
        trustBoundary = trustBoundary.id,
        dataCategories = dataCategories.map(DataCategory::id).sorted(),
    )

    fun toJson(snapshot: AnalysisConfigurationSnapshot): String = objectMapper.writeValueAsString(snapshot)
}
