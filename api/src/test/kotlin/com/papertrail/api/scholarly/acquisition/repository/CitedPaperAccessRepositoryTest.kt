package com.papertrail.api.scholarly.acquisition.repository

import com.papertrail.api.scholarly.acquisition.domain.UserSuppliedCitedPaperFullText
import com.papertrail.api.scholarly.acquisition.domain.UserSuppliedCitedPaperProvenance
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

class CitedPaperAccessRepositoryTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val repository = CitedPaperAccessRepository(jdbc)

    @Test
    fun `rejects supplied bytes that do not match their declared hash before persistence`() {
        assertThrows(IllegalArgumentException::class.java) {
            saveUserSupplied(
                objectKey = "synthetic/object.pdf",
                bytes = "synthetic PDF bytes".toByteArray(),
            )
        }
        Mockito.verifyNoInteractions(jdbc)
    }

    @Test
    fun `rejects a blank supplied object key before persistence`() {
        val bytes = "synthetic PDF bytes".toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            saveUserSupplied(
                objectKey = "  ",
                bytes = bytes,
                contentSha256 = sha256(bytes),
            )
        }
        Mockito.verifyNoInteractions(jdbc)
    }

    private fun saveUserSupplied(
        objectKey: String,
        bytes: ByteArray,
        contentSha256: String = "a".repeat(64),
    ) = repository.saveUserSupplied(
        analysisRunId = UUID.randomUUID(),
        reference = ResolvedCitedReference(
            bibliographyEntryId = UUID.randomUUID(),
            title = "Synthetic reference",
            authors = emptyList(),
            year = null,
            doi = null,
            referenceType = "JOURNAL_ARTICLE",
            canonicalPaperId = UUID.randomUUID(),
        ),
        objectKey = objectKey,
        fullText = UserSuppliedCitedPaperFullText(
            bytes = bytes,
            mediaType = "application/pdf",
        ),
        provenance = UserSuppliedCitedPaperProvenance(
            contentSha256 = contentSha256,
            mediaType = "application/pdf",
            language = "en",
            languageDetectorVersion = "fixture-detector",
            rightsDeclarationVersion = "fixture-v1",
            rightsDeclarationText = "Synthetic private-use declaration",
            rightsDeclaredAt = Instant.parse("2025-01-01T00:00:00Z"),
            originatingAnalysisRunId = UUID.randomUUID(),
            originatingRecoveryBatchId = UUID.randomUUID(),
            originatingRecoveryUploadId = UUID.randomUUID(),
        ),
        discoveredAt = Instant.parse("2025-01-01T00:00:00Z"),
    )

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}
