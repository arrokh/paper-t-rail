package com.papertrail.api.parsing

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

data class ParsedScientificDocument(
    val parserId: String,
    val parserVersion: String,
    val normalizedSourceText: String,
    val sections: List<ParsedSection>,
    val citationContexts: List<ParsedCitationContext>,
    val bibliographyEntries: List<ParsedBibliographyEntry>,
    val rawParserOutput: ByteArray = ByteArray(0),
)

data class ParsedSection(
    val sectionOrder: Int,
    val heading: String?,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
)

data class ParsedCitationContext(
    val sectionOrder: Int,
    val boundaryKind: String,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val occurrences: List<ParsedCitationOccurrence>,
)

data class ParsedCitationOccurrence(
    val markerText: String,
    val startOffset: Int,
    val endOffset: Int,
    val bibliographyReferenceKeys: List<String>,
)

data class ParsedBibliographyEntry(
    val entryOrder: Int,
    val localReferenceKey: String,
    val rawText: String,
    val title: String?,
    val authors: List<String>,
    val year: Int?,
    val doi: String?,
    val referenceType: String,
)

class GrobidTeiParser(
    private val parserId: String,
    private val parserVersion: String,
    private val maximumCharacters: Int = Int.MAX_VALUE,
    private val contextSegmenter: CitationContextSegmenter = CitationContextSegmenter(),
) {
    init {
        require(maximumCharacters > 0) { "The parsed-text limit must be positive." }
    }
    fun parse(tei: String): ParsedScientificDocument {
        val root = try {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setXIncludeAware(false)
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(tei))).documentElement
        } catch (exception: Exception) {
            throw IllegalArgumentException("The scientific parser returned invalid TEI XML.", exception)
        }
        val body = descendants(root).firstOrNull { it.localName == "body" }
            ?: throw IllegalArgumentException("The scientific parser response has no TEI body.")
        val bibliographyEntries = readBibliography(root)
        val bibliographyKeys = bibliographyEntries.mapTo(mutableSetOf()) { it.localReferenceKey }
        val sourceSections = readSections(body)
        val sections = mutableListOf<ParsedSection>()
        val contexts = mutableListOf<ParsedCitationContext>()
        val normalizedText = StringBuilder()

        sourceSections.forEachIndexed { sectionOrder, sourceSection ->
            if (sectionOrder > 0) normalizedText.append('\n')
            val sectionStart = normalizedText.length
            val sectionText = sourceSection.paragraphs.joinToString("\n") { it.text }
            normalizedText.append(sectionText)
            val sectionEnd = normalizedText.length
            require(sectionEnd <= maximumCharacters) {
                "The scientific parser output exceeds the configured extracted-character limit."
            }
            sections += ParsedSection(sectionOrder, sourceSection.heading, sectionText, sectionStart, sectionEnd)

            var paragraphStart = sectionStart
            sourceSection.paragraphs.forEachIndexed { paragraphIndex, paragraph ->
                contextSegmenter.segment(paragraph.text, paragraph.markers).forEach { context ->
                    val occurrences = context.markers.map { marker ->
                        ParsedCitationOccurrence(
                            markerText = marker.text,
                            startOffset = paragraphStart + marker.startOffset,
                            endOffset = paragraphStart + marker.endOffset,
                            bibliographyReferenceKeys = marker.referenceKeys.filter { it in bibliographyKeys },
                        )
                    }
                    contexts += ParsedCitationContext(
                        sectionOrder = sectionOrder,
                        boundaryKind = context.boundaryKind,
                        text = context.text,
                        startOffset = paragraphStart + context.startOffset,
                        endOffset = paragraphStart + context.endOffset,
                        occurrences = occurrences,
                    )
                }
                paragraphStart += paragraph.text.length
                if (paragraphIndex < sourceSection.paragraphs.lastIndex) paragraphStart++
            }
        }

        require(normalizedText.isNotBlank()) { "The scientific parser response contains no body text." }
        return ParsedScientificDocument(
            parserId = parserId,
            parserVersion = parserVersion,
            normalizedSourceText = normalizedText.toString(),
            sections = sections,
            citationContexts = contexts,
            bibliographyEntries = bibliographyEntries,
        )
    }

    private fun readSections(body: Element): List<SourceSection> {
        val topLevelDivs = childElements(body).filter { it.localName == "div" }
        val bodyParagraphs = childElements(body).filter { it.localName == "p" }
            .map(::readParagraph)
        val sections = mutableListOf<SourceSection>()
        if (bodyParagraphs.isNotEmpty()) sections += SourceSection(null, bodyParagraphs)
        topLevelDivs.forEachIndexed { index, div ->
            val paragraphs = descendants(div).filter { it.localName == "p" }.map(::readParagraph)
            if (paragraphs.isNotEmpty()) {
                val heading = childElements(div).firstOrNull { it.localName == "head" }
                    ?.let { normalizeWhitespace(it.textContent) }
                    ?.takeIf(String::isNotEmpty)
                sections += SourceSection(heading ?: "Section ${index + 1}", paragraphs)
            }
        }
        if (sections.isEmpty()) {
            val paragraphs = descendants(body).filter { it.localName == "p" }.map(::readParagraph)
            if (paragraphs.isNotEmpty()) sections += SourceSection(null, paragraphs)
        }
        return sections
    }

    private fun readParagraph(element: Element): SourceParagraph {
        val rawText = StringBuilder()
        val rawMarkers = mutableListOf<RawMarker>()

        fun append(node: Node) {
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> rawText.append(node.nodeValue.orEmpty())
                Node.ELEMENT_NODE -> {
                    val child = node as Element
                    if (child.localName == "lb") {
                        if (child.getAttribute("break") != "no") rawText.append(' ')
                    } else if (child.localName == "ref" && child.getAttribute("type") == "bibr") {
                        val start = rawText.length
                        val children = child.childNodes
                        for (childIndex in 0 until children.length) append(children.item(childIndex))
                        val end = rawText.length
                        if (end > start) {
                            val targets = child.getAttribute("target")
                                .split(Regex("\\s+"))
                                .mapNotNull(::referenceFragment)
                            rawMarkers += RawMarker(start, end, targets)
                        }
                    } else {
                        val children = child.childNodes
                        for (childIndex in 0 until children.length) append(children.item(childIndex))
                    }
                }
            }
        }
        val children = element.childNodes
        for (index in 0 until children.length) append(children.item(index))
        val normalized = normalizeParagraph(rawText.toString(), rawMarkers)
        return SourceParagraph(normalized.text, normalized.markers)
    }

    private fun normalizeParagraph(text: String, markers: List<RawMarker>): NormalizedParagraph {
        val normalized = StringBuilder()
        val offsets = IntArray(text.length + 1)
        var index = 0
        while (index < text.length) {
            if (text[index].isWhitespaceCharacter()) {
                val start = index
                while (index < text.length && text[index].isWhitespaceCharacter()) index++
                offsets[start] = normalized.length
                if (normalized.isNotEmpty() && index < text.length && normalized.last() != ' ') normalized.append(' ')
                for (boundary in start + 1..index) offsets[boundary] = normalized.length
            } else {
                offsets[index] = normalized.length
                normalized.append(text[index])
                index++
                offsets[index] = normalized.length
            }
        }
        val normalizedText = normalized.toString()
        val parsedMarkers = markers.mapNotNull { marker ->
            var start = offsets[marker.start].coerceIn(0, normalizedText.length)
            var end = offsets[marker.end].coerceIn(start, normalizedText.length)
            while (start < end && normalizedText[start].isWhitespaceCharacter()) start++
            while (end > start && normalizedText[end - 1].isWhitespaceCharacter()) end--
            if (start == end) null else CitationMarker(
                startOffset = start,
                endOffset = end,
                text = normalizedText.substring(start, end),
                referenceKeys = marker.referenceKeys,
            )
        }
        return NormalizedParagraph(normalizedText, parsedMarkers)
    }

    private fun readBibliography(root: Element): List<ParsedBibliographyEntry> {
        val list = descendants(root).firstOrNull { it.localName == "listBibl" } ?: return emptyList()
        val candidates = descendants(list).filter { it.localName == "biblStruct" || it.localName == "bibl" }
        val seenKeys = mutableSetOf<String>()
        return candidates.mapIndexedNotNull { index, element ->
            val id = element.getAttributeNS(XMLConstants.XML_NS_URI, "id")
                .ifBlank { element.getAttribute("xml:id") }
                .ifBlank { "ref${index + 1}" }
            require(seenKeys.add(id)) { "The scientific parser returned duplicate bibliography identifiers." }
            val rawText = readableBibliographyText(element)
            if (rawText.isEmpty()) return@mapIndexedNotNull null
            val title = descendants(element).firstOrNull { candidate ->
                candidate.localName == "title" && candidate.parentNode is Element &&
                    (candidate.parentNode as Element).localName in setOf("analytic", "monogr")
            }?.let { normalizeWhitespace(it.textContent) }?.takeIf(String::isNotEmpty)
            val authors = descendants(element).filter { it.localName == "author" }
                .mapNotNull { author ->
                    val person = descendants(author).firstOrNull { it.localName == "persName" }
                    val nameParts = person?.let { name ->
                        descendants(name)
                            .filter { it.localName in setOf("forename", "surname", "genName", "addName") }
                            .map { normalizeWhitespace(it.textContent) }
                            .filter(String::isNotEmpty)
                    }.orEmpty()
                    (nameParts.takeIf { it.isNotEmpty() }?.joinToString(" ")
                        ?: normalizeWhitespace(person?.textContent ?: author.textContent))
                        .takeIf(String::isNotEmpty)
                }
            val year = descendants(element).firstOrNull { it.localName == "date" }
                ?.let { it.getAttribute("when").ifBlank { it.textContent } }
                ?.let(YEAR_PATTERN::find)?.value?.toIntOrNull()
            val doi = descendants(element).firstOrNull {
                it.localName == "idno" && it.getAttribute("type").equals("doi", ignoreCase = true)
            }?.let { normalizeDoi(it.textContent) }
            val type = referenceType(element)
            ParsedBibliographyEntry(index, id, rawText, title, authors, year, doi, type)
        }
    }

    private fun referenceType(element: Element): String {
        val reportDescription = descendants(element)
            .filter { it.localName == "note" && it.getAttribute("type") in setOf("report", "report_type") }
            .joinToString(" ") { normalizeWhitespace(it.textContent) }
        val hasArxivIdentifier = descendants(element).any {
            it.localName == "idno" && it.getAttribute("type").equals("arXiv", ignoreCase = true)
        }
        return when {
            THESIS_PATTERN.containsMatchIn(reportDescription) -> "ACADEMIC_MANUSCRIPT"
            hasArxivIdentifier || PREPRINT_PATTERN.containsMatchIn(reportDescription) -> "PREPRINT"
            descendants(element).any { it.localName == "meeting" } -> "CONFERENCE_PAPER"
            descendants(element).any { it.localName == "title" && it.getAttribute("level") == "j" } -> "JOURNAL_ARTICLE"
            descendants(element).any { it.localName == "title" && it.getAttribute("level") == "m" } -> "BOOK"
            else -> "OTHER"
        }
    }

    private fun referenceFragment(target: String): String? = target
        .substringAfterLast('#', target)
        .removePrefix("#")
        .takeIf(String::isNotBlank)

    private fun normalizeDoi(value: String): String = normalizeWhitespace(value)
        .replace(Regex("^https?://(?:dx\\.)?doi\\.org/", RegexOption.IGNORE_CASE), "")
        .removePrefix("doi:")
        .trim()

    private fun readableBibliographyText(element: Element): String {
        val separatedElements = setOf("author", "date", "forename", "genName", "idno", "surname", "title", "addName")
        val text = StringBuilder()
        fun append(node: Node) {
            when (node.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> text.append(node.nodeValue.orEmpty())
                Node.ELEMENT_NODE -> {
                    val child = node as Element
                    val addSeparators = child.localName in separatedElements
                    if (addSeparators && text.isNotEmpty()) text.append(' ')
                    val children = child.childNodes
                    for (index in 0 until children.length) append(children.item(index))
                    if (addSeparators) text.append(' ')
                }
            }
        }
        append(element)
        return normalizeWhitespace(text.toString())
    }

    private fun normalizeWhitespace(value: String): String = value.replace(WHITESPACE, " ").trim()

    private fun descendants(element: Element): List<Element> {
        val result = mutableListOf<Element>()
        val children = element.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element) {
                result += child
                result += descendants(child)
            }
        }
        return result
    }

    private fun childElements(element: Element): List<Element> {
        val children = element.childNodes
        return (0 until children.length).mapNotNull { children.item(it) as? Element }
    }

    private fun Char.isWhitespaceCharacter(): Boolean = isWhitespace() || Character.isSpaceChar(this)

    private data class SourceSection(val heading: String?, val paragraphs: List<SourceParagraph>)
    private data class SourceParagraph(val text: String, val markers: List<CitationMarker>)
    private data class RawMarker(val start: Int, val end: Int, val referenceKeys: List<String>)
    private data class NormalizedParagraph(val text: String, val markers: List<CitationMarker>)

    companion object {
        private val WHITESPACE = Regex("[\\s\\p{Z}]+")
        private val YEAR_PATTERN = Regex("(?:18|19|20)\\d{2}")
        private val THESIS_PATTERN = Regex("\\b(?:thesis|dissertation)\\b", RegexOption.IGNORE_CASE)
        private val PREPRINT_PATTERN = Regex("\\bpreprint\\b", RegexOption.IGNORE_CASE)
    }
}
