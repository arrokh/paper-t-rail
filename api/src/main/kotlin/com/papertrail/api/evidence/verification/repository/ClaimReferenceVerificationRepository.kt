package com.papertrail.api.evidence.verification.repository

import com.papertrail.api.evidence.verification.domain.EvidenceAggregationDecision
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessDecision
import com.papertrail.api.scholarly.references.resolver.ReferenceResolutionStatus
import java.util.UUID

interface ClaimReferenceVerificationRepository {
    fun initializeExpectedPairs(analysisRunId: UUID)

    fun applyResolution(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        status: ReferenceResolutionStatus,
        reason: String,
        canonicalPaperId: UUID?,
    )

    fun applyAccessDecision(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        canonicalPaperId: UUID,
        decision: CitedPaperAccessDecision,
    )

    fun isCompleted(verificationId: UUID): Boolean

    fun complete(
        verificationId: UUID,
        decision: EvidenceAggregationDecision,
        aggregatorVersion: String,
    ): Boolean

    fun failReference(analysisRunId: UUID, bibliographyEntryId: UUID, reason: String)

    fun failFullText(analysisRunId: UUID, bibliographyEntryId: UUID, reason: String)
}
