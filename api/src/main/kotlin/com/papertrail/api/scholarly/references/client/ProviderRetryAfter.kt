package com.papertrail.api.scholarly.references.client

import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal object ProviderRetryAfter {
    fun parseMillis(headerValues: List<String>?, now: Instant): Long? {
        val value = headerValues?.singleOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return null
        if (value.all { it in '0'..'9' }) {
            val seconds = value.toLongOrNull() ?: return Long.MAX_VALUE
            return if (seconds > Long.MAX_VALUE / MILLIS_PER_SECOND) {
                Long.MAX_VALUE
            } else {
                seconds * MILLIS_PER_SECOND
            }
        }
        if (value.length > MAX_HEADER_LENGTH) return null

        val retryAt = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }
            .getOrNull()
            ?: return null
        return runCatching { Duration.between(now, retryAt).toMillis().coerceAtLeast(0) }.getOrNull()
    }

    private const val MILLIS_PER_SECOND = 1_000L
    private const val MAX_HEADER_LENGTH = 64
}
