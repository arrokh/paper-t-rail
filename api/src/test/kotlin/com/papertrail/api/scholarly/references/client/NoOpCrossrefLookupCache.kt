package com.papertrail.api.scholarly.references.client
import com.papertrail.api.external.crossref.CrossrefLookupCache

internal object NoOpCrossrefLookupCache : CrossrefLookupCache {
    override fun findByDoi(doi: String): List<ScholarlyWork>? = null

    override fun storeByDoi(doi: String, works: List<ScholarlyWork>) = Unit

    override fun findBySearch(query: String): List<ScholarlyWork>? = null

    override fun storeSearch(query: String, works: List<ScholarlyWork>) = Unit

    override fun invalidateDoi(doi: String): Boolean = false

    override fun invalidateSearch(query: String): Boolean = false
}
