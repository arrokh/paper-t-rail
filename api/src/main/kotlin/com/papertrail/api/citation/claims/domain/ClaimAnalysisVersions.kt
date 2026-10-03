package com.papertrail.api.citation.claims.domain

object ClaimAnalysisVersions {
    const val HEURISTIC_TARGET_SELECTION_POLICY = "heuristic-all-context-targets-v1"
    const val MODEL_TARGET_SELECTION_POLICY = "model-selected-same-context-targets-v1"
    const val HEURISTIC_OUTPUT_MAPPING = "heuristic-claim-analysis-v1"
    const val OPENAI_COMPATIBLE_PROMPT = "document-claim-analysis-v1"
    const val OPENAI_COMPATIBLE_OUTPUT_MAPPING = "chat-completions-claim-analysis-json-v1"
}
