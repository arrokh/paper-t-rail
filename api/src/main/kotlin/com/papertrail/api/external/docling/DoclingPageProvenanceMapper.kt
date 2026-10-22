package com.papertrail.api.external.docling

import com.fasterxml.jackson.databind.JsonNode
import com.papertrail.api.citation.parsing.ParsedPageSpan

private const val MINIMUM_UNIQUE_MATCH_LENGTH = 16
private val NON_BODY_LABELS = setOf("title", "section_header", "page_header", "page_footer")

class DoclingPageProvenanceMapper {
    fun map(normalizedSourceText: String, jsonContent: JsonNode): List<ParsedPageSpan> {
        val source = ProjectedText.from(normalizedSourceText)
        val items = jsonContent.path("texts")
        if (!items.isArray) return emptyList()

        return items.flatMap { item -> mapItem(source, item) }
            .distinct()
            .sortedWith(compareBy(ParsedPageSpan::startOffset, ParsedPageSpan::endOffset, ParsedPageSpan::pageNumber))
    }

    private fun mapItem(source: ProjectedText, item: JsonNode): List<ParsedPageSpan> {
        if (item.path("label").asText() in NON_BODY_LABELS) return emptyList()
        val textNode = item.path("text")
        if (!textNode.isTextual) return emptyList()
        val itemText = ProjectedText.from(textNode.asText())
        if (itemText.value.length < MINIMUM_UNIQUE_MATCH_LENGTH) return emptyList()

        val itemStart = source.uniqueOccurrenceOf(itemText.value) ?: return emptyList()
        val provenanceNodes = item.path("prov")
        if (!provenanceNodes.isArray || provenanceNodes.size() == 0) return emptyList()

        val provenance = mutableListOf<PageProvenance>()
        for (node in provenanceNodes) {
            val pageNode = node.path("page_no")
            if (!pageNode.isIntegralNumber || !pageNode.canConvertToInt()) return emptyList()
            val pageNumber = pageNode.asInt()
            if (pageNumber <= 0) return emptyList()
            provenance += PageProvenance(pageNumber, node.path("charspan"))
        }

        if (provenance.map(PageProvenance::pageNumber).distinct().size == 1) {
            return source.sourceRange(itemStart, itemStart + itemText.value.length)
                ?.let { listOf(ParsedPageSpan(it.start, it.endExclusive, provenance.first().pageNumber)) }
                .orEmpty()
        }

        val spans = mutableListOf<ParsedPageSpan>()
        for (page in provenance) {
            val charspan = page.charspan
            if (!charspan.isArray || charspan.size() != 2 ||
                !charspan[0].isIntegralNumber || !charspan[0].canConvertToInt() ||
                !charspan[1].isIntegralNumber || !charspan[1].canConvertToInt()
            ) return emptyList()

            val itemRange = itemText.canonicalRangeForRawSpan(charspan[0].asInt(), charspan[1].asInt()) ?: continue
            val sourceRange = source.sourceRange(itemStart + itemRange.start, itemStart + itemRange.endExclusive) ?: continue
            spans += ParsedPageSpan(sourceRange.start, sourceRange.endExclusive, page.pageNumber)
        }
        return spans
    }

    private data class PageProvenance(val pageNumber: Int, val charspan: JsonNode)

    private data class SourceRange(val start: Int, val endExclusive: Int)

    private data class ProjectedText(
        val value: String,
        private val rawText: String,
        private val sourceStarts: IntArray,
        private val sourceEnds: IntArray,
    ) {
        fun uniqueOccurrenceOf(needle: String): Int? {
            var occurrence: Int? = null
            var searchFrom = 0
            while (searchFrom <= value.length - needle.length) {
                val found = value.indexOf(needle, searchFrom)
                if (found < 0) break
                val beforeIsWord = found > 0 && Character.isUnicodeIdentifierPart(value.codePointBefore(found))
                val afterIndex = found + needle.length
                val afterIsWord = afterIndex < value.length && Character.isUnicodeIdentifierPart(value.codePointAt(afterIndex))
                val startsWithWord = needle.isNotEmpty() && Character.isUnicodeIdentifierPart(needle.codePointAt(0))
                val endsWithWord = needle.isNotEmpty() && Character.isUnicodeIdentifierPart(needle.codePointBefore(needle.length))
                if ((!startsWithWord || !beforeIsWord) && (!endsWithWord || !afterIsWord)) {
                    if (occurrence != null) return null
                    occurrence = found
                }
                searchFrom = found + 1
            }
            return occurrence
        }

        fun sourceRange(start: Int, endExclusive: Int): SourceRange? {
            if (start < 0 || endExclusive <= start || endExclusive > value.length) return null
            return SourceRange(sourceStarts[start], sourceEnds[endExclusive - 1])
        }

        fun canonicalRangeForRawSpan(startCodePoint: Int, endCodePoint: Int): SourceRange? {
            val codePointCount = rawText.codePointCount(0, rawText.length)
            if (startCodePoint < 0 || endCodePoint <= startCodePoint || endCodePoint > codePointCount) return null
            val start = rawText.offsetByCodePoints(0, startCodePoint)
            val endExclusive = rawText.offsetByCodePoints(0, endCodePoint)
            val first = firstIndexWhoseEndExceeds(start)
            val afterLast = firstIndexWhoseStartReaches(endExclusive)
            if (first >= afterLast) return null
            return SourceRange(first, afterLast)
        }

        private fun firstIndexWhoseEndExceeds(offset: Int): Int {
            var low = 0
            var high = value.length
            while (low < high) {
                val middle = (low + high) ushr 1
                if (sourceEnds[middle] <= offset) low = middle + 1 else high = middle
            }
            return low
        }

        private fun firstIndexWhoseStartReaches(offset: Int): Int {
            var low = 0
            var high = value.length
            while (low < high) {
                val middle = (low + high) ushr 1
                if (sourceStarts[middle] < offset) low = middle + 1 else high = middle
            }
            return low
        }

        companion object {
            fun from(text: String): ProjectedText {
                val canonical = StringBuilder()
                val starts = IntArray(text.length)
                val ends = IntArray(text.length)
                var index = 0
                while (index < text.length) {
                    if (text[index].isWhitespace()) {
                        val start = index
                        while (index < text.length && text[index].isWhitespace()) index++
                        if (canonical.isNotEmpty() && index < text.length) {
                            if (canonical.last() == ' ') {
                                ends[canonical.lastIndex] = index
                            } else {
                                starts[canonical.length] = start
                                ends[canonical.length] = index
                                canonical.append(' ')
                            }
                        }
                    } else {
                        starts[canonical.length] = index
                        ends[canonical.length] = index + 1
                        canonical.append(text[index])
                        index++
                    }
                }
                return ProjectedText(canonical.toString(), text, starts, ends)
            }
        }
    }
}
