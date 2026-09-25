package com.papertrail.api.parsing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

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
        val exception = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
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
