package com.papertrail.api.infrastructure.providers.openai

import com.papertrail.api.infrastructure.messaging.NonRetryablePipelineException

class OpenAiCompatibleProviderException(message: String) : NonRetryablePipelineException(message)
