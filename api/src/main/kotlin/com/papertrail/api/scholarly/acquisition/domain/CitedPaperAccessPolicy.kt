package com.papertrail.api.scholarly.acquisition.domain

class CitedPaperAccessPolicy {
    fun decide(
        metadataAvailable: Boolean,
        abstractAvailable: Boolean,
        fullTextAvailable: Boolean,
        language: String?,
    ): CitedPaperAccessDecision {
        if (fullTextAvailable) {
            if (language == "en") {
                return CitedPaperAccessDecision(
                    accessStatus = CitedPaperAccessStatus.FULL_TEXT_AVAILABLE,
                    verificationScope = VerificationScope.FULL_TEXT,
                    finalVerificationStatus = null,
                    terminalReason = null,
                )
            }
            return CitedPaperAccessDecision(
                accessStatus = CitedPaperAccessStatus.FULL_TEXT_AVAILABLE,
                verificationScope = VerificationScope.NONE,
                finalVerificationStatus = TerminalVerificationStatus.INSUFFICIENT_EVIDENCE,
                terminalReason = LANGUAGE_UNSUPPORTED,
            )
        }

        if (abstractAvailable) {
            return CitedPaperAccessDecision(
                accessStatus = CitedPaperAccessStatus.ABSTRACT_ONLY,
                verificationScope = VerificationScope.ABSTRACT_ONLY,
                finalVerificationStatus = TerminalVerificationStatus.INSUFFICIENT_EVIDENCE,
                terminalReason = ABSTRACT_ONLY,
            )
        }

        return CitedPaperAccessDecision(
            accessStatus = if (metadataAvailable) CitedPaperAccessStatus.METADATA_ONLY else CitedPaperAccessStatus.UNAVAILABLE,
            verificationScope = VerificationScope.NONE,
            finalVerificationStatus = TerminalVerificationStatus.INACCESSIBLE,
            terminalReason = NO_LEGAL_FULL_TEXT_OR_ABSTRACT,
        )
    }

    companion object {
        const val LANGUAGE_UNSUPPORTED = "LANGUAGE_UNSUPPORTED"
        const val ABSTRACT_ONLY = "ABSTRACT_ONLY"
        const val NO_LEGAL_FULL_TEXT_OR_ABSTRACT = "NO_LEGAL_FULL_TEXT_OR_ABSTRACT"
    }
}
