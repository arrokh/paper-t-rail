package com.papertrail.api.analysis.recovery.domain

import com.papertrail.api.infrastructure.storage.PresignedObjectUpload
import java.time.Instant

data class RecoveryUploadIntent(
    val upload: RecoveryUpload,
    val presignedUpload: PresignedObjectUpload?,
    val urlExpiresAt: Instant?,
)
