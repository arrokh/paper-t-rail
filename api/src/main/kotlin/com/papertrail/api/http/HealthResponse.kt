package com.papertrail.api.http

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "Liveness result returned after the database health query succeeds.")
data class HealthResponse(
    @field:Schema(description = "Health status. The endpoint returns `ok` when the database query succeeds.", example = "ok")
    val status: String,
)
