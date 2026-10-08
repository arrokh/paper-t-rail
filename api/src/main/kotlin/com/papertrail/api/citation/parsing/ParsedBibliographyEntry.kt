package com.papertrail.api.citation.parsing

data class ParsedBibliographyEntry(
    val entryOrder: Int,
    val localReferenceKey: String,
    val rawText: String,
    val title: String?,
    val authors: List<String>,
    val year: Int?,
    val doi: String?,
    val referenceType: String,
    val sourceElement: String? = null,
    val sourceLocalReferenceKey: String? = null,
    val localReferenceKeyOrigin: String = "UNKNOWN",
    val identifiers: List<ParsedBibliographyIdentifier> = emptyList(),
    val sourceLocations: List<ParsedBibliographySourceLocation> = emptyList(),
    val provisionalArtifactSignals: List<String> = emptyList(),
    val extractionLimitations: List<String> = emptyList(),
    val provenanceCaptured: Boolean = false,
    val sourceTextContent: String? = null,
)
