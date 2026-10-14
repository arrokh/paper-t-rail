package com.papertrail.api.analysis.recovery.controller

import com.papertrail.api.analysis.recovery.domain.RecoveryBatch
import com.papertrail.api.analysis.recovery.domain.RecoveryUpload
import com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationAttempt
import com.papertrail.api.analysis.recovery.http.CreateRecoveryBatchRequest
import com.papertrail.api.analysis.recovery.http.CreateRecoveryUploadRequest
import com.papertrail.api.analysis.recovery.http.AcceptRecoveryRightsDeclarationRequest
import com.papertrail.api.analysis.recovery.http.ConfirmRecoveryIdentityRequest
import com.papertrail.api.analysis.recovery.http.RecoveryBatchResponse
import com.papertrail.api.analysis.recovery.http.RecoveryHistoryResponse
import com.papertrail.api.analysis.recovery.http.RecoveryIdentifierAssistanceRequest
import com.papertrail.api.analysis.recovery.http.RecoveryIdentifierAssistanceResponse
import com.papertrail.api.analysis.recovery.http.RecoveryIdentityConfirmationResponse
import com.papertrail.api.analysis.recovery.http.RecoveryRightsDeclarationResponse
import com.papertrail.api.analysis.recovery.http.RecoveryUploadIntentResponse
import com.papertrail.api.analysis.recovery.http.RecoveryUploadResponse
import com.papertrail.api.analysis.recovery.http.RecoveryUploadAssetSelectionResponse
import com.papertrail.api.analysis.recovery.http.RecoveryUploadValidationResponse
import com.papertrail.api.analysis.recovery.service.RecoveryHistoryService
import com.papertrail.api.analysis.recovery.service.RecoveryStagingService
import com.papertrail.api.analysis.recovery.service.RecoveryUploadIdentityService
import com.papertrail.api.analysis.recovery.service.RecoveryUploadValidationService
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody as SpringRequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Recovery Uploads", description = "Temporarily stage rights-declared user PDFs for Bibliography Entries without changing immutable Analysis Runs or starting provider assessment.")
class RecoveryBatchController(
    private val recoveryStagingService: RecoveryStagingService,
    private val recoveryUploadValidationService: RecoveryUploadValidationService,
    private val recoveryUploadIdentityService: RecoveryUploadIdentityService,
    private val recoveryHistoryService: RecoveryHistoryService,
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
        val batch = recoveryStagingService.activeBatch(analysisRunId)?.let { activeBatch ->
            activeBatch.copy(
                selectedAssets = activeBatch.selectedAssets.filter { selection ->
                    recoveryUploadValidationService.latest(activeBatch.id, selection.uploadId)
                        ?.id == selection.validationAttemptId
                },
            )
        }
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(listOfNotNull(batch?.toResponse()))
    }

    @Operation(
        summary = "Create or retrieve a Recovery Batch",
        description = "Creates or retrieves one active batch for the Analysis Run. Omit both rightsDeclarationVersion and rightsDeclarationAccepted for DOI-only work, or provide the current version and true acceptance for existing PDF clients. The declaration is not a license grant or external-provider consent.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Created or existing active Recovery Batch", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RecoveryBatchResponse::class))]),
            ApiResponse(responseCode = "400", description = "Rights fields are incomplete or the declaration was not accepted", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "404", description = "Analysis Run not found or its Source Document was deleted", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "The rights declaration changed, the batch already records a different snapshot, or the idempotency key was used with a different PDF-rights acceptance", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "410", description = "Idempotency key refers to an expired Recovery Batch", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @PostMapping("/analysis-runs/{analysisRunId}/recovery-batches", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun createBatch(
        @Parameter(description = "Predecessor Analysis Run identifier.", required = true)
        @PathVariable analysisRunId: UUID,
        @RequestBody(description = "Idempotency key and optional paired rights acceptance; omit both rights fields for DOI-only work.", required = true, content = [Content(schema = Schema(implementation = CreateRecoveryBatchRequest::class))])
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
        summary = "Accept the current PDF-rights declaration for a Recovery Batch",
        description = "Records the exact current PDF-rights declaration before any upload intent. This is a user assertion, not a license grant or external-provider consent; DOI-only work does not require it.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Rights declaration recorded", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RecoveryBatchResponse::class))]),
            ApiResponse(responseCode = "400", description = "Acceptance was not true or the request is invalid", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "404", description = "Recovery Batch not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "The accepted declaration version is no longer current or the batch already records another snapshot", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "410", description = "Recovery Batch has expired", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @PostMapping("/recovery-batches/{batchId}/rights-declaration", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun acceptRightsDeclaration(
        @Parameter(description = "Recovery Batch identifier.", required = true)
        @PathVariable batchId: UUID,
        @RequestBody(description = "Current declaration version and explicit acceptance.", required = true, content = [Content(schema = Schema(implementation = AcceptRecoveryRightsDeclarationRequest::class))])
        @Valid @SpringRequestBody request: AcceptRecoveryRightsDeclarationRequest,
    ): ResponseEntity<RecoveryBatchResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(recoveryStagingService.acceptCurrentRightsDeclaration(batchId, request.rightsDeclarationVersion, request.accepted).toResponse())

    @Operation(
        summary = "Read paginated Recovery Batch history",
        description = "Returns newest-first, keyset-paginated content-minimized lifecycle and work events for the immutable predecessor Analysis Run. The ledger excludes DOI values, filenames, hashes, signed URLs, PDF bytes, source text, and validation detail payloads.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Content-minimized event page", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RecoveryHistoryResponse::class))]),
            ApiResponse(responseCode = "400", description = "Invalid cursor or page size", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "404", description = "Analysis Run not found or its Source Document was deleted", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @GetMapping("/analysis-runs/{analysisRunId}/recovery-history", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun recoveryHistory(
        @Parameter(description = "Immutable predecessor Analysis Run identifier.", required = true)
        @PathVariable analysisRunId: UUID,
        @Parameter(description = "Opaque cursor returned by the previous page; omit for the newest events.")
        @RequestParam(required = false) cursor: String?,
        @Parameter(
            description = "Requested event count; defaults to 50 and must be between 1 and 100.",
            required = false,
            example = "50",
            schema = Schema(minimum = "1", maximum = "100", defaultValue = "50"),
        )
        @RequestParam(defaultValue = "50") limit: Int,
    ): ResponseEntity<RecoveryHistoryResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(RecoveryHistoryResponse.from(recoveryHistoryService.page(analysisRunId, cursor, limit)))

    @Operation(
        summary = "Stage user-confirmed DOI assistance",
        description = "Stores a normalized DOI for an unresolved supported Bibliography Entry. This is a user identity assertion, not provider verification; staging is local, never invokes a provider, and the DOI value is excluded from recovery history.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Normalized DOI assistance recorded", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RecoveryIdentifierAssistanceResponse::class))]),
            ApiResponse(responseCode = "400", description = "Invalid DOI, unsupported identifier type, or missing identity confirmation", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "404", description = "Recovery Batch or Bibliography Entry not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "Entry is resolved, its type is unsupported, or the batch is no longer open", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "410", description = "Recovery Batch has expired", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @PutMapping("/recovery-batches/{batchId}/entries/{localReferenceKey}/identifier-assistance", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun setIdentifierAssistance(
        @Parameter(description = "Recovery Batch identifier.", required = true)
        @PathVariable batchId: UUID,
        @Parameter(description = "Run-scoped local key of the unresolved Bibliography Entry.", required = true)
        @PathVariable localReferenceKey: String,
        @RequestBody(description = "DOI and explicit confirmation that it identifies this Bibliography Entry.", required = true, content = [Content(schema = Schema(implementation = RecoveryIdentifierAssistanceRequest::class))])
        @Valid @SpringRequestBody request: RecoveryIdentifierAssistanceRequest,
    ): ResponseEntity<RecoveryIdentifierAssistanceResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            RecoveryIdentifierAssistanceResponse.from(
                recoveryStagingService.setIdentifierAssistance(
                    batchId, localReferenceKey, request.identifierType, request.identifierValue,
                    request.confirmsBibliographyIdentity,
                ),
            ),
        )

    @Operation(
        summary = "Remove staged DOI assistance",
        description = "Removes this entry's active DOI assistance and appends a content-free removal event. It does not alter the immutable Bibliography Entry.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "204", description = "DOI assistance removed or was already absent"),
            ApiResponse(responseCode = "404", description = "Recovery Batch not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "410", description = "Recovery Batch has expired", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @DeleteMapping("/recovery-batches/{batchId}/entries/{localReferenceKey}/identifier-assistance")
    fun removeIdentifierAssistance(
        @Parameter(description = "Recovery Batch identifier.", required = true)
        @PathVariable batchId: UUID,
        @Parameter(description = "Run-scoped local key of the Bibliography Entry.", required = true)
        @PathVariable localReferenceKey: String,
    ): ResponseEntity<Void> {
        recoveryStagingService.removeIdentifierAssistance(batchId, localReferenceKey)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }

    @Operation(
        summary = "Create or retrieve a PDF upload intent",
        description = "Creates a run- and Bibliography-Entry-scoped upload intent after the current PDF-rights declaration is recorded and verified as still current, then returns a short-lived browser-reachable presigned PUT URL. Multiple independent versions may be staged for one entry. The signature binds exact Content-Length, PDF content type, and SHA-256; signed URLs are not persisted in batch history or logs.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Upload intent and short-lived PUT capability", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RecoveryUploadIntentResponse::class))]),
            ApiResponse(responseCode = "400", description = "Invalid file metadata or PDF filename", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "404", description = "Recovery Batch or Bibliography Entry not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "PDF-rights declaration is absent or no longer current, or the idempotency key conflicts with a different upload", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
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
            ApiResponse(responseCode = "422", description = "Actual byte size or SHA-256 differs from the intent, the PDF is invalid, encrypted, incomplete or unreadable, over the page limit, or has no selectable text (scanned PDFs are unsupported)", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
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
        summary = "Validate a finalized Recovery Upload",
        description = "Runs the Analysis Run's pinned local Docling parser on the exact finalized PDF snapshot, persists metadata candidates with provenance and parser options, and records separate machine-identity and language-eligibility outcomes with the identity policy version. This operation does not confirm identity, change the immutable Analysis Run, or start evidence assessment.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Persisted validation attempt; FAILED parsing is represented separately from identity and upload validity", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RecoveryUploadValidationResponse::class))]),
            ApiResponse(responseCode = "404", description = "Active finalized Recovery Upload not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "The upload or Recovery Batch became inactive during validation", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @PostMapping("/recovery-batches/{batchId}/uploads/{uploadId}/validate", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun validateUpload(
        @Parameter(description = "Recovery Batch identifier.", required = true)
        @PathVariable batchId: UUID,
        @Parameter(description = "Finalized Recovery Upload identifier.", required = true)
        @PathVariable uploadId: UUID,
    ): ResponseEntity<RecoveryUploadValidationResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(recoveryUploadValidationService.validate(batchId, uploadId).toResponse(batchId, uploadId))

    @Operation(
        summary = "Read the latest Recovery Upload validation attempt",
        description = "Returns the latest attempt evaluated with the current parser, identity, and language-eligibility policies for an active finalized upload, or 204 when no current-policy attempt exists. Validation state does not imply human confirmation, English eligibility, legal permission, or evidence assessment.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Latest persisted validation attempt", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RecoveryUploadValidationResponse::class))]),
            ApiResponse(responseCode = "204", description = "No validation attempt exists for the current parser, identity, and language-eligibility policies; run validation again"),
            ApiResponse(responseCode = "404", description = "Active finalized Recovery Upload not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @GetMapping("/recovery-batches/{batchId}/uploads/{uploadId}/validation", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun latestValidation(
        @Parameter(description = "Recovery Batch identifier.", required = true)
        @PathVariable batchId: UUID,
        @Parameter(description = "Finalized Recovery Upload identifier.", required = true)
        @PathVariable uploadId: UUID,
    ): ResponseEntity<RecoveryUploadValidationResponse> {
        val attempt = recoveryUploadValidationService.latest(batchId, uploadId)
            ?: return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(attempt.toResponse(batchId, uploadId))
    }

    @Operation(
        summary = "Confirm the exact Recovery Upload version",
        description = "Records a separate human decision that the exact staged PDF is the cited work or intended version. When DOI assistance is requested, the researcher must also confirm one normalized DOI candidate and its exact parser/source-location fingerprint from this validation attempt. Only current parser-, identity-, and language-policy NEEDS_CONFIRMATION results can be confirmed; mismatches, unsupported chapter identities, inconclusive legacy book/chapter identities, and stale policy attempts cannot be overridden. This does not change machine metadata, Analysis Run state, language eligibility, or evidence assessment.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Recorded exact-PDF confirmation and, when requested, an explicit DOI-candidate confirmation", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RecoveryIdentityConfirmationResponse::class))]),
            ApiResponse(responseCode = "400", description = "Exact-PDF confirmation was not provided or DOI and candidate fingerprint were incomplete or invalid", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "404", description = "Active finalized Recovery Upload not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "The attempt is stale, uses an old parser, identity, or language policy, is not inconclusive, the DOI candidate does not belong to the attempt, or a different candidate is already confirmed", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @PostMapping("/recovery-batches/{batchId}/uploads/{uploadId}/validation/{attemptId}/confirm-identity", consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun confirmIdentity(
        @Parameter(description = "Recovery Batch identifier.", required = true)
        @PathVariable batchId: UUID,
        @Parameter(description = "Finalized Recovery Upload identifier.", required = true)
        @PathVariable uploadId: UUID,
        @Parameter(description = "Persisted validation-attempt identifier.", required = true)
        @PathVariable attemptId: UUID,
        @RequestBody(description = "Explicit exact-PDF confirmation, optionally paired with the normalized DOI and candidate fingerprint selected from this validation response.", required = true, content = [Content(schema = Schema(implementation = ConfirmRecoveryIdentityRequest::class))])
        @Valid @SpringRequestBody request: ConfirmRecoveryIdentityRequest,
    ): ResponseEntity<RecoveryIdentityConfirmationResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(
            RecoveryIdentityConfirmationResponse.from(
                recoveryUploadIdentityService.confirmExactVersion(
                    batchId,
                    uploadId,
                    attemptId,
                    request.confirmExactVersion,
                    request.confirmedDoi,
                    request.doiCandidateFingerprint,
                ),
            ),
        )

    @Operation(
        summary = "Select one exact Recovery Upload version",
        description = "Selects the exact staged bytes for one Bibliography Entry after completed validation under the current parser, identity, and language policies, English-language eligibility, and any required human confirmation. MISMATCH results are rejected. The current selection is atomically replaced by a different eligible version while prior uploads and validation outcomes remain; selecting the current version is idempotent. Selection does not start evidence assessment.",
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "Selected exact asset version", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = RecoveryUploadAssetSelectionResponse::class))]),
            ApiResponse(responseCode = "404", description = "Active finalized Recovery Upload not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "Validation is missing or stale, a parser, identity, or language policy is outdated, or confirmation or language eligibility is not satisfied", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @PostMapping("/recovery-batches/{batchId}/uploads/{uploadId}/select", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun selectExactVersion(
        @Parameter(description = "Recovery Batch identifier.", required = true)
        @PathVariable batchId: UUID,
        @Parameter(description = "Finalized Recovery Upload identifier.", required = true)
        @PathVariable uploadId: UUID,
    ): ResponseEntity<RecoveryUploadAssetSelectionResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(RecoveryUploadAssetSelectionResponse.from(recoveryUploadIdentityService.selectExactVersion(batchId, uploadId)))

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

    private fun RecoveryUploadValidationAttempt.toResponse(
        batchId: UUID,
        uploadId: UUID,
    ) = RecoveryUploadValidationResponse.from(
        attempt = this,
        humanConfirmation = recoveryUploadIdentityService.confirmation(batchId, uploadId, this)
            ?.let(RecoveryIdentityConfirmationResponse::from),
        selection = recoveryUploadIdentityService.selection(batchId, uploadId)
            ?.let(RecoveryUploadAssetSelectionResponse::from),
    )

    private fun RecoveryBatch.toResponse() = RecoveryBatchResponse(
        id = id,
        analysisRunId = analysisRunId,
        status = status.name,
        rightsDeclarationVersion = rightsDeclaration?.version,
        rightsDeclarationText = rightsDeclaration?.text,
        rightsDeclaredAt = rightsDeclaredAt,
        createdAt = createdAt,
        lastActivityAt = lastActivityAt,
        expiresAt = expiresAt,
        revision = revision,
        uploads = uploads.map { it.toResponse() },
        selectedAssets = selectedAssets.map(RecoveryUploadAssetSelectionResponse::from),
        identifierAssistance = identifierAssistance.map(RecoveryIdentifierAssistanceResponse::from),
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
