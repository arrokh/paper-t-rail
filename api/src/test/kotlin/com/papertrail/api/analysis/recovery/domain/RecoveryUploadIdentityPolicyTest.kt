package com.papertrail.api.analysis.recovery.domain

import com.papertrail.api.citation.parsing.BibliographyNormalizationPolicySelection
import com.papertrail.api.scholarly.references.client.BibliographyReference
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RecoveryUploadIdentityPolicyTest {
    @Test
    fun `blocks inconclusive book identity when the pinned parser policy cannot distinguish chapters`() {
        val result = RecoveryUploadIdentityPolicy.evaluate(
            referenceType = "BOOK",
            bibliographyPolicy = BibliographyNormalizationPolicySelection.VERSION_2,
            reference = resolvedReference("A cited chapter"),
            candidates = emptyList(),
        )

        assertEquals(RecoveryIdentityOutcome.MISMATCH, result.first)
        assertEquals("BOOK_CHAPTER_IDENTITY_UNSUPPORTED", result.second)
    }

    @Test
    fun `keeps inconclusive monograph identity confirmable under chapter-aware parsing`() {
        val result = RecoveryUploadIdentityPolicy.evaluate(
            referenceType = "BOOK",
            bibliographyPolicy = BibliographyNormalizationPolicySelection.CURRENT,
            reference = resolvedReference("A cited book"),
            candidates = emptyList(),
        )

        assertEquals(RecoveryIdentityOutcome.NEEDS_CONFIRMATION, result.first)
        assertEquals("DOI_NOT_EXTRACTED", result.second)
    }

    @Test
    fun `never lets an inconclusive identified chapter be human-confirmed`() {
        val result = RecoveryUploadIdentityPolicy.evaluate(
            referenceType = "BOOK_CHAPTER",
            bibliographyPolicy = BibliographyNormalizationPolicySelection.CURRENT,
            reference = resolvedReference("A cited chapter"),
            candidates = emptyList(),
        )

        assertEquals(RecoveryIdentityOutcome.MISMATCH, result.first)
        assertEquals("CHAPTER_IDENTITY_UNSUPPORTED", result.second)
    }

    private fun resolvedReference(title: String) = BibliographyReference(
        title = title,
        authors = listOf("A. Author"),
        year = 2024,
        doi = "10.1234/work",
        referenceType = "BOOK",
    )
}
