package com.papertrail.api.citation.claims.provider

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.citation.claims.domain.ClaimAnalysisRequest
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallPayload
import org.springframework.stereotype.Component

@Component
class OpenAiCompatibleClaimAnalysisPayloadFactory {
    fun create(request: ClaimAnalysisRequest): ProviderCallPayload {
        val contexts = JsonUtil.arrayNode()
        request.contexts.forEach { context ->
            val contextNode = JsonUtil.objectNode().apply {
                put("contextText", context.contextText)
                put("contextStartOffset", context.contextStartOffset)
                put("contextEndOffset", context.contextEndOffset)
            }
            contextNode.set<JsonNode>("occurrences", JsonUtil.arrayNode().apply {
                context.occurrences.forEach { occurrence ->
                    val occurrenceNode = JsonUtil.objectNode().apply {
                        put("ordinal", occurrence.ordinal)
                        put("markerText", occurrence.markerText)
                        put("startOffset", occurrence.startOffset)
                        put("endOffset", occurrence.endOffset)
                        set<JsonNode>("targetKeys", JsonUtil.arrayNode().apply {
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
                JsonUtil.objectNode().apply {
                    put("localReferenceKey", target.key.bibliographyReferenceKey)
                    target.title?.let { put("title", it) }
                    set<JsonNode>("authors", JsonUtil.toTree(target.authors))
                    target.year?.let { put("year", it) }
                    target.doi?.let { put("doi", it) }
                }
            }
        val bibliographyMetadata = JsonUtil.objectNode().apply {
            set<JsonNode>("references", JsonUtil.toTree(bibliographyReferences))
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
        val root = JsonUtil.objectNode().apply {
            set<JsonNode>("contexts", contexts)
            set<JsonNode>("bibliographicMetadata", bibliographyMetadata)
        }
        return JsonUtil.toJson(root)
    }
}
