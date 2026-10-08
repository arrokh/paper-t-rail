package com.papertrail.api.analysis.recovery.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class RecoveryStagingSettings(
    @Value("\${paper-trail.recovery.staging.max-file-bytes}") val maxFileBytes: Long,
    @Value("\${paper-trail.recovery.staging.max-files-per-batch}") val maxFilesPerBatch: Int,
    @Value("\${paper-trail.recovery.staging.max-batch-bytes}") val maxBatchBytes: Long,
    @Value("\${paper-trail.recovery.staging.upload-url-ttl-seconds}") val uploadUrlTtlSeconds: Long,
    @Value("\${paper-trail.recovery.staging.inactivity-ttl-seconds}") val inactivityTtlSeconds: Long,
    @Value("\${paper-trail.recovery.staging.in-flight-grace-seconds}") val inFlightGraceSeconds: Long,
    @Value("\${paper-trail.recovery.staging.cleanup-retry-window-seconds}") val cleanupRetryWindowSeconds: Long,
    @Value("\${paper-trail.recovery.staging.cleanup-interval-ms}") val cleanupIntervalMs: Long,
    @Value("\${paper-trail.recovery.staging.cleanup-batch-size}") val cleanupBatchSize: Int,
    @Value("\${paper-trail.storage.browser-upload-origins}") browserUploadOrigins: String,
) {
    val browserUploadOrigins: List<String> = browserUploadOrigins.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    init {
        require(maxFileBytes > 0) { "Recovery file size limit must be positive." }
        require(maxFilesPerBatch > 0) { "Recovery batch file limit must be positive." }
        require(maxBatchBytes >= maxFileBytes) { "Recovery batch size limit must be at least the per-file limit." }
        require(uploadUrlTtlSeconds in 1..604_800 && inactivityTtlSeconds > 0) { "Recovery staging expiry limits must be positive and within SigV4 bounds." }
        require(inFlightGraceSeconds > 0 && cleanupRetryWindowSeconds > 0) { "Recovery cleanup windows must be positive." }
        require(cleanupIntervalMs > 0 && cleanupBatchSize > 0) { "Recovery cleanup schedule limits must be positive." }
        require(this.browserUploadOrigins.isNotEmpty() && this.browserUploadOrigins.none { it == "*" }) {
            "Recovery uploads require at least one exact browser origin."
        }
    }
}
