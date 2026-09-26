package com.papertrail.api.scholarly.acquisition.report

import java.time.Instant

data class CitedPaperAccessReport(
    val accessStatus: String,
    val accessReason: String?,
    val providerId: String,
    val sourceUrl: String?,
    val license: String?,
    val version: String?,
    val hostType: String?,
    val discoveredAt: Instant,
    val contentSha256: String?,
    val language: String?,
    val languageDetectorVersion: String?,
    val verificationOutcomes: List<CitedReferenceVerificationOutcome>,
    val evidenceIndexing: EvidenceIndexingReport? = null,
)
