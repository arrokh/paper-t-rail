package com.papertrail.api.citation.parsing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import com.papertrail.api.external.grobid.GrobidTeiParser

class GrobidTeiParserTest {
    @Test
    fun `parses sections citation contexts markers bibliography and source spans`() {
        val parsed = GrobidTeiParser("grobid", "0.9.1-crf").parse(TEI)
        val sourceText = "Prior work supports the method [1] and confirms outcomes [2]; however, controls found no effect [3]."

        assertEquals("grobid", parsed.parserId)
        assertEquals("0.9.1-crf", parsed.parserVersion)
        assertEquals(sourceText, parsed.normalizedSourceText)
        assertEquals(1, parsed.sections.size)
        assertEquals("Introduction", parsed.sections.single().heading)
        assertEquals(sourceText, parsed.sections.single().text)
        assertEquals(0, parsed.sections.single().startOffset)
        assertEquals(sourceText.length, parsed.sections.single().endOffset)

        assertEquals(2, parsed.citationContexts.size)
        val firstClause = parsed.citationContexts[0]
        assertEquals("CLAUSE", firstClause.boundaryKind)
        assertEquals(sourceText.substring(0, sourceText.indexOf(';')), firstClause.text)
        assertEquals(listOf("[1]", "[2]"), firstClause.occurrences.map { it.markerText })
        assertEquals(listOf(listOf("ref2", "ref1"), listOf("ref2")), firstClause.occurrences.map { it.bibliographyReferenceKeys })
        val secondClause = parsed.citationContexts[1]
        assertEquals("CLAUSE", secondClause.boundaryKind)
        assertEquals(sourceText.substring(sourceText.indexOf(';') + 2), secondClause.text)
        assertEquals("[3]", secondClause.occurrences.single().markerText)
        (parsed.citationContexts.flatMap { it.occurrences }).forEach { occurrence ->
            assertEquals(occurrence.markerText, parsed.normalizedSourceText.substring(occurrence.startOffset, occurrence.endOffset))
        }

        assertEquals(3, parsed.bibliographyEntries.size)
        val firstReference = parsed.bibliographyEntries.first()
        assertEquals(0, firstReference.entryOrder)
        assertEquals("ref1", firstReference.localReferenceKey)
        assertEquals("A study of evidence", firstReference.title)
        assertEquals(listOf("Ada Researcher"), firstReference.authors)
        assertEquals(2021, firstReference.year)
        assertEquals("10.5555/example.1", firstReference.doi)
        assertEquals("JOURNAL_ARTICLE", firstReference.referenceType)
        assertEquals(
            "A study of evidence Ada Researcher Journal of Examples https://doi.org/10.5555/example.1",
            firstReference.rawText,
        )
        assertFalse(parsed.bibliographyEntries.any { it.rawText.isBlank() })
    }

    @Test
    fun `preserves heading candidates identifiers coordinates and unmatched TEI targets`() {
        val tei = """
            <TEI xmlns="http://www.tei-c.org/ns/1.0" xmlns:xml="http://www.w3.org/XML/1998/namespace">
              <text>
                <body><div><p>Prior work <ref type="bibr" target="#paper">[1]</ref> and <ref type="bibr" target="#missing">[2]</ref> inform this study.</p></div></body>
                <back><listBibl>
                  <bibl xml:id="heading"><author><persName><surname>References</surname></persName></author></bibl>
                  <biblStruct xml:id="paper" coords="2,10,20,30,40">
                    <analytic><title level="a">A precise study</title><author><persName><forename>Ada</forename><surname>Author</surname></persName></author></analytic>
                    <monogr><title level="j">Example Journal</title><imprint><date when="2024"/></imprint><idno type="DOI">https://doi.org/10.1234/example</idno><idno type="arXiv">arXiv:2401.01234v2</idno><ptr type="web" target="https://example.org/record"/></monogr>
                  </biblStruct>
                  <biblStruct><monogr><title level="m">A work without a source id</title></monogr></biblStruct>
                </listBibl></back>
              </text>
            </TEI>
        """.trimIndent()

        val parsed = GrobidTeiParser("grobid", "0.9.1-crf").parse(tei)
        val heading = parsed.bibliographyEntries.first()
        val paper = parsed.bibliographyEntries[1]
        val generated = parsed.bibliographyEntries[2]
        val occurrences = parsed.citationContexts.single().occurrences

        assertEquals(listOf("heading", "paper"), parsed.bibliographyEntries.take(2).map { it.localReferenceKey })
        assertEquals("paper", occurrences[0].bibliographyReferenceKeys.single())
        assertEquals(listOf("missing"), occurrences[1].unmatchedBibliographyReferenceKeys)
        assertEquals(listOf("UNCITED_SECTION_HEADING_PATTERN"), heading.provisionalArtifactSignals)
        assertEquals("paper", paper.sourceLocalReferenceKey)
        assertEquals("GROBID_XML_ID", paper.localReferenceKeyOrigin)
        assertEquals(listOf(2), paper.sourceLocations.map { it.page })
        assertTrue(paper.extractionLimitations.contains("SOURCE_TEXT_SPAN_UNAVAILABLE"))
        assertEquals(
            listOf("DOI", "arXiv", "web"),
            paper.identifiers.map { it.type },
        )
        assertEquals(
            "\n        A precise studyAdaAuthor\n        Example Journalhttps://doi.org/10.1234/examplearXiv:2401.01234v2\n      ",
            paper.sourceTextContent,
        )
        assertEquals("https://doi.org/10.1234/example", paper.identifiers.first().rawValue)
        assertEquals("10.1234/example", paper.identifiers.first().normalizedValue)
        assertEquals("arXiv:2401.01234v2", paper.identifiers[1].rawValue)
        assertEquals("https://example.org/record", paper.identifiers[2].rawValue)
        assertTrue(generated.localReferenceKey.startsWith("generated-bibl-"))
        assertEquals(null, generated.sourceLocalReferenceKey)
        assertEquals("GENERATED_FALLBACK", generated.localReferenceKeyOrigin)
        assertEquals(BibliographyNormalizationPolicySelection.CURRENT, parsed.bibliographyNormalizationPolicy)
    }

    @Test
    fun `normalizes DOI prefixes case-insensitively and preserves the source identifier`() {
        val tei = """
            <TEI xmlns="http://www.tei-c.org/ns/1.0" xmlns:xml="http://www.w3.org/XML/1998/namespace">
              <text>
                <body><div><p>A source paragraph.</p></div></body>
                <back><listBibl>
                  <biblStruct xml:id="paper">
                    <analytic><title level="a">Example title</title></analytic>
                    <monogr><idno type="DOI">DOI: 10.1234/Example</idno></monogr>
                  </biblStruct>
                </listBibl></back>
              </text>
            </TEI>
        """.trimIndent()

        val entry = GrobidTeiParser("grobid", "0.9.1-crf").parse(tei).bibliographyEntries.single()
        val identifier = entry.identifiers.single()

        assertEquals("DOI: 10.1234/Example", identifier.rawValue)
        assertEquals("10.1234/example", identifier.normalizedValue)
        assertEquals("10.1234/example", entry.doi)

        val legacyEntry = GrobidTeiParser("grobid", "0.9.1-crf").parse(
            tei,
            BibliographyNormalizationPolicySelection.LEGACY,
        ).bibliographyEntries.single()
        assertEquals("DOI: 10.1234/Example", legacyEntry.identifiers.single().normalizedValue)
        assertEquals("DOI: 10.1234/Example", legacyEntry.doi)
    }

    @Test
    fun `does not use journal or series titles as work titles when the analytic title is missing`() {
        val tei = """
            <TEI xmlns="http://www.tei-c.org/ns/1.0" xmlns:xml="http://www.w3.org/XML/1998/namespace">
              <text>
                <body><div><p>Prior work informed this study.</p></div></body>
                <back><listBibl>
                  <biblStruct xml:id="article">
                    <analytic><author><persName><forename>Ada</forename><surname>Researcher</surname></persName></author></analytic>
                    <monogr><title level="j">Journal of Evidence</title><imprint><date when="2024"/></imprint></monogr>
                  </biblStruct>
                  <biblStruct xml:id="book">
                    <monogr><title level="m">A Monograph Title</title></monogr>
                  </biblStruct>
                  <biblStruct xml:id="series">
                    <monogr><title level="s">Research Series</title></monogr>
                  </biblStruct>
                </listBibl></back>
              </text>
            </TEI>
        """.trimIndent()

        val parsed = GrobidTeiParser("grobid", "0.9.1-crf").parse(tei)

        assertNull(parsed.bibliographyEntries[0].title)
        assertEquals("JOURNAL_ARTICLE", parsed.bibliographyEntries[0].referenceType)
        assertEquals("A Monograph Title", parsed.bibliographyEntries[1].title)
        assertEquals("BOOK", parsed.bibliographyEntries[1].referenceType)
        assertNull(parsed.bibliographyEntries[2].title)
        assertEquals("Research Series", parsed.bibliographyEntries[2].rawText)
    }

    @Test
    fun `preserves empty extraction candidates and their Citation Target associations`() {
        val tei = """
            <TEI xmlns="http://www.tei-c.org/ns/1.0" xmlns:xml="http://www.w3.org/XML/1998/namespace">
              <text>
                <body><div><p>Prior work <ref type="bibr" target="#empty">[1]</ref> informed this study.</p></div></body>
                <back><listBibl><bibl xml:id="empty"></bibl></listBibl></back>
              </text>
            </TEI>
        """.trimIndent()

        val parsed = GrobidTeiParser("grobid", "0.9.1-crf").parse(tei)

        assertEquals("empty", parsed.bibliographyEntries.single().localReferenceKey)
        assertEquals("", parsed.bibliographyEntries.single().rawText)
        assertEquals(listOf("EMPTY_GROBID_BIBLIOGRAPHY_TEXT"), parsed.bibliographyEntries.single().provisionalArtifactSignals)
        assertEquals(listOf("empty"), parsed.citationContexts.single().occurrences.single().bibliographyReferenceKeys)
        assertEquals(emptyList<String>(), parsed.citationContexts.single().occurrences.single().unmatchedBibliographyReferenceKeys)
    }

    @Test
    fun `legacy normalization keeps its historical heading filter`() {
        val tei = """
            <TEI xmlns="http://www.tei-c.org/ns/1.0" xmlns:xml="http://www.w3.org/XML/1998/namespace">
              <text>
                <body><div><p>Prior work <ref type="bibr" target="#book">[1]</ref> informed this study.</p></div></body>
                <back><listBibl>
                  <bibl xml:id="heading"><author><persName><surname>References</surname></persName></author></bibl>
                  <biblStruct xml:id="book"><monogr><title level="m">A real book</title></monogr></biblStruct>
                </listBibl></back>
              </text>
            </TEI>
        """.trimIndent()

        val parsed = GrobidTeiParser("grobid", "0.9.1-crf").parse(
            tei,
            BibliographyNormalizationPolicySelection.LEGACY,
        )

        assertEquals(listOf("book"), parsed.bibliographyEntries.map { it.localReferenceKey })
        assertEquals(BibliographyNormalizationPolicySelection.LEGACY, parsed.bibliographyNormalizationPolicy)
    }

    @Test
    fun `retains heading-only bibliography text when an in-text citation targets it`() {
        val tei = """
            <TEI xmlns="http://www.tei-c.org/ns/1.0" xmlns:xml="http://www.w3.org/XML/1998/namespace">
              <text>
                <body><div><p>Prior work <ref type="bibr" target="#heading">[7]</ref> informed this study.</p></div></body>
                <back><listBibl>
                  <bibl xml:id="heading"><author><persName><surname>References</surname></persName></author></bibl>
                </listBibl></back>
              </text>
            </TEI>
        """.trimIndent()

        val parsed = GrobidTeiParser("grobid", "0.9.1-crf").parse(tei)

        assertEquals("heading", parsed.bibliographyEntries.single().localReferenceKey)
        assertEquals("heading", parsed.citationContexts.single().occurrences.single().bibliographyReferenceKeys.single())
    }

    @Test
    fun `classifies preprints and academic manuscripts from explicit GROBID TEI signals`() {
        val tei = """
            <TEI xmlns="http://www.tei-c.org/ns/1.0" xmlns:xml="http://www.w3.org/XML/1998/namespace">
              <text>
                <body><div><p>A short source paragraph.</p></div></body>
                <back><listBibl>
                  <biblStruct xml:id="preprint">
                    <analytic><title level="a">Preprint title</title></analytic>
                    <monogr><imprint><date when="2024"/></imprint></monogr>
                    <idno type="arXiv">arXiv:2401.01234</idno>
                  </biblStruct>
                  <biblStruct xml:id="thesis">
                    <monogr><title level="m">Thesis title</title><imprint><date when="2023"/></imprint></monogr>
                    <note type="report">Ph.D. thesis</note>
                  </biblStruct>
                  <biblStruct xml:id="book">
                    <monogr><title level="m">Book title</title></monogr>
                  </biblStruct>
                </listBibl></back>
              </text>
            </TEI>
        """.trimIndent()

        val parsed = GrobidTeiParser("grobid", "0.9.1-crf").parse(tei)

        assertEquals(listOf("PREPRINT", "ACADEMIC_MANUSCRIPT", "BOOK"), parsed.bibliographyEntries.map { it.referenceType })
    }

    @Test
    fun `uses safe XML parsing and rejects a response without a TEI body`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            GrobidTeiParser("grobid", "0.9.1-crf").parse("<TEI><text><back/></text></TEI>")
        }
        assertNotNull(exception.message)
    }

    companion object {
        val TEI = """
            <TEI xmlns="http://www.tei-c.org/ns/1.0" xmlns:xml="http://www.w3.org/XML/1998/namespace">
              <text>
                <body>
                  <div>
                    <head>Introduction</head>
                    <p>Prior work supports the method <ref type="bibr" target="#ref2 #ref1">[1]</ref> and confirms outcomes <ref type="bibr" target="#ref2">[2]</ref>; however, controls found no effect <ref type="bibr" target="#ref3">[3]</ref>.</p>
                  </div>
                </body>
                <back><listBibl>
                  <biblStruct xml:id="ref1"><analytic><title level="a">A study of evidence</title><author><persName><forename>Ada</forename><surname>Researcher</surname></persName></author></analytic><monogr><title level="j">Journal of Examples</title><imprint><date when="2021"/></imprint><idno type="DOI">https://doi.org/10.5555/example.1</idno></monogr></biblStruct>
                  <biblStruct xml:id="ref2"><analytic><title level="a">Another study</title></analytic><monogr><imprint><date when="2020"/></imprint></monogr></biblStruct>
                  <biblStruct xml:id="ref3"><analytic><title level="a">Control results</title></analytic><monogr><imprint><date when="2019"/></imprint></monogr></biblStruct>
                </listBibl></back>
              </text>
            </TEI>
        """.trimIndent()
    }
}
