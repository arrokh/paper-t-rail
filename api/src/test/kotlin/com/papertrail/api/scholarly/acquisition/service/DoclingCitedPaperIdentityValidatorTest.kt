package com.papertrail.api.scholarly.acquisition.service

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.citation.parsing.BibliographyNormalizationPolicySelection
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataCandidate
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataExtractionMethod
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataField
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.evidence.parsing.CitedPaperPdfParser
import com.papertrail.api.external.docling.DoclingCitedPaperPdfParser
import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessCause
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessLocation
import com.papertrail.api.scholarly.acquisition.repository.ResolvedCitedReference
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DoclingCitedPaperIdentityValidatorTest {
    @Test
    fun `validates a PDF only when the pinned parser returns the expected DOI`() {
        val validator = validator(parsedDocument(candidates = listOf(
            candidate(ParsedBibliographicMetadataField.DOI, "10.1234/expected"),
        )))

        val result = validator.validate(pdf(), reference(), configuration())

        assertEquals(CitedPaperIdentityValidationStatus.VALIDATED, result.status)
        assertEquals("DOI_MATCH", result.reasonCode)
        assertNull(result.accessCause())
    }

    @Test
    fun `preserves a title conflict as a non-overridable automatic mismatch`() {
        val validator = validator(parsedDocument(candidates = listOf(
            candidate(ParsedBibliographicMetadataField.DOI, "10.1234/other"),
            candidate(ParsedBibliographicMetadataField.TITLE, "A different work"),
        )))

        val result = validator.validate(pdf(), reference(), configuration())

        assertEquals(CitedPaperIdentityValidationStatus.MISMATCH, result.status)
        assertEquals("DOI_TITLE_CONFLICT", result.reasonCode)
        assertEquals(CitedPaperAccessCause.FULL_TEXT_IDENTITY_MISMATCH, result.accessCause())
    }

    @Test
    fun `does not treat title and positional author candidates alone as identity proof`() {
        val validator = validator(parsedDocument(candidates = listOf(
            candidate(ParsedBibliographicMetadataField.TITLE, "The expected work"),
            candidate(ParsedBibliographicMetadataField.AUTHORS, "A. Author"),
        )))

        val result = validator.validate(pdf(), reference(), configuration())

        assertEquals(CitedPaperIdentityValidationStatus.NEEDS_CONFIRMATION, result.status)
        assertEquals("DOI_NOT_EXTRACTED", result.reasonCode)
        assertEquals(CitedPaperAccessCause.FULL_TEXT_IDENTITY_UNVERIFIED, result.accessCause())
    }

    @Test
    fun `requires confirmation when a page produces multiple DOI candidates`() {
        val validator = validator(parsedDocument(candidates = listOf(
            candidate(ParsedBibliographicMetadataField.DOI, "10.1371/journal.pcbi.1013666"),
            candidate(ParsedBibliographicMetadataField.DOI, "10.1371/journal"),
            candidate(ParsedBibliographicMetadataField.TITLE, "Inferring pathway activity from single-cell and spatial transcriptomics data with PaaSc"),
        )))
        val reference = reference().copy(
            title = "Inferring pathway activity from single-cell and spatial transcriptomics data with PaaSc",
            doi = "10.1371/journal.pcbi.1013666",
        )

        val result = validator.validate(pdf(), reference, configuration())

        assertEquals(CitedPaperIdentityValidationStatus.NEEDS_CONFIRMATION, result.status)
        assertEquals("MULTIPLE_DOI_CANDIDATES", result.reasonCode)
        assertEquals(CitedPaperAccessCause.FULL_TEXT_IDENTITY_UNVERIFIED, result.accessCause())
    }

    @Test
    fun `accepts a differing DOI only when the extracted title exactly matches`() {
        val validator = validator(parsedDocument(candidates = listOf(
            candidate(ParsedBibliographicMetadataField.DOI, "10.1234/alternate-version"),
            candidate(ParsedBibliographicMetadataField.TITLE, "The expected work"),
        )))

        val result = validator.validate(pdf(), reference(), configuration())

        assertEquals(CitedPaperIdentityValidationStatus.VALIDATED, result.status)
        assertEquals("TITLE_MATCH_DIFFERENT_DOI", result.reasonCode)
        assertNull(result.accessCause())
    }

    @Test
    fun `blocks an inconclusive book chapter from automatic identity validation`() {
        val validator = validator(parsedDocument(candidates = emptyList()))

        val result = validator.validate(
            pdf(),
            reference().copy(referenceType = "BOOK_CHAPTER"),
            configuration(),
        )

        assertEquals(CitedPaperIdentityValidationStatus.MISMATCH, result.status)
        assertEquals("CHAPTER_IDENTITY_UNSUPPORTED", result.reasonCode)
        assertEquals(CitedPaperAccessCause.FULL_TEXT_IDENTITY_MISMATCH, result.accessCause())
    }

    @Test
    fun `blocks an inconclusive legacy book whose parser policy could not distinguish chapters`() {
        val validator = validator(parsedDocument(candidates = emptyList()))

        val result = validator.validate(
            pdf(),
            reference().copy(referenceType = "BOOK"),
            configuration().copy(bibliographyNormalizationPolicy = BibliographyNormalizationPolicySelection.VERSION_2),
        )

        assertEquals(CitedPaperIdentityValidationStatus.MISMATCH, result.status)
        assertEquals("BOOK_CHAPTER_IDENTITY_UNSUPPORTED", result.reasonCode)
        assertEquals(CitedPaperAccessCause.FULL_TEXT_IDENTITY_MISMATCH, result.accessCause())
    }

    @Test
    fun `fails closed when the parse does not match the pinned parser provenance`() {
        val validator = validator(parsedDocument(parserVersion = "old-parser-version", candidates = listOf(
            candidate(ParsedBibliographicMetadataField.DOI, "10.1234/expected"),
        )))

        val result = validator.validate(pdf(), reference(), configuration())

        assertEquals(CitedPaperIdentityValidationStatus.FAILED, result.status)
        assertEquals("PARSER_PROVENANCE_MISMATCH", result.reasonCode)
        assertEquals(CitedPaperAccessCause.FULL_TEXT_IDENTITY_VALIDATION_FAILED, result.accessCause())
    }

    @Test
    fun `does not treat extracted text as an identity-validated PDF`() {
        var parseCalls = 0
        val parser = object : CitedPaperPdfParser {
            override val parserId = DoclingCitedPaperPdfParser.PARSER_ID
            override fun parse(pdf: ByteArray): ParsedScientificDocument {
                parseCalls++
                return parsedDocument(candidates = emptyList())
            }
        }
        val validator = DoclingCitedPaperIdentityValidator(parser)

        val result = validator.validate(pdf().copy(mediaType = "text/plain"), reference(), configuration())

        assertEquals(CitedPaperIdentityValidationStatus.NEEDS_CONFIRMATION, result.status)
        assertEquals("PDF_METADATA_CANDIDATES_UNAVAILABLE", result.reasonCode)
        assertEquals(CitedPaperAccessCause.FULL_TEXT_IDENTITY_UNVERIFIED, result.accessCause())
        assertEquals(0, parseCalls)
    }

    private fun validator(parsed: ParsedScientificDocument) = DoclingCitedPaperIdentityValidator(
        object : CitedPaperPdfParser {
            override val parserId = DoclingCitedPaperPdfParser.PARSER_ID
            override fun parse(pdf: ByteArray): ParsedScientificDocument = parsed
        },
    )

    private fun parsedDocument(
        parserVersion: String = PARSER_VERSION,
        candidates: List<ParsedBibliographicMetadataCandidate>,
    ) = ParsedScientificDocument(
        parserId = DoclingCitedPaperPdfParser.PARSER_ID,
        parserVersion = parserVersion,
        normalizedSourceText = "",
        sections = emptyList(),
        citationContexts = emptyList(),
        bibliographyEntries = emptyList(),
        bibliographicMetadataCandidates = candidates,
        bibliographicMetadataExtractionPolicyVersion = DoclingCitedPaperPdfParser.METADATA_EXTRACTION_POLICY_VERSION,
        bibliographicMetadataExtractionOptions = DoclingCitedPaperPdfParser.BIBLIOGRAPHIC_METADATA_EXTRACTION_OPTIONS,
    )

    private fun candidate(field: ParsedBibliographicMetadataField, value: String) = ParsedBibliographicMetadataCandidate(
        field = field,
        value = value,
        pageNumber = 1,
        sourceLabel = field.name.lowercase(),
        extractionMethod = if (field == ParsedBibliographicMetadataField.DOI) {
            ParsedBibliographicMetadataExtractionMethod.EXPLICIT_DOI_PREFIX
        } else {
            ParsedBibliographicMetadataExtractionMethod.DOCLING_LABEL
        },
    )

    private fun reference() = ResolvedCitedReference(
        bibliographyEntryId = UUID.randomUUID(),
        title = "The expected work",
        authors = listOf("A. Author"),
        year = 2025,
        doi = "10.1234/expected",
        referenceType = "ARTICLE",
        canonicalPaperId = UUID.randomUUID(),
    )

    private fun pdf() = AcquiredFullText(
        bytes = "%PDF-1.7".toByteArray(),
        mediaType = "application/pdf",
        location = OpenAccessLocation(
            url = "https://example.invalid/work.pdf",
            license = "CC-BY-4.0",
            version = "publishedVersion",
            hostType = "repository",
            providerId = "test",
        ),
    )

    private fun configuration() = AnalysisConfigurationSnapshot(
        captureExecution = false,
        claimExtractor = ProviderSelection("heuristic", "v1"),
        embedding = ProviderSelection("feature-hash", "v1"),
        systemOne = ProviderSelection("mock", "v1"),
        sourceParser = ProviderSelection("grobid", "1.0"),
        languageDetector = ProviderSelection("optimaize", "1.0"),
        validationLimits = ValidationLimitsSnapshot(1, 1, 1, 1, 1, 0.5),
        externalProviderConsents = emptyList(),
        citedPaperParser = ProviderSelection(DoclingCitedPaperPdfParser.PARSER_ID, PARSER_VERSION),
        bibliographyNormalizationPolicy = BibliographyNormalizationPolicySelection.CURRENT,
    )

    companion object {
        private const val PARSER_VERSION = "1.30.0"
    }
}
