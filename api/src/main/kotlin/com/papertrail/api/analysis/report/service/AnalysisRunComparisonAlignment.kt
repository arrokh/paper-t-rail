package com.papertrail.api.analysis.report.service

import com.papertrail.api.analysis.report.http.AnalysisRunComparisonResponse

internal data class ComparisonCandidate(
    val result: AnalysisRunComparisonResponse.PairResult,
    val referenceIdentity: String?,
)

internal class AnalysisRunComparisonAlignment {
    fun align(
        left: List<ComparisonCandidate>,
        right: List<ComparisonCandidate>,
        sameSourceContent: Boolean,
    ): List<AnalysisRunComparisonResponse.Item> {
        val leftByKey = left.mapNotNull { candidate -> alignmentKey(candidate, sameSourceContent)?.let { it to candidate } }.groupBy({ it.first }, { it.second })
        val rightByKey = right.mapNotNull { candidate -> alignmentKey(candidate, sameSourceContent)?.let { it to candidate } }.groupBy({ it.first }, { it.second })
        val items = mutableListOf<AnalysisRunComparisonResponse.Item>()
        val handledLeft = mutableSetOf<ComparisonCandidate>()
        val handledRight = mutableSetOf<ComparisonCandidate>()

        (leftByKey.keys + rightByKey.keys).toSortedSet().forEach { key ->
            val leftMatches = leftByKey[key].orEmpty()
            val rightMatches = rightByKey[key].orEmpty()
            when {
                leftMatches.size == 1 && rightMatches.size == 1 -> {
                    val leftCandidate = leftMatches.single()
                    val rightCandidate = rightMatches.single()
                    handledLeft += leftCandidate
                    handledRight += rightCandidate
                    items += alignedItem(leftCandidate.result, rightCandidate.result)
                }
                leftMatches.size > 1 || rightMatches.size > 1 -> {
                    leftMatches.forEach { candidate ->
                        handledLeft += candidate
                        items += unmatchedItem(candidate.result, "AMBIGUOUS_CANDIDATES", leftSide = true)
                    }
                    rightMatches.forEach { candidate ->
                        handledRight += candidate
                        items += unmatchedItem(candidate.result, "AMBIGUOUS_CANDIDATES", leftSide = false)
                    }
                }
            }
        }

        left.filterNot(handledLeft::contains).forEach { candidate ->
            items += if (candidate.referenceIdentity == null || hasIdentityUnknownPeer(candidate, right, sameSourceContent)) {
                unmatchedItem(candidate.result, "NO_COMPATIBLE_REFERENCE_IDENTITY", leftSide = true)
            } else {
                singleSideItem(candidate.result, added = false)
            }
        }
        right.filterNot(handledRight::contains).forEach { candidate ->
            items += if (candidate.referenceIdentity == null || hasIdentityUnknownPeer(candidate, left, sameSourceContent)) {
                unmatchedItem(candidate.result, "NO_COMPATIBLE_REFERENCE_IDENTITY", leftSide = false)
            } else {
                singleSideItem(candidate.result, added = true)
            }
        }

        return items.sortedWith(
            compareBy<AnalysisRunComparisonResponse.Item> { it.left?.sourceStartOffset ?: it.right?.sourceStartOffset ?: Int.MAX_VALUE }
                .thenBy { it.left?.sourceEndOffset ?: it.right?.sourceEndOffset ?: Int.MAX_VALUE }
                .thenBy { it.changeKind },
        )
    }

    private fun hasIdentityUnknownPeer(
        candidate: ComparisonCandidate,
        otherSide: List<ComparisonCandidate>,
        sameSourceContent: Boolean,
    ): Boolean = otherSide.any { other ->
        other.referenceIdentity == null && sameClaimAnchor(candidate.result, other.result, sameSourceContent)
    }

    private fun sameClaimAnchor(
        left: AnalysisRunComparisonResponse.PairResult,
        right: AnalysisRunComparisonResponse.PairResult,
        sameSourceContent: Boolean,
    ): Boolean = if (sameSourceContent) {
        left.sourceStartOffset == right.sourceStartOffset && left.sourceEndOffset == right.sourceEndOffset
    } else {
        normalize(left.claimText) == normalize(right.claimText)
    }

    private fun alignmentKey(candidate: ComparisonCandidate, sameSourceContent: Boolean): String? {
        val referenceIdentity = candidate.referenceIdentity ?: return null
        val result = candidate.result
        return if (sameSourceContent) {
            "span:${result.sourceStartOffset}:${result.sourceEndOffset}:$referenceIdentity"
        } else {
            "claim:${normalize(result.claimText)}:$referenceIdentity"
        }
    }

    private fun alignedItem(
        left: AnalysisRunComparisonResponse.PairResult,
        right: AnalysisRunComparisonResponse.PairResult,
    ): AnalysisRunComparisonResponse.Item {
        val changedFields = buildList {
            if (left.claimText != right.claimText || left.citationContextText != right.citationContextText ||
                left.sourceStartOffset != right.sourceStartOffset || left.sourceEndOffset != right.sourceEndOffset
            ) add("sourceClaimAndContext")
            if (left.citationTargets.map { it.markerText to it.associationKind }.sortedBy { it.first } !=
                right.citationTargets.map { it.markerText to it.associationKind }.sortedBy { it.first }
            ) add("citationTargets")
            if (left.reference.copy(localReferenceKey = "") != right.reference.copy(localReferenceKey = "")) add("referenceResolution")
            if (left.citedPaperAccess != right.citedPaperAccess) add("citedPaperAccess")
            if (left.evidencePassages != right.evidencePassages) add("evidencePassages")
            if (machineOutcome(left) != machineOutcome(right)) add("machineOutcome")
        }
        val outcomeChanged = left.processingStatus != right.processingStatus || left.finalStatus != right.finalStatus
        return AnalysisRunComparisonResponse.Item(
            changeKind = if (changedFields.isEmpty()) "UNCHANGED" else "CHANGED",
            alignmentStatus = "ALIGNED",
            unmatchedReason = null,
            changedFields = changedFields,
            outcomeChanged = outcomeChanged,
            left = left,
            right = right,
        )
    }

    private fun singleSideItem(
        result: AnalysisRunComparisonResponse.PairResult,
        added: Boolean,
    ) = AnalysisRunComparisonResponse.Item(
        changeKind = if (added) "ADDED" else "REMOVED",
        alignmentStatus = "UNPAIRED",
        unmatchedReason = null,
        changedFields = emptyList(),
        outcomeChanged = false,
        left = result.takeUnless { added },
        right = result.takeIf { added },
    )

    private fun unmatchedItem(
        result: AnalysisRunComparisonResponse.PairResult,
        reason: String,
        leftSide: Boolean,
    ) = AnalysisRunComparisonResponse.Item(
        changeKind = "UNMATCHED",
        alignmentStatus = "UNMATCHED",
        unmatchedReason = reason,
        changedFields = emptyList(),
        outcomeChanged = false,
        left = result.takeIf { leftSide },
        right = result.takeUnless { leftSide },
    )

    private fun machineOutcome(result: AnalysisRunComparisonResponse.PairResult): List<Any?> = listOf(
        result.processingStatus,
        result.processingFailureReason,
        result.finalStatus,
        result.verificationScope,
        result.terminalReason,
        result.evidenceConflict,
        result.aggregatorVersion,
    )

    private fun normalize(value: String): String = value.trim().replace(WHITESPACE, " ")

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
