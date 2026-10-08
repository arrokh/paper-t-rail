package com.papertrail.api.analysis.recovery.domain

enum class RecoveryUploadStatus {
    PENDING_UPLOAD,
    FINALIZING,
    STAGED,
    REJECTED,
    REMOVED,
    EXPIRED,
}
