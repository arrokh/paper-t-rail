package com.papertrail.api.analysis.http

import com.papertrail.api.citation.parsing.ParsedBibliographyIdentifier
import com.papertrail.api.citation.parsing.ParsedBibliographySourceLocation
import io.swagger.v3.oas.annotations.media.Schema

data class ParsedBibliographyEntryView(
    val entryOrder: Int,
    val localReferenceKey: String,
    val rawText: String,
    val title: String?,
    val authors: List<String>,
    val year: Int?,
    val doi: String?,
    @field:Schema(description = "GROBID-derived reference type. Under bibliography normalization policy v3, an analytic work inside a monograph is BOOK_CHAPTER and a monograph without an analytic work is BOOK; earlier policy versions may retain BOOK for either form.")
    val referenceType: String,
    val resolutionStatus: String,
    @field:Schema(description = "Unnormalized element text content extracted from GROBID TEI; raw TEI bytes are stored separately.")
    val sourceTextContent: String? = null,
    @field:Schema(description = "GROBID bibliography element, such as bibl or biblStruct; null when older storage did not retain it.")
    val sourceElement: String? = null,
    @field:Schema(description = "Original TEI xml:id. Null means no source ID was captured or the entry used a generated internal key.")
    val sourceLocalReferenceKey: String? = null,
    @field:Schema(description = "Whether the local key came from GROBID's xml:id, was generated internally, or is unknown for legacy rows.", allowableValues = ["GROBID_XML_ID", "GENERATED_FALLBACK", "UNKNOWN"])
    val localReferenceKeyOrigin: String = "UNKNOWN",
    @field:Schema(description = "Raw TEI identifiers and URL targets plus any local normalization. Preserving them does not trigger provider requests.")
    val identifiers: List<ParsedBibliographyIdentifier> = emptyList(),
    @field:Schema(description = "GROBID page/coordinate locations when supplied; these are not source-text offsets.")
    val sourceLocations: List<ParsedBibliographySourceLocation> = emptyList(),
    @field:Schema(description = "Provisional rule signals only. They are not human adjudications and do not remove the Bibliography Entry or Citation Target.")
    val provisionalArtifactSignals: List<String> = emptyList(),
    @field:Schema(description = "Known extraction limits, including unavailable source page or text-span provenance.")
    val extractionLimitations: List<String> = emptyList(),
    @field:Schema(description = "Whether bibliography-level extraction metadata was captured for this row; legacy rows report UNAVAILABLE rather than inferred values.", allowableValues = ["CAPTURED", "UNAVAILABLE"])
    val provenanceCaptureStatus: String = "UNAVAILABLE",
)
