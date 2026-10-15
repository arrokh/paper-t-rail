package com.papertrail.api.infrastructure.messaging

interface PipelineHandlerAttemptPolicyProvider {
    val attemptPolicy: PipelineHandlerAttemptPolicy
}
