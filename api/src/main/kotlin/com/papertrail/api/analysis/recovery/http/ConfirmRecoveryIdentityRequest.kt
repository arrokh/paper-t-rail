package com.papertrail.api.analysis.recovery.http

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Pattern

@Schema(description = "Explicit human confirmation that the exact staged PDF is the cited work or intended alternate version. Optional DOI assistance is a separate confirmation of one extracted DOI candidate and its source provenance.")
data class ConfirmRecoveryIdentityRequest(
    @field:AssertTrue(message = "Explicitly confirm the exact PDF version before continuing.")
    val confirmExactVersion: Boolean,
    @field:Schema(description = "Canonical lower-case DOI explicitly confirmed as identifying the cited work; provide together with doiCandidateFingerprint only when using DOI assistance.", nullable = true, pattern = "^10\\.[0-9]{4,9}/[-._;()/:a-z0-9]+$")
    @field:Pattern(regexp = "^10\\.[0-9]{4,9}/[-._;()/:a-z0-9]+$", message = "Provide a normalized DOI candidate.")
    val confirmedDoi: String? = null,
    @field:Schema(description = "Versioned SHA-256 fingerprint of the exact DOI metadata candidate shown in the validation response; provide together with confirmedDoi.", nullable = true, pattern = "^recovery-metadata-candidate-v1:[0-9a-f]{64}$")
    @field:Pattern(regexp = "^recovery-metadata-candidate-v1:[0-9a-f]{64}$", message = "Choose a DOI candidate from this validation attempt.")
    val doiCandidateFingerprint: String? = null,
)
