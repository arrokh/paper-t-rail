package com.papertrail.api.scholarly.acquisition.domain

enum class TerminalVerificationStatus {
    SUPPORTED,
    PARTIALLY_SUPPORTED,
    CONTRADICTED,
    INSUFFICIENT_EVIDENCE,
    INACCESSIBLE,
    UNRESOLVED,
    UNSUPPORTED_REFERENCE_TYPE,
}
