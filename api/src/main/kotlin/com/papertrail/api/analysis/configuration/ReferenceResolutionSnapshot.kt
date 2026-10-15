package com.papertrail.api.analysis.configuration

import com.fasterxml.jackson.annotation.JsonInclude
import io.swagger.v3.oas.annotations.media.Schema

data class ReferenceResolutionSnapshot(
    val executionStatus: String = "NOT_RUN",
    val provider: ProviderSelection? = null,
    val scorePolicyVersion: String? = null,
    val confidenceThreshold: Double? = null,
    val providerConfigurationFingerprint: String? = null,
    @field:Schema(description = "Nullable immutable versioned provider chain for new chain runs. Null preserves the legacy single-provider path and historical snapshots.")
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val providerChain: ProviderChainSnapshot? = null,
)
