package com.papertrail.api.scholarly.references.resolver

import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.ScholarlyWork
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConservativeReferenceResolverTest {
    @Test
    fun `normalizes and validates DOI forms without accepting malformed identifiers`() {
        assertEquals("10.1234/example.2024", DoiNormalizer.normalize(" https://doi.org/10.1234/Example.2024 "))
        assertEquals("10.1234/example.2024", DoiNormalizer.normalize("doi:10.1234/Example.2024"))
        assertNull(DoiNormalizer.normalize("10.1234"))
        assertNull(DoiNormalizer.normalize("not-a-doi"))
        assertNull(DoiNormalizer.normalize("10.1234/example with spaces"))
    }

    @Test
    fun `requires title and corroborating author or year evidence for metadata matching`() {
        val matcher = ScholarlyMetadataMatcher(threshold = 0.9, ambiguityMargin = 0.02)
        val entry = reference(title = "Conservative citation resolution", authors = listOf("Ada Researcher"), year = 2024)

        val exact = matcher.match(entry, listOf(work("10.1234/exact", "Conservative citation resolution", listOf("Ada Researcher"), 2024)))
        assertEquals("10.1234/exact", exact.candidate?.doi)
        assertEquals(1.0, exact.score)
        assertEquals("MATCHED", exact.reasonCode)

        val titleOnly = matcher.match(entry.copy(authors = emptyList(), year = null), listOf(work("10.1234/title-only", "Conservative citation resolution", emptyList(), null)))
        assertNull(titleOnly.candidate)
        assertEquals("INSUFFICIENT_MATCH_METADATA", titleOnly.reasonCode)
    }

    @Test
    fun `does not treat a title-only candidate as full evidence when citation has authors and year`() {
        val matcher = ScholarlyMetadataMatcher(threshold = 0.9, ambiguityMargin = 0.02)
        val entry = reference(title = "Conservative citation resolution", authors = listOf("Ada Researcher"), year = 2024)

        val result = matcher.match(entry, listOf(work("10.1234/title-only", entry.title!!, emptyList(), null)))

        assertNull(result.candidate)
        assertEquals("BELOW_CONFIDENCE_THRESHOLD", result.reasonCode)
    }

    @Test
    fun `keeps below-threshold and close top candidates unresolved`() {
        val matcher = ScholarlyMetadataMatcher(threshold = 0.9, ambiguityMargin = 0.02)
        val entry = reference(title = "A study of cautious scholarly reference matching", authors = listOf("Ada Researcher"), year = 2024)

        val nearMiss = matcher.match(entry, listOf(work("10.1234/near", "A study of unrelated ocean chemistry", listOf("Different Author"), 2024)))
        assertNull(nearMiss.candidate)
        assertEquals("BELOW_CONFIDENCE_THRESHOLD", nearMiss.reasonCode)

        val ambiguous = matcher.match(entry, listOf(
            work("10.1234/one", entry.title!!, entry.authors, entry.year),
            work("10.1234/two", entry.title!!, entry.authors, entry.year),
        ))
        assertNull(ambiguous.candidate)
        assertEquals("AMBIGUOUS_MATCH", ambiguous.reasonCode)
    }

    @Test
    fun `classifies unsupported reference types before attempting scholarly metadata lookup`() {
        val provider = RecordingMetadataProvider()
        val resolver = ConservativeReferenceResolver(provider, ScholarlyMetadataMatcher(0.9, 0.02))

        val result = resolver.resolve(reference(type = "BOOK", doi = "10.1234/book"))

        assertEquals(ReferenceResolutionStatus.UNSUPPORTED_REFERENCE_TYPE, result.status)
        assertEquals("UNSUPPORTED_REFERENCE_TYPE", result.reasonCode)
        assertFalse(provider.doiLookupAttempted)
        assertFalse(provider.searchAttempted)
    }

    @Test
    fun `keeps a valid DOI unresolved when metadata does not confirm that exact DOI`() {
        val provider = RecordingMetadataProvider(
            doiResult = work("10.1234/different", "A paper", listOf("A Author"), 2020),
            searchResults = listOf(work("10.1234/matched", "A paper", listOf("A Author"), 2020)),
        )
        val resolver = ConservativeReferenceResolver(provider, ScholarlyMetadataMatcher(0.9, 0.02))

        val result = resolver.resolve(reference(title = "A paper", authors = listOf("A Author"), year = 2020, doi = "10.1234/unconfirmed"))

        assertEquals(ReferenceResolutionStatus.UNRESOLVED, result.status)
        assertEquals("DOI_UNCONFIRMED", result.reasonCode)
        assertNull(result.work)
        assertNull(result.score)
        assertNull(result.matchMethod)
        assertTrue(provider.doiLookupAttempted)
        assertFalse(provider.searchAttempted)
    }

    @Test
    fun `a DOI confirmed by scholarly metadata resolves directly`() {
        val provider = RecordingMetadataProvider(doiResult = work("10.1234/confirmed", "Confirmed paper", listOf("A Author"), 2020))
        val resolver = ConservativeReferenceResolver(provider, ScholarlyMetadataMatcher(0.9, 0.02))

        val result = resolver.resolve(reference(title = "Other parsed title", doi = "https://doi.org/10.1234/confirmed"))

        assertEquals(ReferenceResolutionStatus.RESOLVED, result.status)
        assertEquals("10.1234/confirmed", result.work?.doi)
        assertEquals("CONFIRMED_DOI", result.matchMethod)
        assertFalse(provider.searchAttempted)
    }

    private fun reference(
        title: String? = "A paper",
        authors: List<String> = listOf("A Author"),
        year: Int? = 2020,
        doi: String? = null,
        type: String = "JOURNAL_ARTICLE",
    ) = BibliographyReference(title, authors, year, doi, type)

    private fun work(doi: String, title: String, authors: List<String>, year: Int?) = ScholarlyWork(doi, title, authors, year)

    private class RecordingMetadataProvider(
        private val doiResult: ScholarlyWork? = null,
        private val searchResults: List<ScholarlyWork> = emptyList(),
    ) : ScholarlyMetadataLookup {
        var doiLookupAttempted = false
        var searchAttempted = false

        override fun byDoi(doi: String): ScholarlyWork? {
            doiLookupAttempted = true
            return doiResult
        }

        override fun search(reference: BibliographyReference): List<ScholarlyWork> {
            searchAttempted = true
            return searchResults
        }
    }
}
