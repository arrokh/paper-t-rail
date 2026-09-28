package com.papertrail.api.evidence.verification.domain

/** One source-traceable core range with an optional adjacent-sentence context window. */
data class EvidencePassageSpanDefinition(
    val spanIndex: Int,
    val coreStartOffset: Int,
    val coreEndOffset: Int,
    val contextStartOffset: Int,
    val contextEndOffset: Int,
    val tokenCounts: List<Int>,
    val incompleteReason: String? = null,
) {
    init {
        require(spanIndex >= 0) { "Evidence span index must be non-negative." }
        require(coreStartOffset >= 0 && coreEndOffset > coreStartOffset) { "Evidence span core range is invalid." }
        require(contextStartOffset >= 0 && contextStartOffset <= coreStartOffset) {
            "Evidence span context must start at or before its core range."
        }
        require(contextEndOffset >= coreEndOffset) { "Evidence span context must end at or after its core range." }
        require(tokenCounts.size == 6 && tokenCounts.all { it >= 0 }) {
            "Evidence span must retain token counts for all six System One questions."
        }
    }

    val isIncomplete: Boolean get() = incompleteReason != null
}
