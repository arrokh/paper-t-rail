package com.papertrail.api.infrastructure.http

class BoundedHttpRequestException(val reason: Reason) : IllegalStateException() {
    enum class Reason {
        TIMEOUT,
        UNAVAILABLE,
        INTERRUPTED,
    }
}
