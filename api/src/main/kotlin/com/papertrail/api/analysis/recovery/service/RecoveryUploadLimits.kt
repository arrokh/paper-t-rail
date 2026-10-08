package com.papertrail.api.analysis.recovery.service

data class RecoveryUploadLimits(
    val maxFileBytes: Long,
    val maxFilesPerBatch: Int,
    val maxBatchBytes: Long,
    val uploadUrlTtlSeconds: Long,
    val inactivityTtlSeconds: Long,
)
