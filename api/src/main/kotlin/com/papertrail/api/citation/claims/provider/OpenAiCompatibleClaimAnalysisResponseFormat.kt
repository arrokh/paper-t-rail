package com.papertrail.api.citation.claims.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

internal object OpenAiCompatibleClaimAnalysisResponseFormat {
    const val TYPE = "json_schema"
    const val NAME = "paper_trail_claim_analysis"

    fun create(objectMapper: ObjectMapper, allowedTargetKeys: Collection<String> = emptyList()): JsonNode {
        val responseFormat = objectMapper.readTree(RESPONSE_FORMAT_JSON)
        if (allowedTargetKeys.isNotEmpty()) {
            val targetKeySchema = responseFormat
                .path("json_schema")
                .path("schema")
                .path("properties")
                .path("claims")
                .path("items")
                .path("properties")
                .path("citationTargetKeys")
                .path("items") as ObjectNode
            targetKeySchema.set<JsonNode>("enum", objectMapper.valueToTree(allowedTargetKeys.distinct().sorted()))
        }
        return responseFormat
    }

    private const val RESPONSE_FORMAT_JSON = """
        {
          "type": "json_schema",
          "json_schema": {
            "name": "paper_trail_claim_analysis",
            "strict": true,
            "schema": {
              "type": "object",
              "properties": {
                "claims": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "text": { "type": "string" },
                      "sourceStartOffset": { "type": "integer" },
                      "sourceEndOffset": { "type": "integer" },
                      "citationTargetKeys": {
                        "type": "array",
                        "items": { "type": "string" }
                      }
                    },
                    "required": ["text", "sourceStartOffset", "sourceEndOffset", "citationTargetKeys"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["claims"],
              "additionalProperties": false
            }
          }
        }
    """
}
