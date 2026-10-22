package com.papertrail.api.evidence.report

import io.swagger.v3.oas.annotations.media.Schema
import java.util.UUID

data class EvidencePassageReport(
    val id: UUID,
    val text: String,
    val sectionOrder: Int,
    val sectionHeading: String?,
    val paragraphStart: Int,
    val paragraphEnd: Int,
    @field:Schema(description = "First known one-based physical PDF page. pageNumbers contains all reliable pages when the passage spans multiple pages.")
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
    @field:Schema(description = "Optional parent-passage Evidence Judgement. Diagnostic span judgements are reported separately and never populate this field.")
    val evidenceJudgement: EvidenceJudgementReport?,
    @field:Schema(description = "Optional source-traceable Laya sentence-span diagnostics under this immutable parent passage. Span judgements are never rolled up into a parent Evidence Judgement or final Claim–Paper status.")
    val diagnosticSpans: List<EvidencePassageSpanReport> = emptyList(),
    @field:Schema(description = "Reliable one-based physical PDF pages in the exact stored Cited Paper Asset touched by this passage. Empty when parser provenance is unavailable or ambiguous.")
    val pageNumbers: List<Int> = emptyList(),
)
