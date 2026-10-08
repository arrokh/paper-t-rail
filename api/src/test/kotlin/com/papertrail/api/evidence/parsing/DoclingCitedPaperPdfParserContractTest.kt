package com.papertrail.api.evidence.parsing

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataCandidate
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataExtractionMethod
import com.papertrail.api.citation.parsing.ParsedBibliographicMetadataField
import com.papertrail.api.external.docling.DoclingCitedPaperPdfParser
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class DoclingCitedPaperPdfParserContractTest {
    @Test
    fun `converts a cited PDF to normalized section paragraphs using Docling Markdown output`() {
        val builder = RestClient.builder().baseUrl("http://docling.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://docling.test/v1/convert/file"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Accept", MediaType.APPLICATION_JSON_VALUE))
            .andExpect(content().string(containsString("name=\"files\"")))
            .andExpect(content().string(containsString("name=\"to_formats\"")))
            .andExpect(content().string(containsString("md")))
            .andRespond(
                withSuccess(
                    """{"status":"success","document":{"md_content":"# Study of Effects\n\nThe first line wraps\nonto the next line.\n\n## Results\n\nA result was observed.\n\n| Group | Result |\n| --- | --- |\n| A | Effective |"}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        val parser = DoclingCitedPaperPdfParser(
            client = builder.build(),
            parserVersion = "1.30.0",
            maximumResponseBytes = 64 * 1024,
            maximumCharacters = 5_000,
        )

        val parsed = parser.parse("pdf-bytes".toByteArray())

        assertEquals("docling", parsed.parserId)
        assertEquals("1.30.0", parsed.parserVersion)
        assertEquals(
            "The first line wraps onto the next line.\nA result was observed.\n| Group | Result |\n| --- | --- |\n| A | Effective |",
            parsed.normalizedSourceText,
        )
        assertEquals(listOf("Study of Effects", "Results"), parsed.sections.map { it.heading })
        assertEquals(2, parsed.sections.size)
        assertEquals("The first line wraps onto the next line.", parsed.sections[0].text)
        assertEquals("A result was observed.\n| Group | Result |\n| --- | --- |\n| A | Effective |", parsed.sections[1].text)
        assertEquals(emptyList<Any>(), parsed.citationContexts)
        assertEquals(emptyList<Any>(), parsed.bibliographyEntries)
        server.verify()
    }

    @Test
    fun `returns first-page metadata candidates with source provenance and ignores reference-page DOIs`() {
        val builder = RestClient.builder().baseUrl("http://docling.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://docling.test/v1/convert/file"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(content().string(containsString("name=\"to_formats\"")))
            .andExpect(content().string(containsString("md")))
            .andExpect(content().string(containsString("json")))
            .andRespond(
                withSuccess(
                    """{"status":"success","document":{"md_content":"# Synthetic Cited Paper\n\nBody text.","json_content":{"texts":[{"self_ref":"#/texts/0","label":"section_header","text":"Synthetic Cited Paper","prov":[{"page_no":1,"charspan":[0,21]}]},{"self_ref":"#/texts/1","label":"text","text":"Avery Example and Rowan Sample","prov":[{"page_no":1,"charspan":[0,30]}]},{"self_ref":"#/texts/2","label":"text","text":"DOI: 10.5555/papertrail.synthetic.article.2025","prov":[{"page_no":1,"charspan":[0,46]}]},{"label":"text","text":"References DOI: 10.5555/papertrail.unrelated.reference.2024","prov":[{"page_no":4}]}]}}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        val parser = DoclingCitedPaperPdfParser(
            client = builder.build(),
            parserVersion = "1.30.0",
            maximumResponseBytes = 64 * 1024,
            maximumCharacters = 5_000,
        )

        val parsed = parser.parse("pdf-bytes".toByteArray())

        assertEquals(
            listOf(
                ParsedBibliographicMetadataCandidate(
                    field = ParsedBibliographicMetadataField.TITLE,
                    value = "Synthetic Cited Paper",
                    pageNumber = 1,
                    sourceLabel = "section_header",
                    extractionMethod = ParsedBibliographicMetadataExtractionMethod.FIRST_PAGE_SECTION_HEADER,
                    sourceElementId = "#/texts/0",
                    sourceCharSpanStart = 0,
                    sourceCharSpanEnd = 21,
                ),
                ParsedBibliographicMetadataCandidate(
                    field = ParsedBibliographicMetadataField.AUTHORS,
                    value = "Avery Example and Rowan Sample",
                    pageNumber = 1,
                    sourceLabel = "text",
                    extractionMethod = ParsedBibliographicMetadataExtractionMethod.FIRST_TEXT_AFTER_TITLE_HEADING,
                    sourceElementId = "#/texts/1",
                    sourceCharSpanStart = 0,
                    sourceCharSpanEnd = 30,
                ),
                ParsedBibliographicMetadataCandidate(
                    field = ParsedBibliographicMetadataField.DOI,
                    value = "10.5555/papertrail.synthetic.article.2025",
                    pageNumber = 1,
                    sourceLabel = "text",
                    extractionMethod = ParsedBibliographicMetadataExtractionMethod.EXPLICIT_DOI_PREFIX,
                    sourceElementId = "#/texts/2",
                    sourceCharSpanStart = 0,
                    sourceCharSpanEnd = 46,
                ),
            ),
            parsed.bibliographicMetadataCandidates,
        )
        server.verify()
    }

    @Test
    fun `keeps candidate metadata empty when the Docling JSON export is unavailable`() {
        val builder = RestClient.builder().baseUrl("http://docling.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://docling.test/v1/convert/file"))
            .andRespond(
                withSuccess(
                    """{"status":"success","document":{"md_content":"# Study\n\nExtracted text."}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        val parser = DoclingCitedPaperPdfParser(
            client = builder.build(),
            parserVersion = "1.30.0",
            maximumResponseBytes = 64 * 1024,
            maximumCharacters = 5_000,
        )

        val parsed = parser.parse("pdf-bytes".toByteArray())

        assertEquals(emptyList<ParsedBibliographicMetadataCandidate>(), parsed.bibliographicMetadataCandidates)
        server.verify()
    }

    @Test
    fun `does not infer page provenance when Docling omits page locations`() {
        val builder = RestClient.builder().baseUrl("http://docling.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://docling.test/v1/convert/file"))
            .andRespond(
                withSuccess(
                    """{"status":"success","document":{"md_content":"# Study\n\nExtracted text.","json_content":{"texts":[{"self_ref":"#/texts/0","label":"title","text":"Study of Effects","prov":[]},{"self_ref":"#/texts/1","label":"text","text":"DOI: 10.5555/papertrail.synthetic.article.2025"}]}}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        val parser = DoclingCitedPaperPdfParser(
            client = builder.build(),
            parserVersion = "1.30.0",
            maximumResponseBytes = 64 * 1024,
            maximumCharacters = 5_000,
        )

        val parsed = parser.parse("pdf-bytes".toByteArray())

        assertEquals(emptyList<ParsedBibliographicMetadataCandidate>(), parsed.bibliographicMetadataCandidates)
        server.verify()
    }

    @Test
    fun `rejects a partial Docling conversion rather than indexing incomplete evidence`() {
        val builder = RestClient.builder().baseUrl("http://docling.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://docling.test/v1/convert/file"))
            .andRespond(
                withSuccess(
                    """{"status":"partial_success","document":{"md_content":"## Results\n\nSome extracted text."}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        val parser = DoclingCitedPaperPdfParser(
            client = builder.build(),
            parserVersion = "1.30.0",
            maximumResponseBytes = 64 * 1024,
            maximumCharacters = 5_000,
        )

        assertThrows(IllegalStateException::class.java) { parser.parse("pdf-bytes".toByteArray()) }
        server.verify()
    }

    @Test
    fun `rejects a Docling response that exceeds the configured byte limit`() {
        val builder = RestClient.builder().baseUrl("http://docling.test")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://docling.test/v1/convert/file"))
            .andRespond(
                withSuccess(
                    """{"status":"success","document":{"md_content":"A response larger than the cap."}}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        val parser = DoclingCitedPaperPdfParser(
            client = builder.build(),
            parserVersion = "1.30.0",
            maximumResponseBytes = 16,
            maximumCharacters = 5_000,
        )

        assertThrows(IllegalArgumentException::class.java) { parser.parse("pdf-bytes".toByteArray()) }
        server.verify()
    }
}
