package com.papertrail.api.scholarly.references.attempts.domain

/**
 * Bounded delay and HTTP window for the required retry or provider fallback.
 * `waitMillis` must preserve any valid Retry-After hint without shortening it.
 */
data class ReferenceResolutionNextActionWindow(
    val waitMillis: Long,
    val requestBudgetMillis: Long,
) {
    init {
        require(waitMillis >= 0)
        require(requestBudgetMillis >= MIN_PROVIDER_REQUEST_BUDGET_MILLIS)
    }

    companion object {
        // Approved Crossref and DataCite clients bound connect to 5s and response read to 20s.
        private const val MIN_PROVIDER_REQUEST_BUDGET_MILLIS = 25_000L
    }
}
