package com.papertrail.api.scholarly.acquisition.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class UserSuppliedCitedPaperProvenanceTest {
    @Test
    fun `accepts provenance only for a SHA-256 identified PDF with a declaration snapshot`() {
        val provenance = provenance()

        assertEquals("application/pdf", provenance.mediaType)
        assertEquals("a".repeat(64), provenance.contentSha256)
    }

    @Test
    fun `rejects incomplete or non-PDF provenance`() {
        assertThrows(IllegalArgumentException::class.java) {
            provenance(contentSha256 = "not-a-hash")
        }
        assertThrows(IllegalArgumentException::class.java) {
            provenance(mediaType = "text/plain")
        }
        assertThrows(IllegalArgumentException::class.java) {
            provenance(rightsDeclarationText = " ")
        }
    }

    private fun provenance(
        contentSha256: String = "a".repeat(64),
        mediaType: String = "application/pdf",
        rightsDeclarationText: String = "I may use this copy for private analysis.",
    ) = UserSuppliedCitedPaperProvenance(
        contentSha256 = contentSha256,
        mediaType = mediaType,
        language = "en",
        languageDetectorVersion = "0.6",
        rightsDeclarationVersion = "recovery-v1",
        rightsDeclarationText = rightsDeclarationText,
        rightsDeclaredAt = Instant.parse("2025-01-01T00:00:00Z"),
        originatingAnalysisRunId = UUID.randomUUID(),
        originatingRecoveryBatchId = UUID.randomUUID(),
        originatingRecoveryUploadId = UUID.randomUUID(),
    )
}
