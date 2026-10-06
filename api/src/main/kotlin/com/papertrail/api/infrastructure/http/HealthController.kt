package com.papertrail.api.infrastructure.http

import com.papertrail.api.http.HealthResponse
import com.papertrail.api.infrastructure.health.repository.DatabaseHealthRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/health", produces = [MediaType.APPLICATION_JSON_VALUE])
@Tag(name = "Health", description = "Check API liveness and database connectivity.")
class HealthController(private val databaseHealthRepository: DatabaseHealthRepository) {
    @Operation(
        summary = "Check API and database health",
        description = "Runs a lightweight query against the configured database. A successful response confirms that the API can reach the database.",
    )
    @ApiResponses(
        value = [
            ApiResponse(
                responseCode = "200",
                description = "The API is running and the database query succeeded",
                content = [Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = Schema(implementation = HealthResponse::class),
                )],
            ),
            ApiResponse(responseCode = "500", description = "The database health query failed"),
        ],
    )
    @GetMapping
    fun checkDatabase(): HealthResponse {
        databaseHealthRepository.checkConnectivity()
        return HealthResponse(status = "ok")
    }
}
