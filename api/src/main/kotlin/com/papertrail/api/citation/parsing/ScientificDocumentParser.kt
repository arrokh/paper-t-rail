package com.papertrail.api.citation.parsing

interface ScientificDocumentParser {
    fun parse(pdf: ByteArray): ParsedScientificDocument
}
