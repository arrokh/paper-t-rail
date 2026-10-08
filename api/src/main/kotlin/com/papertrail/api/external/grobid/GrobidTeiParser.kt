package com.papertrail.api.external.grobid

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

import com.papertrail.api.citation.parsing.BibliographyNormalizationPolicySelection
import com.papertrail.api.citation.parsing.CitationContextSegmenter
import com.papertrail.api.citation.parsing.CitationMarker
import com.papertrail.api.citation.parsing.ParsedBibliographyEntry
import com.papertrail.api.citation.parsing.ParsedBibliographyIdentifier
import com.papertrail.api.citation.parsing.ParsedBibliographySourceLocation
import com.papertrail.api.citation.parsing.ParsedCitationContext
import com.papertrail.api.citation.parsing.ParsedCitationOccurrence
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import com.papertrail.api.citation.parsing.ParsedSection

class GrobidTeiParser(
    private val parserId: String,
    private val parserVersion: String,
    private val maximumCharacters: Int = Int.MAX_VALUE,
    private val contextSegmenter: CitationContextSegmenter = CitationContextSegmenter(),
) {
    init {
        require(maximumCharacters > 0) { "The parsed-text limit must be positive." }
    }
    fun parse(
        tei: String,
        normalizationPolicy: BibliographyNormalizationPolicySelection = BibliographyNormalizationPolicySelection.CURRENT,
    ): ParsedScientificDocument {
        require(normalizationPolicy in SUPPORTED_BIBLIOGRAPHY_POLICIES) {
            "The bibliography normalization policy is not supported."
        }
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
        val referencedBibliographyKeys = readReferencedBibliographyKeys(body)
        val bibliographyEntries = readBibliography(root, referencedBibliographyKeys, normalizationPolicy)
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
                            unmatchedBibliographyReferenceKeys = marker.referenceKeys.filterNot { it in bibliographyKeys },
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
            bibliographyNormalizationPolicy = normalizationPolicy,
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

    private fun readReferencedBibliographyKeys(body: Element): Set<String> = descendants(body)
        .asSequence()
        .filter { it.localName == "ref" && it.getAttribute("type") == "bibr" }
        .flatMap { element ->
            element.getAttribute("target")
                .split(Regex("\\s+"))
                .asSequence()
                .mapNotNull(::referenceFragment)
        }
        .toSet()

    private fun readBibliography(
        root: Element,
        referencedKeys: Set<String>,
        normalizationPolicy: BibliographyNormalizationPolicySelection,
    ): List<ParsedBibliographyEntry> {
        val list = descendants(root).firstOrNull { it.localName == "listBibl" } ?: return emptyList()
        val candidates = descendants(list).filter { it.localName == "biblStruct" || it.localName == "bibl" }
        val reservedKeys = candidates.mapNotNull(::sourceLocalReferenceKey).toSet() + referencedKeys
        val seenKeys = mutableSetOf<String>()
        val entries = candidates.mapIndexedNotNull { index, element ->
            val sourceKey = sourceLocalReferenceKey(element)
            val localKey = sourceKey ?: if (normalizationPolicy == BibliographyNormalizationPolicySelection.LEGACY) {
                "ref${index + 1}"
            } else {
                generatedLocalReferenceKey(index + 1, reservedKeys + seenKeys)
            }
            require(seenKeys.add(localKey)) { "The scientific parser returned duplicate bibliography identifiers." }
            val rawText = readableBibliographyText(element)
            if (rawText.isEmpty() && normalizationPolicy == BibliographyNormalizationPolicySelection.LEGACY) {
                return@mapIndexedNotNull null
            }
            val analytic = descendants(element).firstOrNull { it.localName == "analytic" }
            val titleElement = analytic?.let { analyticElement ->
                descendants(analyticElement).firstOrNull { it.localName == "title" }
            } ?: if (analytic == null) {
                descendants(element).firstOrNull { candidate ->
                    candidate.localName == "title" && candidate.parentNode is Element &&
                        (candidate.parentNode as Element).localName == "monogr" && candidate.getAttribute("level") !in setOf("j", "s")
                }
            } else {
                null
            }
            val title = titleElement?.let { normalizeWhitespace(it.textContent) }?.takeIf(String::isNotEmpty)
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
            val identifiers = readBibliographyIdentifiers(element, normalizationPolicy)
            val doi = identifiers.firstOrNull { it.type.equals("doi", ignoreCase = true) }?.normalizedValue
            val type = referenceType(element)
            val sourceLocations = readSourceLocations(element)
            val entry = ParsedBibliographyEntry(
                entryOrder = index,
                localReferenceKey = localKey,
                rawText = rawText,
                title = title,
                authors = authors,
                year = year,
                doi = doi,
                referenceType = type,
                sourceElement = element.localName,
                sourceLocalReferenceKey = sourceKey,
                localReferenceKeyOrigin = if (sourceKey == null) "GENERATED_FALLBACK" else "GROBID_XML_ID",
                identifiers = identifiers,
                sourceLocations = sourceLocations,
                extractionLimitations = buildList {
                    if (sourceLocations.none { it.page != null }) add(SOURCE_PAGE_UNAVAILABLE)
                    add(SOURCE_TEXT_SPAN_UNAVAILABLE)
                },
                provenanceCaptured = true,
                sourceTextContent = element.textContent.orEmpty(),
            )
            val potentialArtifact = isPotentialSectionHeadingArtifact(entry, referencedKeys)
            if (normalizationPolicy == BibliographyNormalizationPolicySelection.LEGACY && potentialArtifact) {
                return@mapIndexedNotNull null
            }
            val provisionalSignals = buildList {
                if (potentialArtifact) add(POTENTIAL_SECTION_HEADING_SIGNAL)
                if (rawText.isEmpty()) add(EMPTY_BIBLIOGRAPHY_TEXT_SIGNAL)
            }
            entry.copy(provisionalArtifactSignals = provisionalSignals)
        }
        return entries.mapIndexed { entryOrder, entry -> entry.copy(entryOrder = entryOrder) }
    }

    private fun sourceLocalReferenceKey(element: Element): String? = element
        .getAttributeNS(XMLConstants.XML_NS_URI, "id")
        .ifBlank { element.getAttribute("xml:id") }
        .takeIf(String::isNotBlank)

    private fun generatedLocalReferenceKey(index: Int, reservedKeys: Set<String>): String {
        val base = "generated-bibl-$index"
        var candidate = base
        var suffix = 1
        while (candidate in reservedKeys) {
            candidate = "$base-$suffix"
            suffix++
        }
        return candidate
    }

    private fun readBibliographyIdentifiers(
        element: Element,
        normalizationPolicy: BibliographyNormalizationPolicySelection,
    ): List<ParsedBibliographyIdentifier> = descendants(element)
        .filter { it.localName == "idno" || it.localName == "ptr" || it.localName == "ref" }
        .mapNotNull { identifierElement ->
            val rawValue = if (identifierElement.localName == "idno") {
                identifierElement.textContent.orEmpty()
            } else {
                identifierElement.getAttribute("target")
            }
            if (rawValue.isBlank()) return@mapNotNull null
            val type = identifierElement.getAttribute("type").takeIf(String::isNotBlank)
            val normalizedValue = if (type.equals("doi", ignoreCase = true)) {
                normalizeDoi(rawValue, normalizationPolicy).takeIf(String::isNotBlank)
            } else {
                normalizeWhitespace(rawValue).takeIf(String::isNotBlank)
            }
            ParsedBibliographyIdentifier(identifierElement.localName, type, rawValue, normalizedValue)
        }

    private fun readSourceLocations(element: Element): List<ParsedBibliographySourceLocation> =
        (listOf(element) + descendants(element))
            .flatMap { candidate -> candidate.getAttribute("coords").takeIf(String::isNotBlank)?.split(';').orEmpty() }
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map { coordinates ->
                ParsedBibliographySourceLocation(
                    page = coordinates.substringBefore(',').trim().toIntOrNull(),
                    coordinates = coordinates,
                )
            }

    private fun isPotentialSectionHeadingArtifact(
        entry: ParsedBibliographyEntry,
        referencedKeys: Set<String>,
    ): Boolean {
        if (entry.localReferenceKey in referencedKeys) return false
        if (entry.referenceType != "OTHER" || entry.year != null || entry.doi != null) return false

        val heading = normalizeBibliographyHeading(entry.rawText)
        if (heading !in BIBLIOGRAPHY_SECTION_HEADINGS) return false
        if (entry.title != null && normalizeBibliographyHeading(entry.title) != heading) return false
        return entry.authors.all { normalizeBibliographyHeading(it) == heading }
    }

    private fun normalizeBibliographyHeading(value: String): String = normalizeWhitespace(value)
        .trimEnd(':', '.')
        .lowercase(Locale.ROOT)

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

    private fun normalizeDoi(value: String, normalizationPolicy: BibliographyNormalizationPolicySelection): String {
        val withoutUrl = normalizeWhitespace(value)
            .replace(Regex("^https?://(?:dx\\.)?doi\\.org/", RegexOption.IGNORE_CASE), "")
        if (normalizationPolicy == BibliographyNormalizationPolicySelection.LEGACY) {
            return withoutUrl.removePrefix("doi:").trim()
        }
        return withoutUrl
            .replace(Regex("^doi:\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
            .lowercase(Locale.ROOT)
    }

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
        private val SUPPORTED_BIBLIOGRAPHY_POLICIES = setOf(
            BibliographyNormalizationPolicySelection.LEGACY,
            BibliographyNormalizationPolicySelection.CURRENT,
        )
        private val BIBLIOGRAPHY_SECTION_HEADINGS = setOf("references", "bibliography", "works cited", "literature cited")
        private const val POTENTIAL_SECTION_HEADING_SIGNAL = "UNCITED_SECTION_HEADING_PATTERN"
        private const val EMPTY_BIBLIOGRAPHY_TEXT_SIGNAL = "EMPTY_GROBID_BIBLIOGRAPHY_TEXT"
        private const val SOURCE_PAGE_UNAVAILABLE = "SOURCE_PAGE_UNAVAILABLE"
        private const val SOURCE_TEXT_SPAN_UNAVAILABLE = "SOURCE_TEXT_SPAN_UNAVAILABLE"
        private val WHITESPACE = Regex("[\\s\\p{Z}]+")
        private val YEAR_PATTERN = Regex("(?:18|19|20)\\d{2}")
        private val THESIS_PATTERN = Regex("\\b(?:thesis|dissertation)\\b", RegexOption.IGNORE_CASE)
        private val PREPRINT_PATTERN = Regex("\\bpreprint\\b", RegexOption.IGNORE_CASE)
    }
}
