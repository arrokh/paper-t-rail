package com.papertrail.api.scholarly.acquisition.domain

enum class CitedPaperAccessReason {
    ABSTRACT_ONLY,
    NO_LEGAL_FULL_TEXT_LOCATION,
    NO_ACCESSIBLE_METADATA,
    FULL_TEXT_ACQUISITION_FAILED,
}
