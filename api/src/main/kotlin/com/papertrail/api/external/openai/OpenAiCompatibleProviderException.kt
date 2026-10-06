package com.papertrail.api.external.openai

import com.papertrail.api.infrastructure.messaging.NonRetryablePipelineException

class OpenAiCompatibleProviderException(message: String) : NonRetryablePipelineException(message)
