package com.papertrail.api.analysis.recovery.domain

import com.papertrail.api.citation.parsing.BibliographyNormalizationPolicySelection
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataCandidate
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.identity.CitedWorkIdentityOutcome
import com.papertrail.api.scholarly.references.identity.CitedWorkIdentityPolicy

object RecoveryUploadIdentityPolicy {
    const val VERSION = CitedWorkIdentityPolicy.VERSION

    fun evaluate(
        referenceType: String,
        bibliographyPolicy: BibliographyNormalizationPolicySelection?,
        reference: BibliographyReference?,
        candidates: List<ParsedBibliographicMetadataCandidate>,
    ): Pair<RecoveryIdentityOutcome, String> {
        val decision = CitedWorkIdentityPolicy.evaluate(
            referenceType = referenceType,
            bibliographyPolicy = bibliographyPolicy,
            reference = reference,
            candidates = candidates,
        )
        val outcome = when (decision.outcome) {
            CitedWorkIdentityOutcome.VALIDATED -> RecoveryIdentityOutcome.VALIDATED
            CitedWorkIdentityOutcome.NEEDS_CONFIRMATION -> RecoveryIdentityOutcome.NEEDS_CONFIRMATION
            CitedWorkIdentityOutcome.MISMATCH -> RecoveryIdentityOutcome.MISMATCH
        }
        return outcome to decision.reasonCode
    }
}
