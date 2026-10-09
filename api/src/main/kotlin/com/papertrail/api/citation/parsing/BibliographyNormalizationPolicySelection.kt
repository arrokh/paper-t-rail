package com.papertrail.api.citation.parsing

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Versioned bibliography normalization policy applied to this Analysis Run.")
data class BibliographyNormalizationPolicySelection(
    @field:Schema(description = "Stable identifier for the application bibliography normalization policy.")
    val policyId: String,
    @field:Schema(description = "Policy version applied to the parsed bibliography. Version 3 adds GROBID analytic-work chapter classification; earlier versions preserve their historical type output.")
    val version: String,
) {
    init {
        require(policyId.isNotBlank()) { "Bibliography normalization policy ID must not be blank." }
        require(version.isNotBlank()) { "Bibliography normalization policy version must not be blank." }
    }

    fun supportsChapterTypeClassification(): Boolean =
        policyId == POLICY_ID && version.toIntOrNull()?.let { it >= CHAPTER_CLASSIFICATION_VERSION } == true

    companion object {
        const val POLICY_ID = "grobid-bibliography-normalization"
        const val CHAPTER_CLASSIFICATION_VERSION = 3
        val LEGACY = BibliographyNormalizationPolicySelection(POLICY_ID, "1")
        val VERSION_2 = BibliographyNormalizationPolicySelection(POLICY_ID, "2")
        val CURRENT = BibliographyNormalizationPolicySelection(POLICY_ID, CHAPTER_CLASSIFICATION_VERSION.toString())
    }
}
