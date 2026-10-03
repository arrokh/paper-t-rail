package com.papertrail.api.citation.claims.domain

/** Identifies one GROBID-derived Citation Target within a request, without exposing a database ID. */
data class CitationTargetKey(
    val occurrenceOrdinal: Int,
    val bibliographyReferenceKey: String,
) {
    init {
        require(occurrenceOrdinal >= 0) { "Citation Target occurrence ordinals must be non-negative." }
        require(bibliographyReferenceKey.isNotBlank() && bibliographyReferenceKey.length <= MAX_REFERENCE_KEY_LENGTH) {
            "Citation Target bibliography keys must contain between one and 256 characters."
        }
        require(bibliographyReferenceKey.none(Char::isISOControl)) { "Citation Target bibliography keys must not contain control characters." }
    }

    val value: String get() = "occurrence-$occurrenceOrdinal:$bibliographyReferenceKey"

    companion object {
        private const val MAX_REFERENCE_KEY_LENGTH = 256
    }
}
