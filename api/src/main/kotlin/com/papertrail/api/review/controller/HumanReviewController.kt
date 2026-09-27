package com.papertrail.api.review.controller

import com.papertrail.api.http.ApiError
import com.papertrail.api.review.domain.HumanReview
import com.papertrail.api.review.http.CreateHumanReviewRequest
import com.papertrail.api.review.service.HumanReviewService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.parameters.RequestBody as OpenApiRequestBody
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/v1/verifications/{verificationId}/reviews")
@Tag(name = "Human Reviews", description = "Record append-only researcher assessments separately from machine results.")
class HumanReviewController(
    private val humanReviewService: HumanReviewService,
) {
    @Operation(
        summary = "Record a Human Review",
        description = "Appends a researcher assessment to a completed Claim–Paper Verification. OVERRIDE requires overrideStatus; AGREE and DISAGREE must omit it. The machine result is never changed.",
        requestBody = OpenApiRequestBody(
            required = true,
            content = [Content(
                mediaType = MediaType.APPLICATION_JSON_VALUE,
                schema = Schema(implementation = CreateHumanReviewRequest::class),
            )],
        ),
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "201", description = "Human Review appended", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = HumanReview::class))]),
            ApiResponse(responseCode = "400", description = "Review action, override status, or note is invalid", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "404", description = "Claim–Paper Verification not found", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
            ApiResponse(responseCode = "409", description = "The machine Verification does not have a completed result yet", content = [Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = Schema(implementation = ApiError::class))]),
        ],
    )
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun create(
        @PathVariable verificationId: UUID,
        @RequestBody request: CreateHumanReviewRequest,
    ): ResponseEntity<HumanReview> = ResponseEntity.status(HttpStatus.CREATED).body(
        humanReviewService.record(
            verificationId = verificationId,
            action = request.action,
            overrideStatus = request.overrideStatus,
            note = request.note,
        ),
    )
}
