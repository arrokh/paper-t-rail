package com.papertrail.api.analysis.recovery.http

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.util.UUID

data class RecoveryUploadResponse(
    @field:Schema(description = "Recovery upload identifier.", format = "uuid")
    val id: UUID,
    @field:Schema(description = "The Bibliography Entry's run-scoped local key.")
    val localReferenceKey: String,
    @field:Schema(description = "Client-generated idempotency key. Reuse it with the same file metadata to resume an interrupted upload intent.", format = "uuid")
    val idempotencyKey: UUID,
    val filename: String,
    @field:Schema(description = "Client-declared expected byte count; actualSize is server-verified.")
    val expectedSize: Long,
    @field:Schema(description = "Client-declared expected checksum; actualSha256 is server-verified.")
    val expectedSha256: String,
    @field:Schema(description = "Upload state only. STAGED does not assert identity, language eligibility, legal permission, or evidence support.")
    val status: String,
    val failureCode: String?,
    val actualSize: Long?,
    val actualSha256: String?,
    val createdAt: Instant,
    val finalizedAt: Instant?,
)
