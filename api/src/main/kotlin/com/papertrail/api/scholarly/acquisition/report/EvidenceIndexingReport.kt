package com.papertrail.api.scholarly.acquisition.report

import java.util.UUID

data class EvidenceIndexingReport(
    val status: String,
    val failureReason: String?,
    val assetId: UUID?,
    val parserProvider: String?,
    val parserVersion: String?,
    val contentSha256: String?,
    val language: String?,
    val languageDetectorVersion: String?,
    val retrievalProfile: EvidenceRetrievalProfileReport,
)
