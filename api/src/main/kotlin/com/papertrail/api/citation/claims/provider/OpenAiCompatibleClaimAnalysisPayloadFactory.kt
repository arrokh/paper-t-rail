package com.papertrail.api.citation.claims.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.citation.claims.domain.ClaimAnalysisRequest
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallPayload
import org.springframework.stereotype.Component

@Component
class OpenAiCompatibleClaimAnalysisPayloadFactory(
    private val objectMapper: ObjectMapper,
) {
    fun create(request: ClaimAnalysisRequest): ProviderCallPayload {
        val contexts = objectMapper.createArrayNode()
        request.contexts.forEach { context ->
            val contextNode = objectMapper.createObjectNode().apply {
                put("contextText", context.contextText)
                put("contextStartOffset", context.contextStartOffset)
                put("contextEndOffset", context.contextEndOffset)
            }
            contextNode.set<JsonNode>("occurrences", objectMapper.createArrayNode().apply {
                context.occurrences.forEach { occurrence ->
                    val occurrenceNode = objectMapper.createObjectNode().apply {
                        put("ordinal", occurrence.ordinal)
                        put("markerText", occurrence.markerText)
                        put("startOffset", occurrence.startOffset)
                        put("endOffset", occurrence.endOffset)
                        set<JsonNode>("targetKeys", objectMapper.createArrayNode().apply {
                            occurrence.targets.forEach { add(it.key.value) }
                        })
                    }
                    add(occurrenceNode)
                }
            })
            contexts.add(contextNode)
        }

        val bibliographyReferences = request.contexts
            .flatMap { it.targetCandidates }
            .distinctBy { it.key.bibliographyReferenceKey }
            .map { target ->
                objectMapper.createObjectNode().apply {
                    put("localReferenceKey", target.key.bibliographyReferenceKey)
                    target.title?.let { put("title", it) }
                    set<JsonNode>("authors", objectMapper.valueToTree(target.authors))
                    target.year?.let { put("year", it) }
                    target.doi?.let { put("doi", it) }
                }
            }
        val bibliographyMetadata = objectMapper.createObjectNode().apply {
            set<JsonNode>("references", objectMapper.valueToTree(bibliographyReferences))
        }
        return ProviderCallPayload(
            mapOf(
                DataCategory.CITATION_CONTEXT to contexts,
                DataCategory.BIBLIOGRAPHIC_METADATA to bibliographyMetadata,
            ),
        )
    }

    fun userJson(payload: ProviderCallPayload): String {
        val contexts = payload.contentByCategory[DataCategory.CITATION_CONTEXT]
            ?: throw IllegalArgumentException("Claim-analysis payload lacks Citation Contexts.")
        val bibliographyMetadata = payload.contentByCategory[DataCategory.BIBLIOGRAPHIC_METADATA]
            ?: throw IllegalArgumentException("Claim-analysis payload lacks bibliography metadata.")
        val root = objectMapper.createObjectNode().apply {
            set<JsonNode>("contexts", contexts)
            set<JsonNode>("bibliographicMetadata", bibliographyMetadata)
        }
        return objectMapper.writeValueAsString(root)
    }
}
