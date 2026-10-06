package com.papertrail.api.external.crossref.service

import com.papertrail.api.infrastructure.cache.OperatorCredentialVerifier
import com.papertrail.api.external.crossref.CrossrefLookupCache
import com.papertrail.api.external.crossref.http.CrossrefCacheInvalidationRequest
import com.papertrail.api.external.crossref.http.CrossrefCacheInvalidationResponse
import com.papertrail.api.external.crossref.http.CrossrefCacheLookupType
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class CrossrefCacheInvalidationService(
    private val cache: CrossrefLookupCache,
    private val operatorCredentialVerifier: OperatorCredentialVerifier,
) {
    fun invalidate(suppliedCredential: String?, request: CrossrefCacheInvalidationRequest): CrossrefCacheInvalidationResponse {
        operatorCredentialVerifier.authenticate(suppliedCredential)
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
}
