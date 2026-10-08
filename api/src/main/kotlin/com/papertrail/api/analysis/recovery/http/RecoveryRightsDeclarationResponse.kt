package com.papertrail.api.analysis.recovery.http

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Current user declaration for locally staging recovery PDFs. It is an assertion, not a license grant or external-provider consent.")
data class RecoveryRightsDeclarationResponse(
    @field:Schema(description = "Exact version identifier that the batch-creation request must echo.")
    val version: String,
    @field:Schema(description = "Exact declaration text shown for user acceptance.")
    val text: String,
    @field:Schema(description = "Configured per-file byte cap; defaults to the Analysis Run PDF upload limit.", minimum = "1")
    val maxFileBytes: Long,
    @field:Schema(description = "Maximum number of upload intents reserved in one batch.", minimum = "1")
    val maxFilesPerBatch: Int,
    @field:Schema(description = "Maximum total expected bytes reserved in one batch.", minimum = "1")
    val maxBatchBytes: Long,
    @field:Schema(description = "Lifetime of a presigned browser upload URL, in seconds.", minimum = "1")
    val uploadUrlTtlSeconds: Long,
    @field:Schema(description = "Inactivity lifetime since the last successful user mutation, in seconds.", minimum = "1")
    val inactivityTtlSeconds: Long,
)
