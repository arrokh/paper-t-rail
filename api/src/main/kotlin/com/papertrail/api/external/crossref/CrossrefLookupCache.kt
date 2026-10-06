package com.papertrail.api.external.crossref

import com.papertrail.api.scholarly.references.client.ScholarlyWork

interface CrossrefLookupCache {
    /** Returns null only when the logical lookup has no cache entry; an empty list is a cached negative result. */
    fun findByDoi(doi: String): List<ScholarlyWork>?

    fun storeByDoi(doi: String, works: List<ScholarlyWork>)

    /** Returns null only when the normalized search has no cache entry; an empty list is a cached negative result. */
    fun findBySearch(query: String): List<ScholarlyWork>?

    fun storeSearch(query: String, works: List<ScholarlyWork>)

    fun invalidateDoi(doi: String): Boolean

    fun invalidateSearch(query: String): Boolean
}
