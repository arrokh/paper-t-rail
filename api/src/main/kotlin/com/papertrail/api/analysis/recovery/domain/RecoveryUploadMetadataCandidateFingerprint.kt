package com.papertrail.api.analysis.recovery.domain

import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataCandidate
import com.papertrail.api.infrastructure.crypto.sha256Hex
import java.nio.charset.StandardCharsets

object RecoveryUploadMetadataCandidateFingerprint {
    const val VERSION = "recovery-metadata-candidate-v1"

    fun of(candidate: ParsedBibliographicMetadataCandidate): String {
        val canonical = buildString {
            append(VERSION).append('\n')
            appendField(candidate.field.name)
            appendField(candidate.value)
            appendField(candidate.pageNumber.toString())
            appendField(candidate.sourceLabel)
            appendField(candidate.extractionMethod.name)
            appendField(candidate.sourceElementId)
            appendField(candidate.sourceCharSpanStart?.toString())
            appendField(candidate.sourceCharSpanEnd?.toString())
        }
        return "$VERSION:${sha256Hex(canonical.toByteArray(StandardCharsets.UTF_8))}"
    }

    private fun StringBuilder.appendField(value: String?) {
        if (value == null) {
            append("-1:")
        } else {
            append(value.toByteArray(StandardCharsets.UTF_8).size).append(':').append(value)
        }
        append('\n')
    }
}
