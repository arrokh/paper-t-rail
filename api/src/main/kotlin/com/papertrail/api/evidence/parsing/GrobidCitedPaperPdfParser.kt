package com.papertrail.api.evidence.parsing

import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.citation.parsing.ScientificDocumentParser
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class GrobidCitedPaperPdfParser(
    private val scientificDocumentParser: ScientificDocumentParser,
    @Value("\${paper-trail.analysis.parser-id}") override val parserId: String,
) : CitedPaperPdfParser {
    override fun parse(pdf: ByteArray): ParsedScientificDocument = scientificDocumentParser.parse(pdf)
}
