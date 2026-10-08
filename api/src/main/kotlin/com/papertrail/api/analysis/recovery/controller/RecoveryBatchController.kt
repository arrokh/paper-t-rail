package com.papertrail.api.analysis.recovery.controller

import com.papertrail.api.analysis.recovery.domain.RecoveryBatch
import com.papertrail.api.analysis.recovery.domain.RecoveryUpload
import com.papertrail.api.analysis.recovery.http.CreateRecoveryBatchRequest
import com.papertrail.api.analysis.recovery.http.CreateRecoveryUploadRequest
import com.papertrail.api.analysis.recovery.http.RecoveryBatchResponse
import com.papertrail.api.analysis.recovery.http.RecoveryRightsDeclarationResponse
import com.papertrail.api.analysis.recovery.http.RecoveryUploadIntentResponse
import com.papertrail.api.analysis.recovery.http.RecoveryUploadResponse
import com.papertrail.api.analysis.recovery.service.RecoveryStagingService
import com.papertrail.api.http.ApiError
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.parameters.RequestBody
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody as SpringRequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Recovery Uploads", description = "Temporarily stage rights-declared user PDFs for Bibliography Entries without changing immutable Analysis Runs or starting provider assessment.")
class RecoveryBatchController(
    private val recoveryStagingService: RecoveryStagingService,
) {
    @Operation(
        summary = "Read the current Recovery Upload rights declaration",
        description = "Returns the exact versioned user declaration that the UI must show before creating a Recovery Batch.",
    )
    @ApiResponse(responseCode = "200", description = "Current declaration", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RecoveryRightsDeclarationResponse::class))])
    @GetMapping("/recovery-rights-declaration", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun rightsDeclaration(): ResponseEntity<RecoveryRightsDeclarationResponse> {
        val declaration = recoveryStagingService.currentRightsDeclaration()
        val limits = recoveryStagingService.currentLimits()
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(
                RecoveryRightsDeclarationResponse(
                    version = declaration.version,
                    text = declaration.text,
                    maxFileBytes = limits.maxFileBytes,
                    maxFilesPerBatch = limits.maxFilesPerBatch,
                    maxBatchBytes = limits.maxBatchBytes,
                    uploadUrlTtlSeconds = limits.uploadUrlTtlSeconds,
                    inactivityTtlSeconds = limits.inactivityTtlSeconds,
                ),
            )
    }

    @Operation(
        summary = "List the active Recovery Batch",
        description = "Returns the current unexpired Recovery Batch for a non-deleted Analysis Run, or an empty list when none exists. GET does not extend the seven-day inactivity clock.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Zero or one active Recovery Batch", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, array = ArraySchema(schema = Schema(implementation = RecoveryBatchResponse::class)))]),
            ApiResponse(responseCode = "404", description = "Analysis Run not found or its Source Document was deleted", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @GetMapping("/analysis-runs/{analysisRunId}/recovery-batches", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun activeBatch(
        @Parameter(description = "Predecessor Analysis Run identifier.", required = true)
        @PathVariable analysisRunId: UUID,
    ): ResponseEntity<List<RecoveryBatchResponse>> {
        val batch = recoveryStagingService.activeBatch(analysisRunId)
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(listOfNotNull(batch?.toResponse()))
    }

    @Operation(
        summary = "Create or retrieve a Recovery Batch",
        description = "Records the user's versioned rights declaration and creates one active batch for the Analysis Run. Repeated or concurrent requests return the same active batch. This declaration is not a license grant or external-provider consent.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Created or existing active Recovery Batch", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RecoveryBatchResponse::class))]),
            ApiResponse(responseCode = "400", description = "The rights declaration was not accepted", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "404", description = "Analysis Run not found or its Source Document was deleted", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "The rights declaration changed and must be reviewed again", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "410", description = "Idempotency key refers to an expired Recovery Batch", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @PostMapping("/analysis-runs/{analysisRunId}/recovery-batches", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun createBatch(
        @Parameter(description = "Predecessor Analysis Run identifier.", required = true)
        @PathVariable analysisRunId: UUID,
        @RequestBody(description = "Idempotency key and explicit acceptance of the displayed rights declaration.", required = true, content = [Content(schema = Schema(implementation = CreateRecoveryBatchRequest::class))])
        @Valid @SpringRequestBody request: CreateRecoveryBatchRequest,
    ): ResponseEntity<RecoveryBatchResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            recoveryStagingService.createBatch(
                analysisRunId,
                request.idempotencyKey,
                request.rightsDeclarationVersion,
                request.rightsDeclarationAccepted,
            ).toResponse(),
        )

    @Operation(
        summary = "Create or retrieve a PDF upload intent",
        description = "Creates a run- and Bibliography-Entry-scoped upload intent, then returns a short-lived browser-reachable presigned PUT URL. The signature binds exact Content-Length, PDF content type, and SHA-256. Signed URLs are bearer credentials and are not persisted in batch history or logs.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Upload intent and short-lived PUT capability", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RecoveryUploadIntentResponse::class))]),
            ApiResponse(responseCode = "400", description = "Invalid file metadata or PDF filename", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "404", description = "Recovery Batch or Bibliography Entry not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "Conflicting idempotency key or duplicate active entry upload", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "410", description = "Recovery Batch or upload has expired or was removed", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "413", description = "File or batch quota exceeded", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "503", description = "Storage or bucket CORS configuration is unavailable", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @PostMapping("/recovery-batches/{batchId}/entries/{localReferenceKey}/uploads", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun createUploadIntent(
        @Parameter(description = "Recovery Batch identifier.", required = true)
        @PathVariable batchId: UUID,
        @Parameter(description = "Run-scoped local key of the Bibliography Entry.", required = true)
        @PathVariable localReferenceKey: String,
        @RequestBody(description = "File metadata used to bind the signed request; server verifies actual bytes at finalization.", required = true, content = [Content(schema = Schema(implementation = CreateRecoveryUploadRequest::class))])
        @Valid @SpringRequestBody request: CreateRecoveryUploadRequest,
    ): ResponseEntity<RecoveryUploadIntentResponse> {
        val intent = recoveryStagingService.createUploadIntent(
            batchId = batchId,
            localReferenceKey = localReferenceKey,
            idempotencyKey = request.idempotencyKey,
            filename = request.filename,
            expectedSize = request.expectedSize,
            expectedSha256 = request.expectedSha256,
        )
        val signedUpload = intent.presignedUpload
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(
                RecoveryUploadIntentResponse(
                    upload = intent.upload.toResponse(),
                    uploadUrl = signedUpload?.url,
                    requiredHeaders = if (signedUpload == null) emptyMap() else mapOf(
                        "Content-Type" to signedUpload.contentType,
                        "x-amz-checksum-sha256" to signedUpload.checksumSha256,
                    ),
                    uploadUrlExpiresAt = intent.urlExpiresAt,
                ),
            )
    }

    @Operation(
        summary = "Finalize and verify a staged PDF",
        description = "Reads actual bytes from the writable staging key, verifies actual byte count and SHA-256, checks PDF structure locally, and stores a stable server-owned snapshot. It does not assess identity, language eligibility, legal permission, or evidence support. Duplicate requests are safe.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Server-verified stable PDF snapshot", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RecoveryUploadResponse::class))]),
            ApiResponse(responseCode = "404", description = "Recovery Batch or upload not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "Staging bytes are missing or another finalization completed first", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "410", description = "Recovery Batch or upload has expired or was removed", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "422", description = "Actual byte size or SHA-256 differs from the intent, or the bytes are not a valid supported PDF", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "503", description = "Storage or stable-snapshot verification failed", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @PostMapping("/recovery-batches/{batchId}/uploads/{uploadId}/finalize", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun finalizeUpload(
        @Parameter(description = "Recovery Batch identifier.", required = true)
        @PathVariable batchId: UUID,
        @Parameter(description = "Recovery Upload identifier.", required = true)
        @PathVariable uploadId: UUID,
    ): ResponseEntity<RecoveryUploadResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(recoveryStagingService.finalizeUpload(batchId, uploadId).toResponse())

    @Operation(
        summary = "Remove a Recovery Upload",
        description = "Marks the Recovery Upload removed, attempts immediate object deletion, and keeps a durable cleanup tombstone to remove any object recreated by a still-valid or in-flight presigned URL. Repeated DELETE requests return the removed state.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Removed Recovery Upload", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RecoveryUploadResponse::class))]),
            ApiResponse(responseCode = "404", description = "Recovery Batch or upload not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "410", description = "Recovery Batch has expired", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @DeleteMapping("/recovery-batches/{batchId}/uploads/{uploadId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun removeUpload(
        @Parameter(description = "Recovery Batch identifier.", required = true)
        @PathVariable batchId: UUID,
        @Parameter(description = "Recovery Upload identifier.", required = true)
        @PathVariable uploadId: UUID,
    ): ResponseEntity<RecoveryUploadResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(recoveryStagingService.removeUpload(batchId, uploadId).toResponse())

    private fun RecoveryBatch.toResponse() = RecoveryBatchResponse(
        id = id,
        analysisRunId = analysisRunId,
        status = status.name,
        rightsDeclarationVersion = rightsDeclaration.version,
        rightsDeclarationText = rightsDeclaration.text,
        rightsDeclaredAt = rightsDeclaredAt,
        createdAt = createdAt,
        lastActivityAt = lastActivityAt,
        expiresAt = expiresAt,
        uploads = uploads.map { it.toResponse() },
    )

    private fun RecoveryUpload.toResponse() = RecoveryUploadResponse(
        id = id,
        localReferenceKey = localReferenceKey,
        idempotencyKey = idempotencyKey,
        filename = filename,
        expectedSize = expectedSize,
        expectedSha256 = expectedSha256,
        status = status.name,
        failureCode = failureCode,
        actualSize = actualSize,
        actualSha256 = actualSha256,
        createdAt = createdAt,
        finalizedAt = finalizedAt,
    )
}
