package com.papertrail.api.citation.claims.provider

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

internal object OpenAiCompatibleClaimAnalysisResponseFormat {
    fun create(objectMapper: ObjectMapper): JsonNode = objectMapper.readTree(RESPONSE_FORMAT_JSON)

    private const val RESPONSE_FORMAT_JSON = """
        {
          "type": "json_schema",
          "json_schema": {
            "name": "paper_trail_claim_analysis",
            "strict": true,
            "schema": {
              "type": "object",
              "properties": {
                "contexts": {
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "contextStartOffset": { "type": "integer" },
                      "contextEndOffset": { "type": "integer" },
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
                    "required": ["contextStartOffset", "contextEndOffset", "claims"],
                    "additionalProperties": false
                  }
                }
              },
              "required": ["contexts"],
              "additionalProperties": false
            }
          }
        }
    """
}
