package com.papertrail.api.scholarly.acquisition.report

import java.util.UUID

data class EvidencePassageReport(
    val id: UUID,
    val text: String,
    val sectionOrder: Int,
    val sectionHeading: String?,
    val paragraphStart: Int,
    val paragraphEnd: Int,
    val pageNumber: Int?,
    val vectorRank: Int?,
    val lexicalRank: Int?,
    val fusedRank: Int,
    val fusionScore: Double,
    val sourceAssetId: UUID,
    val contentSha256: String,
    val parserProvider: String,
    val parserVersion: String,
    val language: String,
    val languageDetectorVersion: String,
    val retrievalProfile: EvidenceRetrievalProfileReport,
)
