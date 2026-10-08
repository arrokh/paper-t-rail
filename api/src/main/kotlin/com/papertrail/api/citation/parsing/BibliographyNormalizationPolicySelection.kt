package com.papertrail.api.citation.parsing

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Versioned bibliography normalization policy applied to this Analysis Run.")
data class BibliographyNormalizationPolicySelection(
    @field:Schema(description = "Stable identifier for the application bibliography normalization policy.")
    val policyId: String,
    @field:Schema(description = "Policy version applied to the parsed bibliography.")
    val version: String,
) {
    init {
        require(policyId.isNotBlank()) { "Bibliography normalization policy ID must not be blank." }
        require(version.isNotBlank()) { "Bibliography normalization policy version must not be blank." }
    }

    companion object {
        const val POLICY_ID = "grobid-bibliography-normalization"
        val LEGACY = BibliographyNormalizationPolicySelection(POLICY_ID, "1")
        val CURRENT = BibliographyNormalizationPolicySelection(POLICY_ID, "2")
    }
}
