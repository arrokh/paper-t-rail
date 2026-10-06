package com.papertrail.api.infrastructure.messaging.events

import java.util.UUID

/** Small W3C trace-context helper for preserving context in durable pipeline envelopes. */
object W3CTraceContext {
    private val TRACEPARENT = Regex("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")

    fun forTraceId(traceId: UUID): String = "00-${traceId.toString().replace("-", "")}-${randomSpanId()}-01"

    fun child(parent: String?): String? {
        if (parent == null) return null
        val match = TRACEPARENT.matchEntire(parent) ?: return null
        if (!isValid(parent)) return null
        val traceId = match.groupValues[1]
        return "00-$traceId-${randomSpanId()}-${match.groupValues[3]}"
    }

    fun isValid(traceparent: String?): Boolean {
        val match = traceparent?.let(TRACEPARENT::matchEntire) ?: return false
        return match.groupValues[1].any { it != '0' } && match.groupValues[2].any { it != '0' }
    }

    private fun randomSpanId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
}
