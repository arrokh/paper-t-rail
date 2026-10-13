package com.papertrail.api.analysis.recovery.http

import com.papertrail.api.analysis.recovery.domain.RecoveryUploadMetadataCandidateFingerprint
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataCandidate
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataExtractionMethod
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataField
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "A bounded extracted metadata candidate with source location. It is not itself an identity decision.")
data class RecoveryUploadMetadataCandidateResponse(
    val field: ParsedBibliographicMetadataField,
    val value: String,
    val pageNumber: Int,
    val sourceLabel: String,
    val extractionMethod: ParsedBibliographicMetadataExtractionMethod,
    val sourceElementId: String?,
    val sourceCharSpanStart: Int?,
    val sourceCharSpanEnd: Int?,
    @field:Schema(description = "Versioned fingerprint binding this exact candidate value to its parser and source-location provenance; send it when explicitly confirming a DOI candidate.", pattern = "^recovery-metadata-candidate-v1:[0-9a-f]{64}$")
    val candidateFingerprint: String,
    @field:Schema(description = "Canonical DOI value when this candidate is a DOI; null for other fields or invalid DOI text.", nullable = true)
    val normalizedDoi: String?,
) {
    companion object {
        fun from(candidate: ParsedBibliographicMetadataCandidate) = RecoveryUploadMetadataCandidateResponse(
            field = candidate.field,
            value = candidate.value,
            pageNumber = candidate.pageNumber,
            sourceLabel = candidate.sourceLabel,
            extractionMethod = candidate.extractionMethod,
            sourceElementId = candidate.sourceElementId,
            sourceCharSpanStart = candidate.sourceCharSpanStart,
            sourceCharSpanEnd = candidate.sourceCharSpanEnd,
            candidateFingerprint = RecoveryUploadMetadataCandidateFingerprint.of(candidate),
            normalizedDoi = if (candidate.field == ParsedBibliographicMetadataField.DOI) DoiNormalizer.normalize(candidate.value) else null,
        )
    }
}
