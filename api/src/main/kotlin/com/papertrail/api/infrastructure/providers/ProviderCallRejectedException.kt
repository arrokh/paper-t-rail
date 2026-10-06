package com.papertrail.api.infrastructure.providers

import com.papertrail.api.infrastructure.messaging.NonRetryablePipelineException

class ProviderCallRejectedException(message: String) : NonRetryablePipelineException(message)

/** Provider-bound content grouped by the stable category used to disclose and authorize it. */
