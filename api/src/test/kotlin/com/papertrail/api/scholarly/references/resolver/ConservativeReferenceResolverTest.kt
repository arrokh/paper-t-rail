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
        assertEquals(listOf("TITLE_EXACT", "AUTHOR_SET_MATCH", "YEAR_MATCH"), exact.candidateEvidence.single().reasonCodes)

        val titleOnly = matcher.match(entry.copy(authors = emptyList(), year = null), listOf(work("10.1234/title-only", "Conservative citation resolution", emptyList(), null)))
        assertNull(titleOnly.candidate)
        assertEquals("INSUFFICIENT_MATCH_METADATA", titleOnly.reasonCode)
    }

    @Test
    fun `matches the same surname and initials across full and abbreviated author forms`() {
        val matcher = ScholarlyMetadataMatcher(threshold = 0.9, ambiguityMargin = 0.02)
        val entry = reference(
            title = "Conservative citation resolution",
            authors = listOf("Ada Researcher", "Eli Example"),
            year = 2024,
        )
        val providerRecord = work(
            "10.1234/abbreviated-authors",
            entry.title!!,
            listOf("Example, E.", "Researcher, A."),
            entry.year,
        )

        val result = matcher.match(entry, listOf(providerRecord))

        assertEquals(providerRecord, result.candidate)
        assertEquals("MATCHED", result.reasonCode)
    }

    @Test
    fun `does not treat a title-only candidate as full evidence when citation has authors and year`() {
        val matcher = ScholarlyMetadataMatcher(threshold = 0.9, ambiguityMargin = 0.02)
        val entry = reference(title = "Conservative citation resolution", authors = listOf("Ada Researcher"), year = 2024)

        val result = matcher.match(entry, listOf(work("10.1234/title-only", entry.title!!, emptyList(), null)))

        assertNull(result.candidate)
        assertEquals("CANDIDATE_AUTHORS_MISSING", result.reasonCode)
    }

    @Test
    fun `does not let matching authors and year rescue a clearly different title`() {
        val matcher = ScholarlyMetadataMatcher(threshold = 0.25, ambiguityMargin = 0.02)
        val entry = reference(title = "A study of cautious scholarly reference matching", authors = listOf("Ada Researcher"), year = 2024)
        val unrelatedWork = work("10.1234/unrelated", "A study of unrelated ocean chemistry", entry.authors, entry.year)

        val result = matcher.match(entry, listOf(unrelatedWork))

        assertNull(result.candidate)
        assertEquals("TITLE_CONFLICT", result.reasonCode)
        assertEquals("A study of unrelated ocean chemistry", result.candidateEvidence.single().title)
        assertEquals(listOf("TITLE_CONFLICT", "AUTHOR_SET_MATCH", "YEAR_MATCH"), result.candidateEvidence.single().reasonCodes)
    }

    @Test
    fun `keeps title-conflicting and ambiguous candidates unresolved`() {
        val matcher = ScholarlyMetadataMatcher(threshold = 0.9, ambiguityMargin = 0.02)
        val entry = reference(title = "A study of cautious scholarly reference matching", authors = listOf("Ada Researcher"), year = 2024)

        val nearMiss = matcher.match(entry, listOf(work("10.1234/near", "A study of unrelated ocean chemistry", listOf("Different Author"), 2024)))
        assertNull(nearMiss.candidate)
        assertEquals("TITLE_CONFLICT", nearMiss.reasonCode)

        val ambiguous = matcher.match(entry, listOf(
            work("10.1234/one", entry.title!!, entry.authors, entry.year),
            work("10.1234/two", entry.title!!, entry.authors, entry.year),
            work("10.1234/three", entry.title!!, entry.authors, entry.year),
            work("10.1234/four", entry.title!!, entry.authors, entry.year),
        ))
        assertNull(ambiguous.candidate)
        assertEquals("AMBIGUOUS_MATCH", ambiguous.reasonCode)
        assertEquals(3, ambiguous.candidateEvidence.size)
    }

    @Test
    fun `deduplicates metadata-equivalent records only when a DOI is missing`() {
        val matcher = ScholarlyMetadataMatcher(threshold = 0.9, ambiguityMargin = 0.02)
        val title = "Conservative citation resolution"
        val entry = reference(title = title, authors = listOf("Ada Researcher"), year = 2024)
        val withDoi = work("10.1234/identified", title, entry.authors, entry.year)
        val withoutDoi = work(null, title, entry.authors, entry.year)

        val result = matcher.match(entry, listOf(withoutDoi, withDoi))

        assertEquals("MATCHED", result.reasonCode)
        assertEquals(withDoi, result.candidate)
        assertEquals(1, result.candidateEvidence.size)
    }

    @Test
    fun `keeps distinct DOI records ambiguous despite identical metadata`() {
        val matcher = ScholarlyMetadataMatcher(threshold = 0.9, ambiguityMargin = 0.02)
        val title = "Conservative citation resolution"
        val entry = reference(title = title, authors = listOf("Ada Researcher"), year = 2024)

        val result = matcher.match(
            entry,
            listOf(
                work("10.1234/version-one", title, entry.authors, entry.year),
                work("10.1234/version-two", title, entry.authors, entry.year),
            ),
        )

        assertNull(result.candidate)
        assertEquals("AMBIGUOUS_MATCH", result.reasonCode)
        assertEquals(2, result.candidateEvidence.size)
    }

    @Test
    fun `resolves books preprints and journal articles`() {
        val supportedTypes = listOf("BOOK", "PREPRINT", "JOURNAL_ARTICLE")

        supportedTypes.forEach { type ->
            val provider = RecordingMetadataProvider(doiResult = work("10.1234/work", "A scholarly work", listOf("A Author"), 2020))
            val resolver = ConservativeReferenceResolver(provider, ScholarlyMetadataMatcher(0.9, 0.02))

            val result = resolver.resolve(reference(title = "A scholarly work", type = type, doi = "10.1234/work"))

            assertEquals(ReferenceResolutionStatus.RESOLVED, result.status, type)
            assertTrue(provider.doiLookupAttempted, type)
            assertFalse(provider.searchAttempted, type)
        }
    }

    @Test
    fun `classifies unsupported reference types before attempting scholarly metadata lookup`() {
        val provider = RecordingMetadataProvider()
        val resolver = ConservativeReferenceResolver(provider, ScholarlyMetadataMatcher(0.9, 0.02))

        val result = resolver.resolve(reference(type = "WEBSITE", doi = "10.1234/site"))

        assertEquals(ReferenceResolutionStatus.UNSUPPORTED_REFERENCE_TYPE, result.status)
        assertEquals("UNSUPPORTED_REFERENCE_TYPE", result.reasonCode)
        assertFalse(provider.doiLookupAttempted)
        assertFalse(provider.searchAttempted)
    }

    @Test
    fun `keeps conflicting DOI identifiers unresolved without provider lookup`() {
        val provider = RecordingMetadataProvider(
            doiResult = work("10.1234/first", "A paper", listOf("A Author"), 2020),
            searchResults = listOf(work("10.1234/first", "A paper", listOf("A Author"), 2020)),
        )
        val resolver = ConservativeReferenceResolver(provider, ScholarlyMetadataMatcher(0.9, 0.02))

        val result = resolver.resolve(
            reference(doi = "10.1234/first").copy(doiIdentifiers = listOf("10.1234/first", "10.1234/second")),
        )

        assertEquals(ReferenceResolutionStatus.UNRESOLVED, result.status)
        assertEquals("CONFLICTING_DOI_IDENTIFIERS", result.reasonCode)
        assertFalse(provider.doiLookupAttempted)
        assertFalse(provider.searchAttempted)
    }

    @Test
    fun `rejects a malformed additional DOI even when another DOI is valid`() {
        val provider = RecordingMetadataProvider(doiResult = work("10.1234/first", "A paper", listOf("A Author"), 2020))
        val resolver = ConservativeReferenceResolver(provider, ScholarlyMetadataMatcher(0.9, 0.02))

        val result = resolver.resolve(
            reference(doi = "10.1234/first").copy(doiIdentifiers = listOf("not-a-doi")),
        )

        assertEquals(ReferenceResolutionStatus.UNRESOLVED, result.status)
        assertEquals("INVALID_IDENTIFIER", result.reasonCode)
        assertFalse(provider.doiLookupAttempted)
        assertFalse(provider.searchAttempted)
    }

    @Test
    fun `reports an unconfirmed DOI separately from a conflicting DOI`() {
        val provider = RecordingMetadataProvider()
        val resolver = ConservativeReferenceResolver(provider, ScholarlyMetadataMatcher(0.9, 0.02))

        val result = resolver.resolve(reference(doi = "10.1234/missing"))

        assertEquals(ReferenceResolutionStatus.UNRESOLVED, result.status)
        assertEquals("DOI_NOT_FOUND", result.reasonCode)
        assertTrue(result.candidateEvidence.isEmpty())
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
        assertEquals("DOI_CONFLICT", result.reasonCode)
        assertEquals("DOI_CONFLICT", result.candidateEvidence.single().reasonCodes.last())
        assertNull(result.work)
        assertNull(result.score)
        assertNull(result.matchMethod)
        assertTrue(provider.doiLookupAttempted)
        assertFalse(provider.searchAttempted)
    }

    @Test
    fun `a DOI confirmed by matching scholarly metadata resolves directly`() {
        val provider = RecordingMetadataProvider(doiResult = work("10.1234/confirmed", "Confirmed paper", listOf("A Author"), 2020))
        val resolver = ConservativeReferenceResolver(provider, ScholarlyMetadataMatcher(0.9, 0.02))

        val result = resolver.resolve(reference(title = "Confirmed paper", doi = "https://doi.org/10.1234/confirmed"))

        assertEquals(ReferenceResolutionStatus.RESOLVED, result.status)
        assertEquals("10.1234/confirmed", result.work?.doi)
        assertEquals("CONFIRMED_DOI", result.matchMethod)
        assertFalse(provider.searchAttempted)
    }

    @Test
    fun `does not resolve a confirmed DOI when its authors conflict with the bibliography entry`() {
        val provider = RecordingMetadataProvider(doiResult = work("10.1234/confirmed", "Bibliography title", listOf("Different Author"), 2020))
        val resolver = ConservativeReferenceResolver(provider, ScholarlyMetadataMatcher(0.9, 0.02))

        val result = resolver.resolve(reference(title = "Bibliography title", authors = listOf("A Author"), year = 2020, doi = "10.1234/confirmed"))

        assertEquals(ReferenceResolutionStatus.UNRESOLVED, result.status)
        assertEquals("DOI_AUTHOR_CONFLICT", result.reasonCode)
        assertTrue(result.candidateEvidence.single().reasonCodes.contains("AUTHOR_CONFLICT"))
        assertFalse(provider.searchAttempted)
    }

    @Test
    fun `does not resolve a confirmed DOI when its year conflicts with the bibliography entry`() {
        val provider = RecordingMetadataProvider(doiResult = work("10.1234/confirmed", "Bibliography title", listOf("A Author"), 2021))
        val resolver = ConservativeReferenceResolver(provider, ScholarlyMetadataMatcher(0.9, 0.02))

        val result = resolver.resolve(reference(title = "Bibliography title", authors = listOf("A Author"), year = 2020, doi = "10.1234/confirmed"))

        assertEquals(ReferenceResolutionStatus.UNRESOLVED, result.status)
        assertEquals("DOI_YEAR_CONFLICT", result.reasonCode)
        assertTrue(result.candidateEvidence.single().reasonCodes.contains("YEAR_CONFLICT"))
        assertFalse(provider.searchAttempted)
    }

    @Test
    fun `does not resolve a confirmed DOI when its title conflicts with the bibliography entry`() {
        val provider = RecordingMetadataProvider(doiResult = work("10.1234/confirmed", "A different paper", listOf("A Author"), 2020))
        val resolver = ConservativeReferenceResolver(provider, ScholarlyMetadataMatcher(0.9, 0.02))

        val result = resolver.resolve(reference(title = "Bibliography title", doi = "10.1234/confirmed"))

        assertEquals(ReferenceResolutionStatus.UNRESOLVED, result.status)
        assertEquals("DOI_TITLE_CONFLICT", result.reasonCode)
        assertTrue(result.candidateEvidence.single().reasonCodes.contains("DOI_TITLE_CONFLICT"))
        assertNull(result.work)
        assertFalse(provider.searchAttempted)
    }

    @Test
    fun `does not search for candidates without a title and author or year metadata`() {
        val provider = RecordingMetadataProvider(
            searchResults = listOf(work("10.1234/work", "A paper", listOf("A Author"), 2020)),
        )
        val resolver = ConservativeReferenceResolver(provider, ScholarlyMetadataMatcher(0.9, 0.02))

        val result = resolver.resolve(reference(title = "A paper", authors = emptyList(), year = null))

        assertEquals(ReferenceResolutionStatus.UNRESOLVED, result.status)
        assertEquals("INSUFFICIENT_MATCH_METADATA", result.reasonCode)
        assertFalse(provider.searchAttempted)
    }

    @Test
    fun `does not fall back to metadata search when the supplied DOI is malformed`() {
        val provider = RecordingMetadataProvider(searchResults = listOf(work("10.1234/work", "A paper", listOf("A Author"), 2020)))
        val resolver = ConservativeReferenceResolver(provider, ScholarlyMetadataMatcher(0.9, 0.02))

        val result = resolver.resolve(reference(doi = "10.1234/bad doi"))

        assertEquals(ReferenceResolutionStatus.UNRESOLVED, result.status)
        assertEquals("INVALID_IDENTIFIER", result.reasonCode)
        assertFalse(provider.doiLookupAttempted)
        assertFalse(provider.searchAttempted)
    }

    @Test
    fun `keeps the legacy DOI fallback for a run pinned to its older policy`() {
        val matchingWork = work("10.1234/matched", "A paper", listOf("A Author"), 2020)
        val provider = RecordingMetadataProvider(
            doiResult = work("10.1234/different", "A different paper", listOf("A Author"), 2020),
            searchResults = listOf(matchingWork),
        )
        val resolver = ConservativeReferenceResolver(
            provider,
            ScholarlyMetadataMatcher(0.9, 0.02, ScholarlyMetadataMatcher.LEGACY_POLICY_VERSION),
        )

        val result = resolver.resolve(
            reference(doi = "10.1234/unconfirmed").copy(doiIdentifiers = listOf("10.1234/additional")),
        )

        assertEquals(ReferenceResolutionStatus.RESOLVED, result.status)
        assertEquals(matchingWork, result.work)
        assertEquals("METADATA_MATCH", result.matchMethod)
        assertTrue(provider.searchAttempted)
        assertEquals(emptyList<String>(), provider.searchedReference?.doiIdentifiers)
        assertTrue(result.candidateEvidence.isEmpty())
    }

    @Test
    fun `keeps the legacy policy behavior for references with additional DOI identifiers`() {
        val confirmed = work("10.1234/first", "A paper", listOf("A Author"), 2020)
        val provider = RecordingMetadataProvider(doiResult = confirmed)
        val resolver = ConservativeReferenceResolver(
            provider,
            ScholarlyMetadataMatcher(0.9, 0.02, ScholarlyMetadataMatcher.LEGACY_POLICY_VERSION),
        )

        val result = resolver.resolve(
            reference(doi = "10.1234/first").copy(doiIdentifiers = listOf("10.1234/first", "10.1234/second")),
        )

        assertEquals(ReferenceResolutionStatus.RESOLVED, result.status)
        assertEquals("DOI_CONFIRMED", result.reasonCode)
        assertTrue(provider.doiLookupAttempted)
        assertFalse(provider.searchAttempted)
    }

    @Test
    fun `keeps the legacy matcher for a run pinned to its older policy`() {
        val matcher = ScholarlyMetadataMatcher(
            threshold = 0.25,
            ambiguityMargin = 0.02,
            policyVersion = ScholarlyMetadataMatcher.LEGACY_POLICY_VERSION,
        )
        val entry = reference(title = "A study of cautious scholarly reference matching", authors = listOf("Ada Researcher"), year = 2024)
        val unrelatedWork = work("10.1234/unrelated", "A study of unrelated ocean chemistry", entry.authors, entry.year)

        val result = matcher.match(entry, listOf(unrelatedWork))

        assertEquals("MATCHED", result.reasonCode)
        assertEquals(unrelatedWork, result.candidate)
    }

    private fun reference(
        title: String? = "A paper",
        authors: List<String> = listOf("A Author"),
        year: Int? = 2020,
        doi: String? = null,
        type: String = "JOURNAL_ARTICLE",
    ) = BibliographyReference(title, authors, year, doi, type)

    private fun work(doi: String?, title: String, authors: List<String>, year: Int?) = ScholarlyWork(doi, title, authors, year)

    private class RecordingMetadataProvider(
        private val doiResult: ScholarlyWork? = null,
        private val searchResults: List<ScholarlyWork> = emptyList(),
    ) : ScholarlyMetadataLookup {
        var doiLookupAttempted = false
        var searchAttempted = false
        var searchedReference: BibliographyReference? = null

        override fun byDoi(doi: String): ScholarlyWork? {
            doiLookupAttempted = true
            return doiResult
        }

        override fun search(reference: BibliographyReference): List<ScholarlyWork> {
            searchAttempted = true
            searchedReference = reference
            return searchResults
        }
    }
}
