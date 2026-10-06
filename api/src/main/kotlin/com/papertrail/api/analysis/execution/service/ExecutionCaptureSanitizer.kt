package com.papertrail.api.analysis.execution.service

import com.papertrail.api.analysis.execution.domain.*

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.utils.JsonUtil
import com.fasterxml.jackson.databind.node.ObjectNode
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import java.net.URI

@Component
class ExecutionCaptureSanitizer(
    @Value("\${paper-trail.analysis.execution.max-artifact-bytes}")
    private val maxArtifactBytes: Int = DEFAULT_MAX_ARTIFACT_BYTES,
) {
    private val scholarlyResponseProjector = ScholarlyProviderResponseProjector()

    init {
        require(maxArtifactBytes in 1..DEFAULT_MAX_ARTIFACT_BYTES) {
            "Execution artifact size limit must be between one byte and $DEFAULT_MAX_ARTIFACT_BYTES bytes."
        }
    }

    fun isSafeIdentifier(value: String): Boolean = SAFE_IDENTIFIER.matches(value) &&
        (value == SAFE_FIXTURE_PROVIDER_ID || !containsUnsafeText(value))

    fun isSafeHttpRoute(value: String): Boolean = SAFE_HTTP_ROUTE.matches(value)

    fun sanitizeOpenAiRequestBody(body: ByteArray): SanitizedExecutionArtifact {
        if (body.size > maxArtifactBytes) return omitted("openai-compatible-request-v1", "ARTIFACT_TOO_LARGE")
        val request = parseJson(body) ?: return omitted("openai-compatible-request-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
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
        return serializePartial(
            "openai-compatible-request-v1",
            "PROMPT_AND_SCHEMA_CONTENT_OMITTED",
            linkedMapOf(
                "model" to model,
                "temperature" to temperature,
                "max_tokens" to maxTokens,
                "stream" to false,
                "messageCount" to messages.size(),
                "requestBytes" to body.size,
                "promptContentOmitted" to true,
                "responseSchemaOmitted" to true,
            ),
        )
    }

    fun sanitizeSystemOneRequestBody(body: ByteArray): SanitizedExecutionArtifact {
        if (body.size > maxArtifactBytes) return omitted("system-one-request-v1", "ARTIFACT_TOO_LARGE")
        val request = parseJson(body)
            ?: return omitted("system-one-request-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
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
        val response = parseJson(body)
            ?: return omitted("jev-system-one-response-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        val model = response.path("model").takeIf(JsonNode::isTextual)?.asText()
        val answers = response.path("answers")
        val usage = response.path("usage")
        if (!response.isObject || model == null || !isSafeIdentifier(model) || !answers.isObject ||
            answers.isEmpty || answers.fieldNames().asSequence().toSet().any { it !in JEV_ANSWER_IDS } ||
            !usage.isObject || usage.fieldNames().asSequence().toSet() != JEV_USAGE_FIELDS ||
            usage.fields().asSequence().any { (_, value) -> !value.isIntegralNumber || !value.canConvertToLong() || value.asLong() < 0 }
        ) return omitted("jev-system-one-response-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
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
        val response = parseJson(body)
            ?: return omitted("openai-compatible-response-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        if (!response.isObject) return omitted("openai-compatible-response-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        val model = response.path("model").takeIf(JsonNode::isTextual)?.asText()
        val choices = response.path("choices")
        if (model?.let(::isSafeIdentifier) == false || !choices.isArray || choices.isEmpty || choices.size() > MAX_OPENAI_CHOICES) {
            return omitted("openai-compatible-response-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        }
        val finishReasons = choices.map { choice ->
            val reason = choice.path("finish_reason").takeIf(JsonNode::isTextual)?.asText()
            val message = choice.path("message")
            val index = choice.path("index")
            if (!choice.isObject || (!index.isMissingNode && (!index.isIntegralNumber || index.asInt() < 0)) ||
                reason !in OPENAI_FINISH_REASONS || !message.isObject || message.path("role").asText() != "assistant" ||
                !message.path("content").isTextual
            ) return omitted("openai-compatible-response-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
            reason
        }
        val usage = response.path("usage")
        val safeUsage = if (usage.isMissingNode || usage.isNull) emptyMap() else {
            if (!usage.isObject || usage.fieldNames().asSequence().toSet().any { it !in OPENAI_USAGE_FIELDS } ||
                usage.fields().asSequence().any { (_, value) -> !value.isIntegralNumber || value.asLong() < 0 }
            ) return omitted("openai-compatible-response-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
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
        return serializePartial("openai-compatible-response-v1", "GENERATED_MESSAGE_CONTENT_OMITTED", partial)
    }

    fun sanitizeProviderJsonBody(schemaVersion: String, body: ByteArray): SanitizedExecutionArtifact {
        if (!SAFE_SCHEMA_VERSION.matches(schemaVersion)) return omitted("execution-provider-response-v1", "UNSUPPORTED_SCHEMA")
        if (body.size > maxArtifactBytes) return omitted(schemaVersion, "ARTIFACT_TOO_LARGE")
        return when (schemaVersion) {
            "crossref-response-v1" -> sanitizeCrossrefResponseBody(schemaVersion, body)
            "unpaywall-response-v1" -> sanitizeUnpaywallResponseBody(schemaVersion, body)
            "system-one-response-v1" -> sanitizeSystemOneResponseBody(schemaVersion, body)
            "system-one-preflight-response-v1" -> sanitizeSystemOnePreflightResponseBody(schemaVersion, body)
            else -> omitted(schemaVersion, "UNSUPPORTED_SCHEMA")
        }
    }

    private fun sanitizeSystemOneResponseBody(schemaVersion: String, body: ByteArray): SanitizedExecutionArtifact {
        val response = parseJson(body) ?: return omitted(schemaVersion, "UNSUPPORTED_OR_UNSAFE_FIELDS")
        val model = response.path("model").takeIf(JsonNode::isTextual)?.asText()
        val answers = response.path("answers")
        val usage = response.path("usage")
        if (!response.isObject || model == null || !isSafeIdentifier(model) || !answers.isObject ||
            answers.fieldNames().asSequence().toSet() != JEV_ANSWER_IDS || !usage.isObject ||
            usage.fieldNames().asSequence().toSet() != JEV_USAGE_FIELDS ||
            usage.fields().asSequence().any { (_, value) -> !value.isIntegralNumber || !value.canConvertToLong() || value.asLong() < 0 }
        ) return omitted(schemaVersion, "UNSUPPORTED_OR_UNSAFE_FIELDS")

        var omittedFields = response.fieldNames().asSequence().toSet() != setOf("model", "answers", "usage")
        val sanitizedAnswers = JsonUtil.objectNode()
        answers.fields().asSequence().forEach { (question, answer) ->
            val answerType = answer.path("type").takeIf(JsonNode::isTextual)?.asText()
            val supportedAnswer = JsonUtil.objectNode()
            when (answerType) {
                "choice" -> {
                    val expectedChoices = when (question) {
                        "judgement" -> SYSTEM_ONE_JUDGEMENT_VALUES
                        "evidence_role" -> SYSTEM_ONE_ROLE_VALUES
                        else -> return omitted(schemaVersion, "UNSUPPORTED_OR_UNSAFE_FIELDS")
                    }
                    val choice = answer.path("choice")
                    val probabilities = answer.path("probabilities")
                    val expectedFields = if (question == "judgement") {
                        setOf("type", "choice", "probabilities", "confidence", "answer_confidence")
                    } else {
                        setOf("type", "choice", "probabilities", "confidence")
                    }
                    if (!answer.isObject || !choice.isTextual || choice.asText() !in expectedChoices ||
                        !isValidProbabilityMap(probabilities, expectedChoices) || !isValidConfidence(answer.path("confidence")) ||
                        (question == "judgement" && !isValidConfidence(answer.path("answer_confidence")))
                    ) return omitted(schemaVersion, "UNSUPPORTED_OR_UNSAFE_FIELDS")
                    supportedAnswer.put("type", "choice")
                    supportedAnswer.set<JsonNode>("choice", choice)
                    supportedAnswer.set<JsonNode>("probabilities", probabilities)
                    supportedAnswer.set<JsonNode>("confidence", answer.path("confidence"))
                    if (question == "judgement") supportedAnswer.set<JsonNode>("answer_confidence", answer.path("answer_confidence"))
                    if (answer.fieldNames().asSequence().toSet() != expectedFields) omittedFields = true
                }
                "score" -> {
                    if (question !in SYSTEM_ONE_SCORE_ANSWER_IDS) return omitted(schemaVersion, "UNSUPPORTED_OR_UNSAFE_FIELDS")
                    val score = answer.path("score")
                    val probabilities = answer.path("probabilities")
                    val expectedFields = setOf("type", "score", "legend", "probabilities", "confidence")
                    if (!answer.isObject || !score.isNumber || !score.doubleValue().isFinite() || score.doubleValue() !in 0.0..4.0 ||
                        !isValidProbabilityMap(probabilities, SYSTEM_ONE_SCORE_VALUES) ||
                        !isValidConfidence(answer.path("confidence"))
                    ) return omitted(schemaVersion, "UNSUPPORTED_OR_UNSAFE_FIELDS")
                    supportedAnswer.put("type", "score")
                    supportedAnswer.set<JsonNode>("score", score)
                    supportedAnswer.set<JsonNode>("probabilities", probabilities)
                    supportedAnswer.set<JsonNode>("confidence", answer.path("confidence"))
                    if (answer.fieldNames().asSequence().toSet() != expectedFields) omittedFields = true
                }
                else -> return omitted(schemaVersion, "UNSUPPORTED_OR_UNSAFE_FIELDS")
            }
            sanitizedAnswers.set<ObjectNode>(question, supportedAnswer)
        }
        val supported = JsonUtil.objectNode()
            .put("model", model)
        supported.set<ObjectNode>("answers", sanitizedAnswers)
        supported.set<JsonNode>("usage", usage)
        val sanitized = sanitizeJsonTree(schemaVersion, supported)
        return if (omittedFields && sanitized.fidelity != CaptureFidelity.OMITTED) {
            sanitized.copy(fidelity = CaptureFidelity.PARTIAL, reason = "UNSUPPORTED_FIELDS_OMITTED")
        } else sanitized
    }

    private fun sanitizeSystemOnePreflightResponseBody(schemaVersion: String, body: ByteArray): SanitizedExecutionArtifact {
        val response = parseJson(body) ?: return omitted(schemaVersion, "UNSUPPORTED_OR_UNSAFE_FIELDS")
        val contextLimit = response.path("contextLimit")
        val tokenCounts = response.path("tokenCounts")
        if (!response.isObject || !contextLimit.isIntegralNumber || !contextLimit.canConvertToInt() || contextLimit.asInt() <= 0 ||
            !tokenCounts.isArray || tokenCounts.isEmpty || tokenCounts.size() > MAX_SYSTEM_ONE_QUESTIONS ||
            tokenCounts.any { !it.isIntegralNumber || !it.canConvertToLong() || it.asLong() < 0 }
        ) return omitted(schemaVersion, "UNSUPPORTED_OR_UNSAFE_FIELDS")
        val supported = JsonUtil.objectNode()
            .put("contextLimit", contextLimit.asInt())
        supported.set<JsonNode>("tokenCounts", tokenCounts)
        val sanitized = sanitizeJsonTree(schemaVersion, supported)
        return if (response.fieldNames().asSequence().toSet() != setOf("contextLimit", "tokenCounts")) {
            sanitized.copy(fidelity = CaptureFidelity.PARTIAL, reason = "UNSUPPORTED_FIELDS_OMITTED")
        } else sanitized
    }

    private fun sanitizeCrossrefResponseBody(schemaVersion: String, body: ByteArray): SanitizedExecutionArtifact {
        val projected = scholarlyResponseProjector.crossref(body)
            ?: return omitted(schemaVersion, "UNSUPPORTED_OR_UNSAFE_FIELDS")
        return markProviderResponsePartial(schemaVersion, projected)
    }


    private fun sanitizeUnpaywallResponseBody(schemaVersion: String, body: ByteArray): SanitizedExecutionArtifact {
        val projected = scholarlyResponseProjector.unpaywall(body)
            ?: return omitted(schemaVersion, "UNSUPPORTED_OR_UNSAFE_FIELDS")
        return markProviderResponsePartial(schemaVersion, projected)
    }

    private fun markProviderResponsePartial(schemaVersion: String, projected: ObjectNode): SanitizedExecutionArtifact {
        val sanitized = sanitizeJsonTree(schemaVersion, projected)
        return if (sanitized.fidelity == CaptureFidelity.OMITTED) sanitized else {
            sanitized.copy(fidelity = CaptureFidelity.PARTIAL, reason = "UNSUPPORTED_FIELDS_OMITTED")
        }
    }

    private fun isValidProbabilityMap(value: JsonNode, expectedKeys: Set<String>): Boolean =
        value.isObject && value.fieldNames().asSequence().toSet() == expectedKeys &&
            value.fields().asSequence().all { (_, probability) ->
                probability.isNumber && probability.doubleValue().isFinite() && probability.doubleValue() in 0.0..1.0
            }

    private fun isValidConfidence(value: JsonNode): Boolean =
        value.isNumber && value.doubleValue().isFinite() && value.doubleValue() in 0.0..1.0

    fun sanitizeCrossrefRequest(uri: URI): SanitizedExecutionArtifact {
        val pathParameters = JsonUtil.objectNode()
        val doi = uri.path.removePrefix("/works/").takeIf { it != uri.path && DOI.matches(it) }
        doi?.let { pathParameters.put("doi", it) }
        val queryParameters = JsonUtil.objectNode()
        var emailOmitted = false
        var bibliographicQueryOmitted = false
        var unsupportedParameterOmitted = false
        val query = runCatching { UriComponentsBuilder.fromUri(uri).build(true).queryParams }.getOrNull()
            ?: return omitted("crossref-request-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        query.forEach { (name, values) ->
            when (name) {
                "mailto" -> emailOmitted = true
                "query.bibliographic" -> bibliographicQueryOmitted = true
                "rows" -> {
                    val rows = values.singleOrNull()?.toIntOrNull()?.takeIf { it in 1..MAX_CAPTURED_METADATA_ITEMS }
                    if (rows == null) unsupportedParameterOmitted = true else queryParameters.put("rows", rows)
                }
                else -> unsupportedParameterOmitted = true
            }
        }
        val request = JsonUtil.objectNode()
            .put("method", "GET")
            .put("route", "/works")
            .put("contactParameterOmitted", emailOmitted)
            .put("bibliographicQueryOmitted", bibliographicQueryOmitted)
            .put("unsupportedParametersOmitted", unsupportedParameterOmitted)
        request.set<ObjectNode>("pathParameters", pathParameters)
        request.set<ObjectNode>("queryParameters", queryParameters)
        val sanitized = sanitizeJsonTree("crossref-request-v1", request)
        if ((bibliographicQueryOmitted || unsupportedParameterOmitted) && sanitized.fidelity != CaptureFidelity.OMITTED) {
            return sanitized.copy(fidelity = CaptureFidelity.PARTIAL, reason = "UNSUPPORTED_FIELDS_OMITTED")
        }
        if (emailOmitted && sanitized.fidelity == CaptureFidelity.COMPLETE) {
            return sanitized.copy(fidelity = CaptureFidelity.SANITIZED, reason = "SENSITIVE_VALUES_REDACTED")
        }
        return sanitized
    }

    fun sanitizeUnpaywallRequest(uri: URI): SanitizedExecutionArtifact {
        if (uri.scheme !in setOf("http", "https") || uri.host == null) {
            return omitted("unpaywall-request-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        }
        val doi = uri.path.removePrefix("/v2/").takeIf { uri.path.startsWith("/v2/") && it.isNotBlank() }
            ?.takeIf(DOI::matches)
            ?: return omitted("unpaywall-request-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        val query = runCatching { UriComponentsBuilder.fromUri(uri).build(true).queryParams }.getOrNull()
            ?: return omitted("unpaywall-request-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        val contactEmailOmitted = query.containsKey("email")
        val unsupportedParameterOmitted = query.keys.any { it != "email" }
        val request = JsonUtil.objectNode()
            .put("method", "GET")
            .put("route", "/v2/{doi}")
            .put("doi", doi)
            .put("contactParameterOmitted", contactEmailOmitted)
            .put("unsupportedParametersOmitted", unsupportedParameterOmitted)
        val sanitized = sanitizeJsonTree("unpaywall-request-v1", request)
        if (unsupportedParameterOmitted && sanitized.fidelity != CaptureFidelity.OMITTED) {
            return sanitized.copy(fidelity = CaptureFidelity.PARTIAL, reason = "UNSUPPORTED_FIELDS_OMITTED")
        }
        if (contactEmailOmitted && sanitized.fidelity == CaptureFidelity.COMPLETE) {
            return sanitized.copy(fidelity = CaptureFidelity.SANITIZED, reason = "SENSITIVE_VALUES_REDACTED")
        }
        return sanitized
    }

    fun sanitizeOpenAccessRequest(uri: URI): SanitizedExecutionArtifact {
        if (uri.scheme !in setOf("http", "https") || uri.host == null) {
            return omitted("open-access-content-request-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        }
        val rawUrl = uri.toASCIIString()
        val safeUrl = sanitizeUrl(rawUrl)
        if (safeUrl == REDACTED) return omitted("open-access-content-request-v1", "UNSUPPORTED_OR_UNSAFE_FIELDS")
        val request = JsonUtil.objectNode()
            .put("method", "GET")
            .put("url", safeUrl)
            .put("queryOmitted", uri.rawQuery != null)
        val sanitized = sanitizeJsonTree("open-access-content-request-v1", request)
        val omittedUrlDetails = safeUrl != rawUrl || uri.rawFragment != null
        if (omittedUrlDetails && sanitized.fidelity == CaptureFidelity.COMPLETE) {
            return sanitized.copy(fidelity = CaptureFidelity.SANITIZED, reason = "SENSITIVE_VALUES_REDACTED")
        }
        return sanitized
    }

    fun omittedBody(schemaVersion: String, reason: String): SanitizedExecutionArtifact {
        if (!SAFE_SCHEMA_VERSION.matches(schemaVersion) || reason !in SAFE_OMISSION_REASONS) {
            return omitted("execution-body-v1", "UNSAFE_ARTIFACT_METADATA_OMITTED")
        }
        return omitted(schemaVersion, reason)
    }

    fun sanitize(schemaVersion: String, fields: Map<String, Any?>): SanitizedExecutionArtifact {
        if (schemaVersion in UNSAFE_RAW_SCHEMAS) {
            val reason = if (schemaVersion == "source-document-pdf-request-v1") "BINARY_ASSET_REFERENCE" else "UNSUPPORTED_OR_UNSAFE_FIELDS"
            return SanitizedExecutionArtifact(null, CaptureFidelity.OMITTED, reason, schemaVersion)
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
            JsonUtil.toJson(sanitized)
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
            AUTH_BEARER.containsMatchIn(value) || AUTH_ASSIGNMENT.containsMatchIn(value) || API_TOKEN.containsMatchIn(value) -> "AUTHENTICATION_SECRET"
            PRIVATE_IDENTIFIER.containsMatchIn(value) -> "PRIVATE_IDENTIFIER"
            else -> null
        }
        is Iterable<*> -> value.firstNotNullOfOrNull(::unsafeReason)
        else -> null
    }

    private fun serializePartial(schemaVersion: String, reason: String, fields: Map<String, Any?>): SanitizedExecutionArtifact {
        val content = runCatching { JsonUtil.toJson(fields) }.getOrNull()
            ?: return omitted(schemaVersion, "SANITIZATION_FAILED")
        if (content.toByteArray(Charsets.UTF_8).size > maxArtifactBytes) return omitted(schemaVersion, "ARTIFACT_TOO_LARGE")
        return SanitizedExecutionArtifact(content, CaptureFidelity.PARTIAL, reason, schemaVersion)
    }

    private fun parseJson(body: ByteArray): JsonNode? = runCatching { JsonUtil.parseTree(body) }.getOrNull()

    private fun sanitizeJsonTree(schemaVersion: String, source: JsonNode): SanitizedExecutionArtifact {
        var changed = false
        fun scrub(node: JsonNode): JsonNode? = when {
            node.isObject -> {
                val safe = JsonUtil.objectNode()
                node.fields().forEachRemaining { (name, value) ->
                    if (isSensitiveFieldName(name)) {
                        changed = true
                    } else {
                        val sanitized = scrub(value)
                        if (sanitized == null) changed = true else safe.set<JsonNode>(name, sanitized)
                    }
                }
                safe
            }
            node.isArray -> {
                val safe = JsonUtil.arrayNode()
                node.forEach { value -> safe.add(scrub(value) ?: JsonUtil.nullNode().also { changed = true }) }
                safe
            }
            node.isTextual -> {
                val original = node.asText()
                val sanitized = sanitizeText(original)
                if (sanitized != original) changed = true
                JsonUtil.textNode(sanitized)
            }
            node.isBinary -> null
            else -> node.deepCopy<JsonNode>()
        }
        val sanitized = scrub(source) ?: return omitted(schemaVersion, "UNSUPPORTED_OR_UNSAFE_FIELDS")
        val content = runCatching { JsonUtil.toJson(sanitized) }.getOrNull()
            ?: return omitted(schemaVersion, "SANITIZATION_FAILED")
        if (content.toByteArray(Charsets.UTF_8).size > maxArtifactBytes) return omitted(schemaVersion, "ARTIFACT_TOO_LARGE")
        return SanitizedExecutionArtifact(
            content = content,
            fidelity = if (changed) CaptureFidelity.SANITIZED else CaptureFidelity.COMPLETE,
            reason = if (changed) "SENSITIVE_VALUES_REDACTED" else null,
            schemaVersion = schemaVersion,
        )
    }

    private fun sanitizeText(value: String): String {
        var sanitized = AUTH_BEARER.replace(value, "Bearer $REDACTED")
        sanitized = AUTH_ASSIGNMENT.replace(sanitized) { match -> "${match.groupValues[1]}$REDACTED" }
        sanitized = API_TOKEN.replace(sanitized, REDACTED)
        sanitized = EMAIL.replace(sanitized, REDACTED)
        sanitized = PHONE.replace(sanitized, REDACTED)
        sanitized = PRIVATE_IDENTIFIER.replace(sanitized, REDACTED)
        sanitized = URL.replace(sanitized) { match -> sanitizeUrl(match.value) }
        return sanitized
    }

    private fun sanitizeUrl(value: String): String {
        val uri = runCatching { URI(value.trimEnd('.', ',', ';', ')', ']')) }.getOrNull() ?: return REDACTED
        val host = uri.host ?: return REDACTED
        val path = sanitizeText(uri.path.orEmpty())
        return URI(uri.scheme, null, host, uri.port, path, null, null).toASCIIString()
    }

    private fun isSensitiveFieldName(name: String): Boolean {
        val tokens = CAMEL_CASE_BOUNDARY.replace(name, " ")
            .split(FIELD_NAME_SEPARATOR)
            .filter(String::isNotBlank)
            .map(String::lowercase)
        if (tokens.joinToString("_") in SAFE_TOKEN_COUNT_FIELDS) return false
        if (tokens.any { it in SENSITIVE_FIELD_TOKENS }) return true
        return tokens.windowed(2).any { pair -> pair.joinToString("_") in SENSITIVE_FIELD_TOKEN_PAIRS }
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
        private const val SAFE_FIXTURE_PROVIDER_ID = "recorded-fixtures"
        private val SAFE_IDENTIFIER = Regex("^(?![a-zA-Z][a-zA-Z0-9+.-]*://)[a-zA-Z0-9][a-zA-Z0-9._/:-]{0,119}$")
        private val SAFE_HTTP_ROUTE = Regex("^/(?:[A-Za-z0-9_-]+/)*[A-Za-z0-9_-]+$")
        private val SAFE_HASH = Regex("^[0-9a-f]{64}$")
        private val SAFE_SCHEMA_VERSION = Regex("^[a-z0-9][a-z0-9-]{0,63}-v[0-9]{1,3}$")
        private val SAFE_OMISSION_REASONS = setOf(
            "ARTIFACT_TOO_LARGE", "UNSUPPORTED_SCHEMA", "UNSUPPORTED_OR_UNSAFE_FIELDS",
            "SANITIZATION_FAILED", "PRIVATE_CONTACT_DETAIL",
            "AUTHENTICATION_SECRET", "PRIVATE_IDENTIFIER", "BINARY_ASSET_REFERENCE",
        )
        private const val REDACTED = "[REDACTED]"
        private val DOI = Regex("^10\\.[0-9]{4,9}/[-._;()/:A-Z0-9]+$", RegexOption.IGNORE_CASE)
        private val EMAIL = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        private val AUTH_BEARER = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/-]+=*")
        private val AUTH_ASSIGNMENT = Regex("(?i)(\\b(?:authorization|auth|api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|client[_-]?secret|secret|credential)\\s*[:=]\\s*[\\\"']?)[^\\s,\\\"'<>}]+")
        private val API_TOKEN = Regex("(?i)\\bsk-[a-z0-9_-]{8,}\\b")
        private val PHONE = Regex("(?<![A-Za-z0-9])\\+?[0-9][0-9 .()/-]{6,}[0-9](?![A-Za-z0-9])")
        private val PRIVATE_IDENTIFIER = Regex("(?i)\\b(?:participant|subject|patient|account|user|member|employee|record)[\\s_:#-]*[A-Z0-9][A-Z0-9_-]{1,}\\b")
        private val CAMEL_CASE_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
        private val FIELD_NAME_SEPARATOR = Regex("[^A-Za-z0-9]+")
        private val SENSITIVE_FIELD_TOKENS = setOf(
            "authorization", "auth", "password", "passwd", "secret", "credential", "token",
            "email", "mailto", "phone", "telephone", "mobile", "participant", "subject", "patient",
            "account", "user", "member", "employee", "record", "id", "identifier",
        )
        private val SENSITIVE_FIELD_TOKEN_PAIRS = setOf("api_key", "e_mail", "contact_email")
        private val SAFE_TOKEN_COUNT_FIELDS = setOf("token_count", "token_counts")
        private val URL = Regex("https?://[^\\s\\\"'<>]+", RegexOption.IGNORE_CASE)
        private val UNSAFE_RAW_SCHEMAS = setOf("source-document-pdf-request-v1", "source-parser-response-v1")
        private val OPENAI_REQUEST_FIELDS = setOf("model", "temperature", "max_tokens", "stream", "response_format", "messages")
        private val SYSTEM_ONE_REQUEST_FIELDS = setOf("model", "state", "questions")
        private val SYSTEM_ONE_STATE_FIELDS = setOf("claim", "evidence", "section")
        private val OPENAI_USAGE_FIELDS = setOf("prompt_tokens", "completion_tokens", "total_tokens")
        private val OPENAI_FINISH_REASONS = setOf("stop", "length", "tool_calls", "function_call", "content_filter")
        private val JEV_ANSWER_IDS = setOf("judgement", "evidence_role", "directness", "claim_scope_match", "study_design_quality", "relevance")
        private val JEV_USAGE_FIELDS = setOf("input_tokens", "output_tokens")
        private val SYSTEM_ONE_JUDGEMENT_VALUES = setOf("DIRECT_SUPPORT", "PARTIAL_SUPPORT", "CONTRADICTS", "UNRELATED", "INSUFFICIENT")
        private val SYSTEM_ONE_ROLE_VALUES = setOf("PRIMARY_FINDING", "AUTHOR_SYNTHESIS", "SECONDARY_REPORT")
        private val SYSTEM_ONE_SCORE_VALUES = setOf("0", "1", "2", "3", "4")
        private val SYSTEM_ONE_SCORE_ANSWER_IDS = setOf("directness", "claim_scope_match", "study_design_quality", "relevance")
        private const val MAX_OPENAI_CHOICES = 20
        private const val MAX_OPENAI_MESSAGES = 100
        private const val MAX_SYSTEM_ONE_QUESTIONS = 100
        private const val MAX_CAPTURED_METADATA_ITEMS = 10
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
            "open-access-content-metadata-v1" to ArtifactSchema(
                allowedFields = setOf("httpStatus", "mediaType", "byteCount"),
                requiredFields = setOf("httpStatus"),
                validate = { key, value ->
                    when (key) {
                        "httpStatus" -> value is Int && value in 100..599
                        "mediaType" -> value is String && value in setOf("application/pdf", "text/plain")
                        "byteCount" -> value is Int && value >= 0
                        else -> false
                    }
                },
            ),
        )

        private fun containsUnsafeText(value: String): Boolean = EMAIL.containsMatchIn(value) ||
            AUTH_BEARER.containsMatchIn(value) || AUTH_ASSIGNMENT.containsMatchIn(value) || API_TOKEN.containsMatchIn(value) ||
            PHONE.containsMatchIn(value) || PRIVATE_IDENTIFIER.containsMatchIn(value)
    }
}
