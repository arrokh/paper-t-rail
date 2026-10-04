package com.papertrail.api.evidence.verification.provider

/** Shared versioned questions and output categories for every typed System One adapter. */
object SystemOneEvidenceJudgementContract {
    const val JUDGEMENT_QUESTION = "judgement"
    const val ROLE_QUESTION = "evidence_role"
    const val DIRECTNESS_QUESTION = "directness"
    const val CLAIM_SCOPE_QUESTION = "claim_scope_match"
    const val STUDY_DESIGN_QUESTION = "study_design_quality"
    const val RELEVANCE_QUESTION = "relevance"
    const val PROBABILITY_SUM_TOLERANCE = 0.02

    val SCORE_LEVEL_LABELS = listOf("none", "low", "moderate", "high", "complete")
    val JUDGEMENT_VALUES = setOf("DIRECT_SUPPORT", "PARTIAL_SUPPORT", "CONTRADICTS", "UNRELATED", "INSUFFICIENT")
    val ROLE_VALUES = setOf("PRIMARY_FINDING", "AUTHOR_SYNTHESIS", "SECONDARY_REPORT")
    val EXPECTED_ANSWER_IDS = setOf(
        JUDGEMENT_QUESTION,
        ROLE_QUESTION,
        DIRECTNESS_QUESTION,
        CLAIM_SCOPE_QUESTION,
        STUDY_DESIGN_QUESTION,
        RELEVANCE_QUESTION,
    )
    val DIRECTNESS_LEVELS = listOf(
        "No evidence addresses the claim.",
        "Only an indirect or weak connection is present.",
        "The passage is relevant but does not directly answer the claim.",
        "The passage directly addresses most of the claim.",
        "The passage directly reports evidence for the complete claim.",
    )
    val CLAIM_SCOPE_LEVELS = listOf(
        "The population, conditions, or outcome do not match.",
        "A major material qualifier is missing or conflicts.",
        "The core claim matches, but at least one material qualifier is uncertain.",
        "The claim scope and nearly all material qualifiers match.",
        "The population, conditions, outcome, and material qualifiers match.",
    )
    val STUDY_DESIGN_LEVELS = listOf(
        "No study design or method is described.",
        "The described design provides very weak evidence for this claim.",
        "The design provides limited or observational evidence.",
        "The design provides reasonably strong evidence for this claim.",
        "The design is rigorous and directly suited to assess this claim.",
    )
    val RELEVANCE_LEVELS = listOf(
        "The passage is unrelated to the claim.",
        "The passage has only a slight topical connection.",
        "The passage is relevant but only partly addresses the claim.",
        "The passage is strongly relevant to the claim.",
        "The passage is directly and fully relevant to the claim.",
    )

    fun layaQuestions(): Map<String, Any> = questions(::layaScoreQuestion)

    fun jevQuestions(): Map<String, Any> = questions(::jevScoreQuestion)

    private fun questions(scoreQuestion: (String, List<String>) -> Map<String, Any>): Map<String, Any> = linkedMapOf(
        JUDGEMENT_QUESTION to mapOf(
            "type" to "choice",
            "instructions" to "How does the evidence passage relate to the atomic claim, considering every material qualifier?",
            "criteria" to linkedMapOf(
                "DIRECT_SUPPORT" to "The passage directly reports evidence supporting the claim and its material qualifiers.",
                "PARTIAL_SUPPORT" to "The passage supports only part of the claim or misses a material qualifier.",
                "CONTRADICTS" to "The passage reports evidence that materially conflicts with the claim.",
                "UNRELATED" to "The passage has no material bearing on the claim.",
                "INSUFFICIENT" to "The passage is ambiguous or does not permit a supported judgement.",
            ),
        ),
        ROLE_QUESTION to mapOf(
            "type" to "choice",
            "instructions" to "What is the evidentiary role of this passage within the cited paper?",
            "criteria" to linkedMapOf(
                "PRIMARY_FINDING" to "The cited paper's own methods, data, or results report this finding.",
                "AUTHOR_SYNTHESIS" to "The cited paper's authors interpret, summarize, or synthesize evidence across works.",
                "SECONDARY_REPORT" to "The passage attributes a finding to another cited work rather than reporting this paper's own result.",
            ),
        ),
        DIRECTNESS_QUESTION to scoreQuestion(
            "How directly does the passage answer the atomic claim?",
            DIRECTNESS_LEVELS,
        ),
        CLAIM_SCOPE_QUESTION to scoreQuestion(
            "How closely does the passage match the claim's population, conditions, outcome, and other material qualifiers?",
            CLAIM_SCOPE_LEVELS,
        ),
        STUDY_DESIGN_QUESTION to scoreQuestion(
            "How strong is the study design described in the passage for assessing this claim?",
            STUDY_DESIGN_LEVELS,
        ),
        RELEVANCE_QUESTION to scoreQuestion(
            "How relevant is the passage to the atomic claim?",
            RELEVANCE_LEVELS,
        ),
    )

    private fun layaScoreQuestion(instructions: String, levels: List<String>): Map<String, Any> = mapOf(
        "type" to "score",
        "instructions" to instructions + " Use this ordered scale: " +
            levels.mapIndexed { index, level -> "$index=$level" }.joinToString("; "),
        "criteria" to SCORE_LEVEL_LABELS,
    )

    private fun jevScoreQuestion(instructions: String, levels: List<String>): Map<String, Any> = mapOf(
        "type" to "score",
        "instructions" to instructions + " Use the listed criteria as the ordered scale from 0 to 4.",
        "criteria" to levels,
    )
}
