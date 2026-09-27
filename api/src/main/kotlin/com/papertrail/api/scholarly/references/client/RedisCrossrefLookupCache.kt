package com.papertrail.api.scholarly.references.client

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration

class RedisCrossrefLookupCache(
    private val redis: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val positiveTtl: Duration,
    private val negativeTtl: Duration,
) : CrossrefLookupCache {
    init {
        require(!positiveTtl.isNegative && !positiveTtl.isZero) { "Crossref positive cache TTL must be greater than zero." }
        require(!negativeTtl.isNegative && !negativeTtl.isZero) { "Crossref negative cache TTL must be greater than zero." }
    }

    override fun findByDoi(doi: String): List<ScholarlyWork>? = CrossrefCacheKeys.doi(doi)?.let(::find)

    override fun storeByDoi(doi: String, works: List<ScholarlyWork>) {
        CrossrefCacheKeys.doi(doi)?.let { store(it, works) }
    }

    override fun findBySearch(query: String): List<ScholarlyWork>? = CrossrefCacheKeys.search(query)?.let(::find)

    override fun storeSearch(query: String, works: List<ScholarlyWork>) {
        CrossrefCacheKeys.search(query)?.let { store(it, works) }
    }

    override fun invalidateDoi(doi: String): Boolean = CrossrefCacheKeys.doi(doi)?.let(::delete) ?: false

    override fun invalidateSearch(query: String): Boolean = CrossrefCacheKeys.search(query)?.let(::delete) ?: false

    private fun find(key: String): List<ScholarlyWork>? {
        val encoded = try {
            redis.opsForValue().get(key)
        } catch (_: DataAccessException) {
            log.warn("Crossref cache read failed; continuing without cached metadata")
            return null
        } ?: return null

        return try {
            val collectionType = objectMapper.typeFactory.constructCollectionType(List::class.java, ScholarlyWork::class.java)
            val works: List<ScholarlyWork>? = objectMapper.readValue(encoded, collectionType)
            if (works == null) {
                removeInvalidEntry(key)
                null
            } else {
                normalizeWorks(works)
            }
        } catch (_: JsonProcessingException) {
            removeInvalidEntry(key)
            null
        } catch (_: IllegalArgumentException) {
            removeInvalidEntry(key)
            null
        }
    }

    private fun store(key: String, works: List<ScholarlyWork>) {
        val normalized = normalizeWorks(works)
        val ttl = if (normalized.isEmpty()) negativeTtl else positiveTtl
        try {
            redis.opsForValue().set(key, objectMapper.writeValueAsString(normalized), ttl)
        } catch (_: DataAccessException) {
            log.warn("Crossref cache write failed; continuing without cached metadata")
        }
    }

    private fun delete(key: String): Boolean = redis.delete(key) == true

    private fun removeInvalidEntry(key: String) {
        try {
            redis.delete(key)
        } catch (_: DataAccessException) {
            log.warn("Invalid Crossref cache entry could not be removed")
        }
    }

    private fun normalizeWorks(works: List<ScholarlyWork>): List<ScholarlyWork> = works.mapNotNull { work ->
        val title = normalizeField(work.title) ?: return@mapNotNull null
        ScholarlyWork(
            doi = DoiNormalizer.normalize(work.doi),
            title = title,
            authors = work.authors.mapNotNull(::normalizeField),
            year = work.year,
        )
    }

    private fun normalizeField(value: String): String? = value.trim().replace(WHITESPACE, " ").takeIf(String::isNotEmpty)

    companion object {
        private val WHITESPACE = Regex("\\s+")
        private val log = LoggerFactory.getLogger(RedisCrossrefLookupCache::class.java)
    }
}
