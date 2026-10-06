package com.papertrail.api.infrastructure.providers

enum class ProviderTrustBoundary(val id: String) {
    LOCAL("LOCAL"),
    EXTERNAL("EXTERNAL"),
    UNREVIEWED("UNREVIEWED"),
}
