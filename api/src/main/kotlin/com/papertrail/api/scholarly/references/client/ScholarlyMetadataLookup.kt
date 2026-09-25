package com.papertrail.api.scholarly.references.client

interface ScholarlyMetadataLookup {
    fun byDoi(doi: String): ScholarlyWork?
    fun search(reference: BibliographyReference): List<ScholarlyWork>
}
