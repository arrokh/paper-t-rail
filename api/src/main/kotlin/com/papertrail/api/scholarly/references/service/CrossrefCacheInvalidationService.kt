package com.papertrail.api.scholarly.references.service

import com.papertrail.api.scholarly.references.client.CrossrefLookupCache
import com.papertrail.api.scholarly.references.http.CrossrefCacheInvalidationRequest
import com.papertrail.api.scholarly.references.http.CrossrefCacheInvalidationResponse
import com.papertrail.api.scholarly.references.http.CrossrefCacheLookupType
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest

@Service
class CrossrefCacheInvalidationService(
    private val cache: CrossrefLookupCache,
    @Value("\${paper-trail.operator.credential:}") private val operatorCredential: String,
) {
    fun invalidate(suppliedCredential: String?, request: CrossrefCacheInvalidationRequest): CrossrefCacheInvalidationResponse {
        authenticate(suppliedCredential)
        val invalidated = try {
            when (request.lookupType) {
                CrossrefCacheLookupType.DOI -> invalidateDoi(request)
                CrossrefCacheLookupType.SEARCH -> invalidateSearch(request)
            }
        } catch (_: DataAccessException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The Crossref cache is unavailable.")
        }
        return CrossrefCacheInvalidationResponse(invalidated)
    }

    private fun invalidateDoi(request: CrossrefCacheInvalidationRequest): Boolean {
        require(!request.doi.isNullOrBlank() && request.query == null) {
            "DOI invalidation requires only the doi field."
        }
        require(DoiNormalizer.normalize(request.doi) != null) { "A valid DOI is required for DOI invalidation." }
        return cache.invalidateDoi(request.doi)
    }

    private fun invalidateSearch(request: CrossrefCacheInvalidationRequest): Boolean {
        require(request.doi == null && !request.query.isNullOrBlank()) {
            "Search invalidation requires only the query field."
        }
        return cache.invalidateSearch(request.query)
    }

    private fun authenticate(suppliedCredential: String?) {
        if (operatorCredential.isBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Operator cache invalidation is not configured.")
        }
        val supplied = suppliedCredential?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val configured = operatorCredential.toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(configured, supplied)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "A valid operator credential is required.")
        }
    }
}
