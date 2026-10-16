package com.papertrail.api.scholarly.references.attempts.domain

enum class ReferenceResolutionChainOutcome {
    RESOLVED,
    NO_CANDIDATE_OR_DOI_UNCONFIRMED,
    CONFLICT_OR_AMBIGUITY,
    PROVIDER_UNAVAILABLE,
    BUDGET_OR_DEADLINE_EXHAUSTED,
    CONSENT_OR_CONFIG_REJECTED,
    NOT_ATTEMPTED,
    UNKNOWN,
}
