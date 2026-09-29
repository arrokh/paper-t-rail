package com.papertrail.api.calibration

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.papertrail.api.evidence.verification.domain.EvidenceAggregationPolicy
import com.papertrail.api.evidence.verification.domain.EvidenceAggregationThresholds
import com.papertrail.api.evidence.verification.domain.EvidenceJudgement
import com.papertrail.api.evidence.verification.domain.EvidenceJudgementKind
import com.papertrail.api.evidence.verification.domain.EvidenceRole
import com.papertrail.api.evidence.verification.provider.LayaSystemOneSettings
import com.papertrail.api.scholarly.references.resolver.ReferenceResolutionStatus
import java.net.URI
import java.security.MessageDigest
import java.time.Instant

/** Human-labelled, paper-split input for the pinned Laya evaluation. */
data class LayaEvaluationDataset(
    val schemaVersion: Int,
    val datasetId: String,
    val datasetVersion: String,
    val status: DatasetStatus,
    val protocolId: String,
    val candidate: Candidate,
    val papers: List<PaperProvenance>,
    val cases: List<Case>,
    val claimPaperOutcomes: List<ClaimPaperOutcome>,
    val aggregationCandidates: List<AggregationCandidate>,
) {
    fun heldOutSplitSha256(): String {
        val heldOutPaperIds = cases.filter { it.split == Split.HELD_OUT }.map(Case::citedPaperId).toSet()
        val manifest = HeldOutManifest(
            schemaVersion = schemaVersion,
            datasetId = datasetId,
            datasetVersion = datasetVersion,
            protocolId = protocolId,
            candidate = candidate,
            papers = papers.filter { it.citedPaperId in heldOutPaperIds }.sortedBy(PaperProvenance::citedPaperId),
            cases = cases.filter { it.split == Split.HELD_OUT }
                .sortedBy(Case::caseId)
                .map { it.copy(
                    independentReviews = it.independentReviews.sortedBy(ReviewAnnotation::reviewerId),
                    coverageTags = it.coverageTags.sortedBy(CoverageTag::name).toSet(),
                ) },
            claimPaperOutcomes = claimPaperOutcomes.filter { it.split == Split.HELD_OUT }
                .sortedBy(ClaimPaperOutcome::claimPaperId)
                .map { it.copy(independentReviews = it.independentReviews.sortedBy(ClaimPaperReview::reviewerId)) },
            aggregationCandidates = aggregationCandidates.sortedBy(AggregationCandidate::candidateId),
        )
        return sha256(FINGERPRINT_MAPPER.writeValueAsBytes(manifest))
    }

    fun validate() {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported Laya evaluation dataset schemaVersion '$schemaVersion'." }
        require(isStableIdentifier(datasetId) && isStableIdentifier(datasetVersion)) {
            "Laya evaluation dataset ID and version must be stable non-text identifiers."
        }
        require(isStableIdentifier(protocolId)) { "Laya evaluation dataset must identify its annotation protocol." }
        candidate.validate()
        require(papers.isNotEmpty()) { "Laya evaluation dataset must contain Cited Paper provenance." }
        require(cases.isNotEmpty()) { "Laya evaluation dataset must contain labelled cases." }
        require(claimPaperOutcomes.isNotEmpty()) { "Laya evaluation dataset must contain adjudicated Claim–Paper outcomes." }
        require(aggregationCandidates.isNotEmpty()) { "Laya evaluation dataset must contain pre-registered aggregation candidates." }

        val paperIds = papers.map(PaperProvenance::citedPaperId)
        require(paperIds.all(String::isNotBlank) && paperIds.distinct().size == paperIds.size) {
            "Laya evaluation Cited Paper identifiers must be non-blank and unique."
        }
        papers.forEach(PaperProvenance::validateBasic)
        cases.forEach(Case::validateBasic)
        claimPaperOutcomes.forEach(ClaimPaperOutcome::validateBasic)
        aggregationCandidates.forEach(AggregationCandidate::validate)
        require(aggregationCandidates.map(AggregationCandidate::candidateId).distinct().size == aggregationCandidates.size) {
            "Aggregation candidate IDs must be unique within a Laya evaluation dataset."
        }

        require(cases.map(Case::caseId).distinct().size == cases.size) {
            "Laya evaluation case identifiers must be unique."
        }
        require(cases.all { it.citedPaperId in paperIds }) {
            "Every Laya evaluation case must reference Cited Paper provenance in the dataset."
        }
        require(papers.all { paper -> cases.any { it.citedPaperId == paper.citedPaperId } }) {
            "Every Cited Paper provenance record must be used by at least one case."
        }
        require(cases.groupBy(Case::citedPaperId).values.all { paperCases ->
            paperCases.map(Case::split).distinct().size == 1
        }) {
            "All cases from one Cited Paper must remain in a single calibration or held-out split."
        }

        validateClaimPaperGroups()
        if (status == DatasetStatus.HUMAN_REVIEWED) validateReleaseDataset()
    }

    private fun validateClaimPaperGroups() {
        val outcomeIds = claimPaperOutcomes.map(ClaimPaperOutcome::claimPaperId)
        require(outcomeIds.all(String::isNotBlank) && outcomeIds.distinct().size == outcomeIds.size) {
            "Claim–Paper outcome identifiers must be non-blank and unique."
        }
        require(cases.all { case -> outcomeIds.contains(case.claimPaperId) }) {
            "Every Laya evaluation case must reference an adjudicated Claim–Paper outcome."
        }
        require(claimPaperOutcomes.all { outcome -> cases.any { it.claimPaperId == outcome.claimPaperId } }) {
            "Every Claim–Paper outcome must be supported by at least one Laya evaluation case."
        }
        claimPaperOutcomes.forEach { outcome ->
            val group = cases.filter { it.claimPaperId == outcome.claimPaperId }
            require(group.all { it.citedPaperId == outcome.citedPaperId && it.split == outcome.split }) {
                "All cases in Claim–Paper outcome '${outcome.claimPaperId}' must share its Cited Paper and split."
            }
            if (outcome.expectedConflict) {
                require(outcome.expectedStatus == ClaimPaperStatus.INSUFFICIENT_EVIDENCE) {
                    "Comparable support/contradiction outcome '${outcome.claimPaperId}' must remain INSUFFICIENT_EVIDENCE."
                }
                require(group.any { it.adjudicatedLabels.judgement in setOf(EvidenceJudgementKind.DIRECT_SUPPORT, EvidenceJudgementKind.PARTIAL_SUPPORT) } &&
                    group.any { it.adjudicatedLabels.judgement == EvidenceJudgementKind.CONTRADICTS }
                ) { "Conflict outcome '${outcome.claimPaperId}' must contain human-labelled comparable support and contradiction cases." }
            }
        }
    }

    private fun validateReleaseDataset() {
        require(protocolId.matches(APPROVED_PROTOCOL_ID_PATTERN)) {
            "A HUMAN_REVIEWED Laya dataset must pin an approved versioned annotation protocol, not a DRAFT ID."
        }
        papers.forEach(PaperProvenance::validateReleaseRights)
        cases.forEach(Case::validateHumanReview)
        claimPaperOutcomes.forEach(ClaimPaperOutcome::validateHumanReview)
        val heldOutCases = cases.filter { it.split == Split.HELD_OUT }
        val heldOutOutcomes = claimPaperOutcomes.filter { it.split == Split.HELD_OUT }
        val calibrationCases = cases.filter { it.split == Split.CALIBRATION }
        require(heldOutCases.isNotEmpty() && calibrationCases.isNotEmpty()) {
            "A HUMAN_REVIEWED Laya dataset requires both calibration and held-out Cited Papers."
        }
        val requiredCoverageTags = setOf(
            CoverageTag.SCOPE_OR_QUALIFIER_MISMATCH,
            CoverageTag.SECONDARY_REPORT,
            CoverageTag.ABSTENTION,
        )
        require(cases.flatMap(Case::coverageTags).toSet().containsAll(requiredCoverageTags) &&
            heldOutCases.flatMap(Case::coverageTags).toSet().containsAll(requiredCoverageTags)
        ) {
            "HUMAN_REVIEWED Laya calibration and held-out data must cover scope/qualifier mismatches, secondary reports, and abstention cases."
        }
        require(heldOutCases.any { CoverageTag.SCOPE_OR_QUALIFIER_MISMATCH in it.coverageTags &&
            it.adjudicatedLabels.claimScopeMatch <= SCOPE_MISMATCH_MAX_SCORE
        }) { "Held-out scope/qualifier mismatch tags must identify low human claim-scope-match scores." }
        require(heldOutCases.any { CoverageTag.SECONDARY_REPORT in it.coverageTags &&
            it.adjudicatedLabels.evidenceRole == EvidenceRole.SECONDARY_REPORT
        }) { "Held-out secondary-report tags must identify human-labelled SECONDARY_REPORT cases." }
        require(heldOutCases.any { CoverageTag.ABSTENTION in it.coverageTags &&
            it.adjudicatedLabels.judgement == EvidenceJudgementKind.INSUFFICIENT
        }) { "Held-out abstention tags must identify human-labelled INSUFFICIENT cases." }
        require(heldOutOutcomes.any(ClaimPaperOutcome::expectedConflict)) {
            "HUMAN_REVIEWED held-out data must include a comparable support/contradiction conflict outcome."
        }

        require(heldOutCases.map(Case::adjudicatedLabels).map(HumanLabels::judgement).toSet()
            .containsAll(EvidenceJudgementKind.entries)) {
            "Held-out cases must cover all five Evidence Judgement classes."
        }
        require(heldOutCases.map(Case::adjudicatedLabels).map(HumanLabels::evidenceRole).toSet()
            .containsAll(EvidenceRole.entries)) {
            "Held-out cases must cover all Evidence Roles."
        }
        require(heldOutOutcomes.map(ClaimPaperOutcome::expectedStatus).toSet().containsAll(ClaimPaperStatus.entries)) {
            "Held-out Claim–Paper outcomes must cover all four final statuses."
        }
    }

    data class Candidate(
        val checkpoint: String,
        val runtime: String,
        val outputMapping: String,
        val contextLimitTokens: Int,
        val promptVersion: String,
    ) {
        fun validate() {
            require(checkpoint == LayaSystemOneSettings.PINNED_MODEL_ID &&
                runtime == LayaSystemOneSettings.PINNED_RUNTIME_VERSION &&
                outputMapping == LayaSystemOneSettings.OUTPUT_MAPPING_VERSION &&
                contextLimitTokens == LayaSystemOneSettings.MODEL_CONTEXT_TOKENS &&
                promptVersion == LayaSystemOneSettings.PROMPT_VERSION_ID
            ) {
                "Laya evaluation dataset must pin the exact Laya checkpoint, runtime, prompt, output mapping, and context limit."
            }
        }
    }

    data class PaperProvenance(
        val citedPaperId: String,
        val citation: String,
        val sourceUrl: String,
        val licenseOrRightsBasis: String,
        val resolutionStatus: ReferenceResolutionStatus = ReferenceResolutionStatus.UNRESOLVED,
        val assetSha256: String,
        val rightsReviewedBy: String? = null,
        val rightsReviewedAt: String? = null,
    ) {
        fun validateBasic() {
            require(isStableIdentifier(citedPaperId) && citation.isNotBlank() && sourceUrl.isNotBlank()) {
                "Cited Paper provenance must identify the work, citation, and source."
            }
            val secureSource = runCatching { URI(sourceUrl) }.getOrNull()?.let { uri ->
                uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null
            } == true
            require(secureSource) { "Cited Paper source URLs must use HTTPS and must not contain credentials, query strings, or fragments." }
            require(assetSha256.matches(SHA256_PATTERN)) { "Cited Paper asset hashes must be 64-character SHA-256 values." }
        }

        fun validateReleaseRights() {
            require(resolutionStatus == ReferenceResolutionStatus.RESOLVED) {
                "Release Laya evaluation requires fully resolved Cited Papers; reference-resolution calibration is separate."
            }
            require(licenseOrRightsBasis.isNotBlank() && !rightsReviewedBy.isNullOrBlank() && !rightsReviewedAt.isNullOrBlank()) {
                "Release Cited Paper provenance must record the license/rights basis and its human reviewer/date."
            }
            require(isIsoInstant(rightsReviewedAt)) { "Cited Paper rights-review time must be an ISO-8601 instant." }
        }
    }

    data class Case(
        val caseId: String,
        val citedPaperId: String,
        val claimPaperId: String,
        val split: Split,
        val sourceLocatorId: String,
        val atomicClaim: String,
        val evidencePassage: String,
        val sourcePage: String? = null,
        val sectionHeading: String? = null,
        val adjudicatedLabels: HumanLabels,
        val independentReviews: List<ReviewAnnotation> = emptyList(),
        val adjudicatorId: String? = null,
        val adjudicatedAt: String? = null,
        val adjudicationRationale: String? = null,
        val coverageTags: Set<CoverageTag> = emptySet(),
    ) {
        fun validateBasic() {
            require(listOf(caseId, citedPaperId, claimPaperId, sourceLocatorId).all(::isStableIdentifier) &&
                listOf(atomicClaim, evidencePassage).all(String::isNotBlank)) {
                "Laya evaluation cases require stable IDs, an opaque source locator ID, an Atomic Claim, and an Evidence Passage."
            }
            require(sourcePage?.isNotBlank() != false && sectionHeading?.isNotBlank() != false &&
                (!sourcePage.isNullOrBlank() || !sectionHeading.isNullOrBlank())
            ) { "Laya evaluation cases require a page or section source location." }
            adjudicatedLabels.validate()
        }

        fun validateHumanReview() {
            require(independentReviews.isNotEmpty()) { "Release case '$caseId' requires at least one independent human review." }
            independentReviews.forEach(ReviewAnnotation::validate)
            require(independentReviews.map(ReviewAnnotation::reviewerId).distinct().size == independentReviews.size) {
                "Case '$caseId' must preserve each independent reviewer annotation separately."
            }
            require(!adjudicatorId.isNullOrBlank() && !adjudicatedAt.isNullOrBlank() && !adjudicationRationale.isNullOrBlank()) {
                "Release case '$caseId' requires a named adjudicator, time, and rationale."
            }
            require(isIsoInstant(adjudicatedAt)) { "Case adjudication time for '$caseId' must be an ISO-8601 instant." }
        }
    }

    data class HumanLabels(
        val judgement: EvidenceJudgementKind,
        val evidenceRole: EvidenceRole,
        val directness: Int,
        val claimScopeMatch: Int,
        val studyDesignQuality: Int,
        val relevance: Int,
    ) {
        fun validate() {
            require(listOf(directness, claimScopeMatch, studyDesignQuality, relevance).all { it in 0..4 }) {
                "Human ordinal evidence scores must be integers from zero to four."
            }
        }
    }

    data class ReviewAnnotation(
        val reviewerId: String,
        val reviewerQualification: String,
        val reviewedAt: String,
        val rationale: String,
        val labels: HumanLabels,
    ) {
        fun validate() {
            require(listOf(reviewerId, reviewerQualification, reviewedAt, rationale).all(String::isNotBlank)) {
                "Independent human reviews require reviewer identity/qualification, time, and rationale."
            }
            require(isIsoInstant(reviewedAt)) { "Human review time must be an ISO-8601 instant." }
            labels.validate()
        }
    }

    data class ClaimPaperOutcome(
        val claimPaperId: String,
        val citedPaperId: String,
        val split: Split,
        val expectedStatus: ClaimPaperStatus,
        val expectedConflict: Boolean,
        val adjudicatorId: String? = null,
        val adjudicatedAt: String? = null,
        val rationale: String? = null,
        val independentReviews: List<ClaimPaperReview> = emptyList(),
    ) {
        fun validateBasic() {
            require(isStableIdentifier(claimPaperId) && isStableIdentifier(citedPaperId)) {
                "Claim–Paper outcomes require non-blank Claim–Paper and Cited Paper identifiers."
            }
        }

        fun validateHumanReview() {
            require(independentReviews.isNotEmpty()) {
                "Release Claim–Paper outcome '$claimPaperId' requires at least one preserved independent review."
            }
            independentReviews.forEach(ClaimPaperReview::validate)
            require(independentReviews.map(ClaimPaperReview::reviewerId).distinct().size == independentReviews.size) {
                "Claim–Paper outcome '$claimPaperId' must preserve each independent reviewer annotation separately."
            }
            require(!adjudicatorId.isNullOrBlank() && !adjudicatedAt.isNullOrBlank() && !rationale.isNullOrBlank()) {
                "Release Claim–Paper outcome '$claimPaperId' requires a named adjudicator, time, and rationale."
            }
            require(isIsoInstant(adjudicatedAt)) {
                "Claim–Paper adjudication time for '$claimPaperId' must be an ISO-8601 instant."
            }
        }
    }

    data class ClaimPaperReview(
        val reviewerId: String,
        val reviewerQualification: String,
        val reviewedAt: String,
        val rationale: String,
        val expectedStatus: ClaimPaperStatus,
        val expectedConflict: Boolean,
    ) {
        fun validate() {
            require(listOf(reviewerId, reviewerQualification, reviewedAt, rationale).all(String::isNotBlank)) {
                "Claim–Paper reviews require reviewer identity/qualification, time, and rationale."
            }
            require(isIsoInstant(reviewedAt)) { "Claim–Paper review time must be an ISO-8601 instant." }
        }
    }

    data class AggregationCandidate(
        val candidateId: String,
        val verificationPolicyVersion: String,
        val aggregationPolicyVersion: String,
        val thresholds: EvidenceAggregationThresholds,
    ) {
        fun validate() {
            require(isStableIdentifier(candidateId)) { "Aggregation candidate ID must be a stable non-text identifier." }
            require(verificationPolicyVersion == EvidenceJudgement.STRENGTH_RUBRIC_VERSION) {
                "Unsupported evidence-strength rubric '$verificationPolicyVersion'."
            }
            require(aggregationPolicyVersion == EvidenceAggregationPolicy.POLICY_VERSION) {
                "Unsupported evidence aggregation policy '$aggregationPolicyVersion'."
            }
        }
    }

    enum class DatasetStatus { DRAFT, HUMAN_REVIEWED }
    enum class Split { CALIBRATION, HELD_OUT }
    enum class CoverageTag { SCOPE_OR_QUALIFIER_MISMATCH, SECONDARY_REPORT, ABSTENTION }
    enum class ClaimPaperStatus { SUPPORTED, PARTIALLY_SUPPORTED, CONTRADICTED, INSUFFICIENT_EVIDENCE }

    private data class HeldOutManifest(
        val schemaVersion: Int,
        val datasetId: String,
        val datasetVersion: String,
        val protocolId: String,
        val candidate: Candidate,
        val papers: List<PaperProvenance>,
        val cases: List<Case>,
        val claimPaperOutcomes: List<ClaimPaperOutcome>,
        val aggregationCandidates: List<AggregationCandidate>,
    )

    companion object {
        const val CURRENT_SCHEMA_VERSION = 1
        const val UNCERTAINTY_METHOD_ID = "cited-paper-cluster-bootstrap-p95-v1"
        private const val SCOPE_MISMATCH_MAX_SCORE = 1
        private val SHA256_PATTERN = Regex("[0-9a-fA-F]{64}")
        private val APPROVED_PROTOCOL_ID_PATTERN = Regex("laya-human-calibration-v[1-9][0-9]*")
        private val STABLE_IDENTIFIER_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val FINGERPRINT_MAPPER = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build()

        private fun isStableIdentifier(value: String): Boolean = value.matches(STABLE_IDENTIFIER_PATTERN)

        private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private fun isIsoInstant(value: String?): Boolean = try {
            Instant.parse(value)
            true
        } catch (_: Exception) {
            false
        }
    }
}
