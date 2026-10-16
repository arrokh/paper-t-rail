package com.papertrail.api.external.datacite

import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptOutcomeCode
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataProviderFailure

class DataCiteProviderException(
    outcomeCode: ReferenceResolutionAttemptOutcomeCode = ReferenceResolutionAttemptOutcomeCode.UNUSABLE,
    httpStatus: Int? = null,
    retryAfterMillis: Long? = null,
    responseBytes: Long? = null,
) : ScholarlyMetadataProviderFailure(
    outcomeCode = outcomeCode,
    httpStatus = httpStatus,
    retryAfterMillis = retryAfterMillis,
    responseBytes = responseBytes,
    message = "DataCite scholarly metadata lookup failed.",
)
