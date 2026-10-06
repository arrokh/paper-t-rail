package com.papertrail.api.external.unpaywall.service

import com.papertrail.api.infrastructure.cache.CacheInvalidationResponse
import com.papertrail.api.infrastructure.cache.OperatorCredentialVerifier
import com.papertrail.api.external.unpaywall.UnpaywallDiscoveryCache
import com.papertrail.api.external.unpaywall.http.UnpaywallCacheInvalidationRequest
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

@Service
class UnpaywallCacheInvalidationService(
    private val cache: UnpaywallDiscoveryCache,
    private val operatorCredentialVerifier: OperatorCredentialVerifier,
) {
    fun invalidate(suppliedCredential: String?, request: UnpaywallCacheInvalidationRequest): CacheInvalidationResponse {
        operatorCredentialVerifier.authenticate(suppliedCredential)
        require(DoiNormalizer.normalize(request.doi) != null) { "A valid DOI is required for Unpaywall cache invalidation." }
        val invalidated = try {
            cache.invalidateDoi(request.doi)
        } catch (_: DataAccessException) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "The Unpaywall cache is unavailable.")
        }
        return CacheInvalidationResponse(invalidated)
    }
}
