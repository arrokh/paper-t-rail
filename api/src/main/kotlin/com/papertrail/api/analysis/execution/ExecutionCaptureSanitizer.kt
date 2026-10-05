package com.papertrail.api.analysis.execution

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class ExecutionCaptureSanitizer(
    @Value("\${paper-trail.analysis.execution.max-artifact-bytes}")
    private val maxArtifactBytes: Int = DEFAULT_MAX_ARTIFACT_BYTES,
) {
    private val objectMapper = ObjectMapper()

    init {
        require(maxArtifactBytes in 1..DEFAULT_MAX_ARTIFACT_BYTES) {
            "Execution artifact size limit must be between one byte and $DEFAULT_MAX_ARTIFACT_BYTES bytes."
        }
    }

    fun isSafeIdentifier(value: String): Boolean = SAFE_IDENTIFIER.matches(value) && !containsUnsafeText(value)

    fun sanitizeOpenAiRequestBody(body: ByteArray): SanitizedExecutionArtifact {
        if (body.size > maxArtifactBytes) return omitted("openai-compatible-request-v1", "ARTIFACT_TOO_LARGE")
        val request = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return omitted("openai-compatible-request-v1", "UNSAFE_UNSTRUCTURED_CONTENT")
        if (!request.isObject || request.fieldNames().asSequence().toSet() != OPENAI_REQUEST_FIELDS) {
            return omitted("openai-compatible-request-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        }
        val model = request.path("model").takeIf(JsonNode::isTextual)?.asText()
        val maxTokens = request.path("max_tokens").takeIf(JsonNode::isIntegralNumber)?.asInt()
        val temperature = request.path("temperature").takeIf(JsonNode::isNumber)?.asDouble()
        val stream = request.path("stream")
        val messages = request.path("messages")
        if (model == null || !isSafeIdentifier(model) || maxTokens == null || maxTokens <= 0 ||
            temperature != 0.0 || !stream.isBoolean || stream.asBoolean() || !request.path("response_format").isObject ||
            !messages.isArray || messages.isEmpty || messages.size() > MAX_OPENAI_MESSAGES ||
            messages.any { !it.isObject || it.path("role").asText() !in OPENAI_MESSAGE_ROLES || !it.path("content").isTextual }
        ) return omitted("openai-compatible-request-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        val partial = linkedMapOf(
            "model" to model,
            "temperature" to temperature,
            "max_tokens" to maxTokens,
            "stream" to false,
            "messageCount" to messages.size(),
            "requestBytes" to body.size,
            "promptContentOmitted" to true,
            "responseSchemaOmitted" to true,
        )
        return serializePartial("openai-compatible-request-v1", "PROMPT_AND_SCHEMA_CONTENT_OMITTED", partial)
    }

    fun sanitizeSystemOneRequestBody(body: ByteArray): SanitizedExecutionArtifact {
        if (body.size > maxArtifactBytes) return omitted("system-one-request-v1", "ARTIFACT_TOO_LARGE")
        val request = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return omitted("system-one-request-v1", "UNSAFE_UNSTRUCTURED_CONTENT")
        if (!request.isObject || request.fieldNames().asSequence().toSet() != SYSTEM_ONE_REQUEST_FIELDS) {
            return omitted("system-one-request-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        }
        val model = request.path("model").takeIf(JsonNode::isTextual)?.asText()
        val state = request.path("state")
        val questions = request.path("questions")
        if (model == null || !isSafeIdentifier(model) || !state.isObject || !questions.isObject ||
            questions.isEmpty || questions.size() > MAX_SYSTEM_ONE_QUESTIONS ||
            questions.fieldNames().asSequence().any { !isSafeIdentifier(it) } ||
            state.fieldNames().asSequence().toSet().any { it !in SYSTEM_ONE_STATE_FIELDS } ||
            state.fields().asSequence().any { (_, value) -> !value.isTextual }
        ) return omitted("system-one-request-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        if (!state.has("claim") || !state.has("evidence")) {
            return omitted("system-one-request-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        }
        return serializePartial(
            "system-one-request-v1",
            "CLAIM_AND_EVIDENCE_TEXT_OMITTED",
            linkedMapOf(
                "model" to model,
                "stateFields" to state.fieldNames().asSequence().toList().sorted(),
                "questionCount" to questions.size(),
                "requestBytes" to body.size,
                "claimAndEvidenceTextOmitted" to true,
            ),
        )
    }

    fun sanitizeJevResponseBody(body: ByteArray): SanitizedExecutionArtifact {
        if (body.size > maxArtifactBytes) return omitted("jev-system-one-response-v1", "ARTIFACT_TOO_LARGE")
        val response = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return omitted("jev-system-one-response-v1", "UNSAFE_UNSTRUCTURED_CONTENT")
        val model = response.path("model").takeIf(JsonNode::isTextual)?.asText()
        val answers = response.path("answers")
        val usage = response.path("usage")
        if (!response.isObject || model == null || !isSafeIdentifier(model) || !answers.isObject ||
            answers.isEmpty || answers.fieldNames().asSequence().toSet().any { it !in JEV_ANSWER_IDS } ||
            !usage.isObject || usage.fieldNames().asSequence().toSet() != JEV_USAGE_FIELDS ||
            usage.fields().asSequence().any { (_, value) -> !value.isIntegralNumber || value.asLong() < 0 }
        ) return omitted("jev-system-one-response-v1", "UNSAFE_UNSTRUCTURED_CONTENT")
        return serializePartial(
            "jev-system-one-response-v1",
            "ANSWER_CONTENT_OMITTED",
            linkedMapOf(
                "model" to model,
                "answerCount" to answers.size(),
                "usage" to usage.fields().asSequence().associate { it.key to it.value.asLong() },
                "answerContentOmitted" to true,
                "responseBytes" to body.size,
            ),
        )
    }

    fun sanitizeOpenAiResponseBody(body: ByteArray): SanitizedExecutionArtifact {
        if (body.size > maxArtifactBytes) return omitted("openai-compatible-response-v1", "ARTIFACT_TOO_LARGE")
        val response = runCatching { objectMapper.readTree(body) }.getOrNull()
            ?: return omitted("openai-compatible-response-v1", "UNSAFE_UNSTRUCTURED_CONTENT")
        if (!response.isObject) return omitted("openai-compatible-response-v1", "UNSAFE_UNSTRUCTURED_CONTENT")
        val model = response.path("model").takeIf(JsonNode::isTextual)?.asText()
        val choices = response.path("choices")
        if (model?.let(::isSafeIdentifier) == false || !choices.isArray || choices.isEmpty || choices.size() > MAX_OPENAI_CHOICES) {
            return omitted("openai-compatible-response-v1", "UNSAFE_UNSTRUCTURED_CONTENT")
        }
        val finishReasons = choices.map { choice ->
            val reason = choice.path("finish_reason").takeIf(JsonNode::isTextual)?.asText()
            val message = choice.path("message")
            val index = choice.path("index")
            if (!choice.isObject || (!index.isMissingNode && (!index.isIntegralNumber || index.asInt() < 0)) ||
                reason !in OPENAI_FINISH_REASONS || !message.isObject || message.path("role").asText() != "assistant" ||
                !message.path("content").isTextual
            ) return omitted("openai-compatible-response-v1", "UNSAFE_UNSTRUCTURED_CONTENT")
            reason
        }
        val usage = response.path("usage")
        val safeUsage = if (usage.isMissingNode || usage.isNull) emptyMap() else {
            if (!usage.isObject || usage.fieldNames().asSequence().toSet().any { it !in OPENAI_USAGE_FIELDS } ||
                usage.fields().asSequence().any { (_, value) -> !value.isIntegralNumber || value.asLong() < 0 }
            ) return omitted("openai-compatible-response-v1", "UNSAFE_UNSTRUCTURED_CONTENT")
            usage.fields().asSequence().associate { it.key to it.value.asLong() }
        }
        val partial = linkedMapOf<String, Any?>(
            "choiceCount" to choices.size(),
            "finishReasons" to finishReasons,
            "usage" to safeUsage,
            "responseBytes" to body.size,
            "messageContentOmitted" to true,
        )
        model?.let { partial["model"] = it }
        return serializePartial(
            "openai-compatible-response-v1",
            "GENERATED_MESSAGE_CONTENT_OMITTED",
            partial,
        )
    }

    fun omittedBody(schemaVersion: String, reason: String): SanitizedExecutionArtifact {
        if (!SAFE_SCHEMA_VERSION.matches(schemaVersion) || reason !in SAFE_OMISSION_REASONS) {
            return omitted("execution-body-v1", "UNSAFE_ARTIFACT_METADATA_OMITTED")
        }
        return omitted(schemaVersion, reason)
    }

    fun sanitize(schemaVersion: String, fields: Map<String, Any?>): SanitizedExecutionArtifact {
        if (schemaVersion in UNSAFE_RAW_SCHEMAS) {
            return SanitizedExecutionArtifact(null, CaptureFidelity.OMITTED, "UNSAFE_UNSTRUCTURED_CONTENT", schemaVersion)
        }
        val schema = SCHEMAS[schemaVersion]
            ?: return SanitizedExecutionArtifact(null, CaptureFidelity.OMITTED, "UNSUPPORTED_SCHEMA", schemaVersion)
        if (fields.keys.any { it !in schema.allowedFields } || !schema.requiredFields.all(fields::containsKey)) {
            return omitted(schemaVersion, "UNSUPPORTED_OR_UNSAFE_FIELDS")
        }

        val sanitized = linkedMapOf<String, Any?>()
        schema.allowedFields.sorted().forEach { key ->
            if (!fields.containsKey(key)) return@forEach
            val value = fields[key]
            if (!schema.validate(key, value)) return omittedForValue(schemaVersion, value)
            sanitized[key] = value
        }

        val content = try {
            objectMapper.writeValueAsString(sanitized)
        } catch (_: Exception) {
            return omitted(schemaVersion, "SANITIZATION_FAILED")
        }
        val sizeBytes = content.toByteArray(Charsets.UTF_8).size
        if (sizeBytes > maxArtifactBytes) return omitted(schemaVersion, "ARTIFACT_TOO_LARGE")
        return SanitizedExecutionArtifact(content, CaptureFidelity.SANITIZED, null, schemaVersion)
    }

    private fun omittedForValue(schemaVersion: String, value: Any?): SanitizedExecutionArtifact =
        omitted(schemaVersion, unsafeReason(value) ?: "UNSUPPORTED_OR_UNSAFE_FIELDS")

    private fun unsafeReason(value: Any?): String? = when (value) {
        is String -> when {
            EMAIL.containsMatchIn(value) || PHONE.containsMatchIn(value) -> "PRIVATE_CONTACT_DETAIL"
            AUTH_SECRET.containsMatchIn(value) -> "AUTHENTICATION_SECRET"
            PRIVATE_IDENTIFIER.containsMatchIn(value) -> "PRIVATE_IDENTIFIER"
            else -> null
        }
        is Iterable<*> -> value.firstNotNullOfOrNull(::unsafeReason)
        else -> null
    }

    private fun serializePartial(schemaVersion: String, reason: String, fields: Map<String, Any?>): SanitizedExecutionArtifact {
        val content = runCatching { objectMapper.writeValueAsString(fields) }.getOrNull()
            ?: return omitted(schemaVersion, "SANITIZATION_FAILED")
        if (content.toByteArray(Charsets.UTF_8).size > maxArtifactBytes) return omitted(schemaVersion, "ARTIFACT_TOO_LARGE")
        return SanitizedExecutionArtifact(content, CaptureFidelity.PARTIAL, reason, schemaVersion)
    }

    private fun omitted(schemaVersion: String, reason: String) =
        SanitizedExecutionArtifact(null, CaptureFidelity.OMITTED, reason, schemaVersion)

    private data class ArtifactSchema(
        val allowedFields: Set<String>,
        val requiredFields: Set<String>,
        val validate: (String, Any?) -> Boolean,
    )

    companion object {
        const val DEFAULT_MAX_ARTIFACT_BYTES = 1_048_576
        private val SAFE_IDENTIFIER = Regex("^[a-zA-Z0-9][a-zA-Z0-9._/-]{0,119}$")
        private val SAFE_HASH = Regex("^[0-9a-f]{64}$")
        private val SAFE_SCHEMA_VERSION = Regex("^[a-z0-9][a-z0-9-]{0,63}-v[0-9]{1,3}$")
        private val SAFE_OMISSION_REASONS = setOf(
            "ARTIFACT_TOO_LARGE", "UNSAFE_UNSTRUCTURED_CONTENT", "UNSUPPORTED_SCHEMA",
            "UNSUPPORTED_OR_UNSAFE_FIELDS", "SANITIZATION_FAILED", "PRIVATE_CONTACT_DETAIL",
            "AUTHENTICATION_SECRET", "PRIVATE_IDENTIFIER",
        )
        private val DOI = Regex("^10\\.[0-9]{4,9}/[-._;()/:A-Z0-9]+$", RegexOption.IGNORE_CASE)
        private val EMAIL = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        private val AUTH_SECRET = Regex("(?i)(?:bearer\\s+|api[_-]?key\\s*[:=]|access[_-]?token\\s*[:=]|sk-[a-z0-9-]{8,})")
        private val PHONE = Regex("(?<![A-Za-z0-9])\\+?[0-9][0-9 .()/-]{6,}[0-9](?![A-Za-z0-9])")
        private val PRIVATE_IDENTIFIER = Regex("(?i)\\b(?:participant|subject|patient|account|user|member|employee|record)[\\s_:#-]*[A-Z0-9][A-Z0-9_-]{1,}\\b")
        private val UNSAFE_RAW_SCHEMAS = setOf("source-document-pdf-request-v1", "source-parser-response-v1")
        private val OPENAI_REQUEST_FIELDS = setOf("model", "temperature", "max_tokens", "stream", "response_format", "messages")
        private val SYSTEM_ONE_REQUEST_FIELDS = setOf("model", "state", "questions")
        private val SYSTEM_ONE_STATE_FIELDS = setOf("claim", "evidence", "section")
        private val OPENAI_USAGE_FIELDS = setOf("prompt_tokens", "completion_tokens", "total_tokens")
        private val OPENAI_FINISH_REASONS = setOf("stop", "length", "tool_calls", "function_call", "content_filter")
        private val JEV_ANSWER_IDS = setOf("judgement", "evidence_role", "directness", "claim_scope_match", "study_design_quality", "relevance")
        private val JEV_USAGE_FIELDS = setOf("input_tokens", "output_tokens")
        private const val MAX_OPENAI_CHOICES = 20
        private const val MAX_OPENAI_MESSAGES = 100
        private const val MAX_SYSTEM_ONE_QUESTIONS = 100
        private val OPENAI_MESSAGE_ROLES = setOf("system", "user", "assistant")
        private val SCHEMAS = mapOf(
            "provider-call-summary-v1" to ArtifactSchema(
                allowedFields = setOf("providerId", "modelId", "httpStatus", "requestBytes", "responseBytes", "elapsedMillis"),
                requiredFields = setOf("providerId"),
                validate = { key, value ->
                    when (key) {
                        "providerId", "modelId" -> value is String && SAFE_IDENTIFIER.matches(value) && !containsUnsafeText(value)
                        "httpStatus" -> value is Int && value in 100..599
                        "requestBytes", "responseBytes", "elapsedMillis" -> value is Number && value.toLong() >= 0
                        else -> false
                    }
                },
            ),
            "source-parser-input-v1" to ArtifactSchema(
                allowedFields = setOf("parserId", "parserVersion", "sourceSha256", "inputBytes"),
                requiredFields = setOf("parserId", "parserVersion", "sourceSha256", "inputBytes"),
                validate = { key, value ->
                    when (key) {
                        "parserId", "parserVersion" -> value is String && SAFE_IDENTIFIER.matches(value) && !containsUnsafeText(value)
                        "sourceSha256" -> value is String && SAFE_HASH.matches(value)
                        "inputBytes" -> value is Number && value.toLong() >= 0
                        else -> false
                    }
                },
            ),
            "citation-metadata-v1" to ArtifactSchema(
                allowedFields = setOf("title", "doi", "authors", "publicationYear"),
                requiredFields = setOf("title", "doi", "authors", "publicationYear"),
                validate = { key, value ->
                    when (key) {
                        "title" -> value is String && value.isNotBlank() && value.length <= 500 && !containsUnsafeText(value)
                        "doi" -> value is String && DOI.matches(value)
                        "authors" -> value is List<*> && value.isNotEmpty() && value.size <= 100 && value.all {
                            it is String && it.isNotBlank() && it.length <= 200 && !containsUnsafeText(it)
                        }
                        "publicationYear" -> value is Int && value in 1400..2200
                        else -> false
                    }
                },
            ),
            "run-stage-input-v1" to ArtifactSchema(
                allowedFields = setOf("itemCount", "candidateCount", "providerId", "modelId"),
                requiredFields = setOf("itemCount"),
                validate = { key, value ->
                    when (key) {
                        "itemCount", "candidateCount" -> value is Number && value.toLong() >= 0
                        "providerId", "modelId" -> value is String && SAFE_IDENTIFIER.matches(value) && !containsUnsafeText(value)
                        else -> false
                    }
                },
            ),
            "run-stage-result-v1" to ArtifactSchema(
                allowedFields = setOf("status", "itemCount", "completedCount", "failedCount"),
                requiredFields = setOf("status"),
                validate = { key, value ->
                    when (key) {
                        "status" -> value is String && value in setOf("SUCCEEDED", "FAILED", "SKIPPED", "REUSED")
                        "itemCount", "completedCount", "failedCount" -> value is Number && value.toLong() >= 0
                        else -> false
                    }
                },
            ),
        )

        private fun containsUnsafeText(value: String): Boolean = EMAIL.containsMatchIn(value) ||
            AUTH_SECRET.containsMatchIn(value) || PHONE.containsMatchIn(value) || PRIVATE_IDENTIFIER.containsMatchIn(value)
    }
}

enum class CaptureFidelity {
    COMPLETE,
    SANITIZED,
    PARTIAL,
    OMITTED,
    REMOVED,
    UNAVAILABLE,
}

data class SanitizedExecutionArtifact(
    val content: String?,
    val fidelity: CaptureFidelity,
    val reason: String?,
    val schemaVersion: String,
)
