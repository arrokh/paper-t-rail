package com.papertrail.api.evidence.parsing

import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.citation.parsing.ParsedScientificDocument

interface CitedPaperParser {
    fun parse(content: ByteArray, mediaType: String, parserSelection: ProviderSelection): ParsedScientificDocument
}
