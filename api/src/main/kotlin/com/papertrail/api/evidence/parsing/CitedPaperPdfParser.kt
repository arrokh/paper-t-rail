package com.papertrail.api.evidence.parsing

import com.papertrail.api.citation.parsing.ParsedScientificDocument

interface CitedPaperPdfParser {
    val parserId: String

    fun parse(pdf: ByteArray): ParsedScientificDocument
}
