package com.papertrail.api.references.client

interface ScholarlyMetadataLookup {
    fun byDoi(doi: String): ScholarlyWork?
    fun search(reference: BibliographyReference): List<ScholarlyWork>
}
