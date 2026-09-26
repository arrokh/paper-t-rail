package com.papertrail.api.scholarly.acquisition.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CitedPaperAccessPolicyTest {
    private val policy = CitedPaperAccessPolicy()

    @Test
    fun `English legal full text is the only access outcome eligible for full text verification`() {
        val decision = policy.decide(
            metadataAvailable = true,
            abstractAvailable = true,
            fullTextAvailable = true,
            language = "en",
        )

        assertEquals(CitedPaperAccessStatus.FULL_TEXT_AVAILABLE, decision.accessStatus)
        assertEquals(VerificationScope.FULL_TEXT, decision.verificationScope)
        assertEquals(null, decision.finalVerificationStatus)
        assertEquals(null, decision.terminalReason)
    }

    @Test
    fun `abstract-only access is insufficient evidence and not a semantic assessment`() {
        val decision = policy.decide(
            metadataAvailable = true,
            abstractAvailable = true,
            fullTextAvailable = false,
            language = null,
        )

        assertEquals(CitedPaperAccessStatus.ABSTRACT_ONLY, decision.accessStatus)
        assertEquals(VerificationScope.ABSTRACT_ONLY, decision.verificationScope)
        assertEquals(TerminalVerificationStatus.INSUFFICIENT_EVIDENCE, decision.finalVerificationStatus)
        assertEquals(CitedPaperAccessPolicy.ABSTRACT_ONLY, decision.terminalReason)
    }

    @Test
    fun `missing full text and abstract is inaccessible regardless of metadata availability`() {
        val decisions = listOf(true, false).map { metadataAvailable ->
            policy.decide(metadataAvailable, abstractAvailable = false, fullTextAvailable = false, language = null)
        }

        assertEquals(
            listOf(CitedPaperAccessStatus.METADATA_ONLY, CitedPaperAccessStatus.UNAVAILABLE),
            decisions.map(CitedPaperAccessDecision::accessStatus),
        )
        assertEquals(List(2) { TerminalVerificationStatus.INACCESSIBLE }, decisions.map(CitedPaperAccessDecision::finalVerificationStatus))
        assertEquals(List(2) { VerificationScope.NONE }, decisions.map(CitedPaperAccessDecision::verificationScope))
    }

    @Test
    fun `accessible non-English full text is insufficient and cannot use the full text verification scope`() {
        val decision = policy.decide(
            metadataAvailable = true,
            abstractAvailable = true,
            fullTextAvailable = true,
            language = "fr",
        )

        assertEquals(CitedPaperAccessStatus.FULL_TEXT_AVAILABLE, decision.accessStatus)
        assertEquals(VerificationScope.NONE, decision.verificationScope)
        assertEquals(TerminalVerificationStatus.INSUFFICIENT_EVIDENCE, decision.finalVerificationStatus)
        assertEquals(CitedPaperAccessPolicy.LANGUAGE_UNSUPPORTED, decision.terminalReason)
    }
}
