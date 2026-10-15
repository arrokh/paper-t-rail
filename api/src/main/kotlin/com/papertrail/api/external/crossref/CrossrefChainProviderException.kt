package com.papertrail.api.external.crossref

import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptOutcomeCode
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataProviderFailure

class CrossrefChainProviderException(
    outcomeCode: ReferenceResolutionAttemptOutcomeCode = ReferenceResolutionAttemptOutcomeCode.UNUSABLE,
    httpStatus: Int? = null,
    retryAfterMillis: Long? = null,
    responseBytes: Long? = null,
) : ScholarlyMetadataProviderFailure(
    outcomeCode = outcomeCode,
    httpStatus = httpStatus,
    retryAfterMillis = retryAfterMillis,
    responseBytes = responseBytes,
    message = "Crossref scholarly metadata lookup failed.",
)
