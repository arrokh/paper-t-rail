package com.papertrail.api.openapi

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.info.Info
import org.springframework.context.annotation.Configuration

@Configuration
@OpenAPIDefinition(
    info = Info(
        title = "Paper T-Rail API",
        version = "0.1.0",
        description = "Local, unauthenticated API for Source Document ingestion and immutable Analysis Runs.",
    ),
)
class OpenApiConfiguration
