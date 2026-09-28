package com.papertrail.api.evidence.verification.repository

import com.papertrail.api.evidence.verification.domain.EvidencePassageSpanDefinition
import java.util.UUID

data class PersistedEvidencePassageSpan(
    val id: UUID,
    val definition: EvidencePassageSpanDefinition,
    val status: String,
    val failureReason: String?,
    val definitionPolicyVersion: String,
    val providerId: String,
    val modelId: String,
    val providerVersion: String,
    val rubricVersion: String,
)
