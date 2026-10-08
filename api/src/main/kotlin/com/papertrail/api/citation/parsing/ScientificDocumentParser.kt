package com.papertrail.api.citation.parsing

interface ScientificDocumentParser {
    fun parse(pdf: ByteArray): ParsedScientificDocument = parse(
        pdf,
        BibliographyNormalizationPolicySelection.CURRENT,
    )

    fun parse(
        pdf: ByteArray,
        bibliographyNormalizationPolicy: BibliographyNormalizationPolicySelection,
    ): ParsedScientificDocument
}
