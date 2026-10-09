package com.papertrail.api.scholarly.acquisition.service

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.scholarly.references.identity.CitedWorkIdentityOutcome
import com.papertrail.api.scholarly.references.identity.CitedWorkIdentityPolicy
import com.papertrail.api.evidence.parsing.CitedPaperPdfParser
import com.papertrail.api.external.docling.DoclingCitedPaperPdfParser
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessCause.FULL_TEXT_IDENTITY_VALIDATION_FAILED
import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText
import com.papertrail.api.scholarly.acquisition.repository.ResolvedCitedReference
import com.papertrail.api.scholarly.references.client.BibliographyReference
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Service
class DoclingCitedPaperIdentityValidator(
    @Qualifier("doclingCitedPaperPdfParser") private val parser: CitedPaperPdfParser,
) : CitedPaperIdentityValidator {
    override fun validate(
        fullText: AcquiredFullText,
        reference: ResolvedCitedReference,
        configuration: AnalysisConfigurationSnapshot,
    ): CitedPaperIdentityValidationResult {
        if (!fullText.mediaType.substringBefore(';').trim().equals(APPLICATION_PDF, ignoreCase = true)) {
            return CitedPaperIdentityValidationResult(
                status = CitedPaperIdentityValidationStatus.NEEDS_CONFIRMATION,
                reasonCode = "PDF_METADATA_CANDIDATES_UNAVAILABLE",
            )
        }

        val parserSelection = configuration.citedPaperParserSelection()
        if (parserSelection.provider != parser.parserId) {
            return failed("PINNED_CITED_PAPER_PARSER_UNAVAILABLE")
        }
        val parsed = try {
            parser.parse(fullText.bytes)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        } catch (_: Exception) {
            return failed("DOCLING_PARSE_FAILED")
        }
        val parserOptions = DoclingCitedPaperPdfParser.BIBLIOGRAPHIC_METADATA_EXTRACTION_OPTIONS
        if (parsed.parserId != parserSelection.provider ||
            parsed.parserVersion != parserSelection.version ||
            parsed.bibliographicMetadataExtractionPolicyVersion != DoclingCitedPaperPdfParser.METADATA_EXTRACTION_POLICY_VERSION ||
            parsed.bibliographicMetadataExtractionOptions != parserOptions
        ) {
            return failed("PARSER_PROVENANCE_MISMATCH")
        }

        val identity = CitedWorkIdentityPolicy.evaluate(
            referenceType = reference.referenceType,
            bibliographyPolicy = configuration.bibliographyNormalizationPolicy,
            reference = BibliographyReference(
                title = reference.title,
                authors = reference.authors,
                year = reference.year,
                doi = reference.doi,
                referenceType = reference.referenceType,
            ),
            candidates = parsed.bibliographicMetadataCandidates,
        )
        val status = when (identity.outcome) {
            CitedWorkIdentityOutcome.VALIDATED -> CitedPaperIdentityValidationStatus.VALIDATED
            CitedWorkIdentityOutcome.NEEDS_CONFIRMATION -> CitedPaperIdentityValidationStatus.NEEDS_CONFIRMATION
            CitedWorkIdentityOutcome.MISMATCH -> CitedPaperIdentityValidationStatus.MISMATCH
        }
        return CitedPaperIdentityValidationResult(status, identity.reasonCode)
    }

    private fun failed(reasonCode: String) = CitedPaperIdentityValidationResult(
        status = CitedPaperIdentityValidationStatus.FAILED,
        reasonCode = reasonCode,
        failureCause = FULL_TEXT_IDENTITY_VALIDATION_FAILED,
    )

    companion object {
        private const val APPLICATION_PDF = "application/pdf"
    }
}
