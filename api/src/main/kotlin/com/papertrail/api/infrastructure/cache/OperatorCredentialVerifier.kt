package com.papertrail.api.infrastructure.cache

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest

@Component
class OperatorCredentialVerifier(
    @Value("\${paper-trail.operator.credential:}") private val configuredCredential: String,
) {
    fun authenticate(suppliedCredential: String?) {
        if (configuredCredential.isBlank()) {
            throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Operator cache invalidation is not configured.")
        }
        val supplied = suppliedCredential?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val configured = configuredCredential.toByteArray(Charsets.UTF_8)
        if (!MessageDigest.isEqual(configured, supplied)) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "A valid operator credential is required.")
        }
    }
}
