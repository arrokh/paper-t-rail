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
        sourceOffsetsComparable: Boolean,
    ): List<AnalysisRunComparisonResponse.Item> {
        val leftByKey = left.mapNotNull { candidate -> alignmentKey(candidate, sourceOffsetsComparable)?.let { it to candidate } }.groupBy({ it.first }, { it.second })
        val rightByKey = right.mapNotNull { candidate -> alignmentKey(candidate, sourceOffsetsComparable)?.let { it to candidate } }.groupBy({ it.first }, { it.second })
        val items = mutableListOf<AnalysisRunComparisonResponse.Item>()
        val leftUnknownAnchors = unknownClaimAnchors(left, sourceOffsetsComparable)
        val rightUnknownAnchors = unknownClaimAnchors(right, sourceOffsetsComparable)
        val handledLeft = mutableSetOf<ComparisonCandidate>()
        val handledRight = mutableSetOf<ComparisonCandidate>()

        (leftByKey.keys + rightByKey.keys)
            .sortedWith(
                compareBy<AlignmentKey> { it.sourceStartOffset ?: Int.MAX_VALUE }
                    .thenBy { it.sourceEndOffset ?: Int.MAX_VALUE }
                    .thenBy { it.normalizedClaimText.orEmpty() }
                    .thenBy { it.referenceIdentity },
            )
            .forEach { key ->
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
            val claimAnchor = claimAnchor(candidate.result, sourceOffsetsComparable)
            items += when {
                candidate.referenceIdentity == null -> unmatchedItem(candidate.result, "NO_COMPATIBLE_REFERENCE_IDENTITY", leftSide = true)
                claimAnchor in rightUnknownAnchors -> unmatchedItem(candidate.result, "COUNTERPART_REFERENCE_IDENTITY_UNAVAILABLE", leftSide = true)
                else -> singleSideItem(candidate.result, added = false)
            }
        }
        right.filterNot(handledRight::contains).forEach { candidate ->
            val claimAnchor = claimAnchor(candidate.result, sourceOffsetsComparable)
            items += when {
                candidate.referenceIdentity == null -> unmatchedItem(candidate.result, "NO_COMPATIBLE_REFERENCE_IDENTITY", leftSide = false)
                claimAnchor in leftUnknownAnchors -> unmatchedItem(candidate.result, "COUNTERPART_REFERENCE_IDENTITY_UNAVAILABLE", leftSide = false)
                else -> singleSideItem(candidate.result, added = true)
            }
        }

        return items.sortedWith(
            compareBy<AnalysisRunComparisonResponse.Item> { it.left?.sourceStartOffset ?: it.right?.sourceStartOffset ?: Int.MAX_VALUE }
                .thenBy { it.left?.sourceEndOffset ?: it.right?.sourceEndOffset ?: Int.MAX_VALUE }
                .thenBy { it.changeKind },
        )
    }

    private fun unknownClaimAnchors(
        candidates: List<ComparisonCandidate>,
        sourceOffsetsComparable: Boolean,
    ): Set<ClaimAnchor> = candidates.asSequence()
        .filter { it.referenceIdentity == null }
        .map { claimAnchor(it.result, sourceOffsetsComparable) }
        .toSet()

    private fun claimAnchor(
        result: AnalysisRunComparisonResponse.PairResult,
        sourceOffsetsComparable: Boolean,
    ): ClaimAnchor = ClaimAnchor(
        sourceStartOffset = result.sourceStartOffset.takeIf { sourceOffsetsComparable },
        sourceEndOffset = result.sourceEndOffset.takeIf { sourceOffsetsComparable },
        normalizedClaimText = if (sourceOffsetsComparable) null else normalize(result.claimText),
        citationTargets = result.citationTargets.map { it.markerText to it.associationKind }
            .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second }),
    )

    private fun alignmentKey(candidate: ComparisonCandidate, sourceOffsetsComparable: Boolean): AlignmentKey? {
        val referenceIdentity = candidate.referenceIdentity ?: return null
        val result = candidate.result
        return AlignmentKey(
            sourceStartOffset = result.sourceStartOffset.takeIf { sourceOffsetsComparable },
            sourceEndOffset = result.sourceEndOffset.takeIf { sourceOffsetsComparable },
            normalizedClaimText = if (sourceOffsetsComparable) null else normalize(result.claimText),
            referenceIdentity = referenceIdentity,
        )
    }

    private fun alignedItem(
        left: AnalysisRunComparisonResponse.PairResult,
        right: AnalysisRunComparisonResponse.PairResult,
    ): AnalysisRunComparisonResponse.Item {
        val changedFields = buildList {
            if (left.claimText != right.claimText || left.citationContextText != right.citationContextText ||
                left.sourceStartOffset != right.sourceStartOffset || left.sourceEndOffset != right.sourceEndOffset
            ) add("sourceClaimAndContext")
            if (left.citationTargets.map { it.markerText to it.associationKind }
                    .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second }) !=
                right.citationTargets.map { it.markerText to it.associationKind }
                    .sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second })
            ) add("citationTargets")
            if (left.reference.copy(localReferenceKey = "") != right.reference.copy(localReferenceKey = "")) add("referenceResolution")
            if (left.citedPaperAccess != right.citedPaperAccess) add("citedPaperAccess")
            if (passageStructure(left) != passageStructure(right)) add("evidencePassages")
            if (machineOutcome(left) != machineOutcome(right) || evidenceJudgementsChanged(left, right)) add("machineOutcome")
        }
        val outcomeChanged = machineResult(left) != machineResult(right) || evidenceJudgementsChanged(left, right)
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

    private fun passageStructure(result: AnalysisRunComparisonResponse.PairResult) =
        result.evidencePassages.map { it.copy(judgement = null, evidenceRole = null) }

    private fun evidenceJudgementsChanged(
        left: AnalysisRunComparisonResponse.PairResult,
        right: AnalysisRunComparisonResponse.PairResult,
    ): Boolean {
        val leftByIdentity = left.evidencePassages.groupBy(::passageIdentity)
        val rightByIdentity = right.evidencePassages.groupBy(::passageIdentity)
        return leftByIdentity.keys.intersect(rightByIdentity.keys).any { identity ->
            val leftPassages = leftByIdentity.getValue(identity)
            val rightPassages = rightByIdentity.getValue(identity)
            leftPassages.size == rightPassages.size &&
                judgementCounts(leftPassages) != judgementCounts(rightPassages)
        }
    }

    private fun judgementCounts(passages: List<AnalysisRunComparisonResponse.EvidencePassage>) =
        passages.groupingBy { it.judgement to it.evidenceRole }.eachCount()

    private fun passageIdentity(passage: AnalysisRunComparisonResponse.EvidencePassage) = EvidencePassageIdentity(
        contentSha256 = passage.contentSha256,
        text = passage.text,
        sectionOrder = passage.sectionOrder,
        paragraphStart = passage.paragraphStart,
        paragraphEnd = passage.paragraphEnd,
    )

    private fun machineResult(result: AnalysisRunComparisonResponse.PairResult): List<Any?> = listOf(
        result.processingStatus,
        result.processingFailureReason,
        result.finalStatus,
        result.verificationScope,
        result.terminalReason,
        result.evidenceConflict,
    )

    private fun machineOutcome(result: AnalysisRunComparisonResponse.PairResult): List<Any?> =
        machineResult(result) + result.aggregatorVersion

    private data class AlignmentKey(
        val sourceStartOffset: Int?,
        val sourceEndOffset: Int?,
        val normalizedClaimText: String?,
        val referenceIdentity: String,
    )

    private data class ClaimAnchor(
        val sourceStartOffset: Int?,
        val sourceEndOffset: Int?,
        val normalizedClaimText: String?,
        val citationTargets: List<Pair<String, String>>,
    )

    private data class EvidencePassageIdentity(
        val contentSha256: String,
        val text: String,
        val sectionOrder: Int,
        val paragraphStart: Int,
        val paragraphEnd: Int,
    )

    private fun normalize(value: String): String = value.trim().replace(WHITESPACE, " ")

    private companion object {
        val WHITESPACE = Regex("\\s+")
    }
}
