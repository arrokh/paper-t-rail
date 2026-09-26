package com.papertrail.api.evidence.domain

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import java.util.UUID

data class EvidenceIndexingContext(
    val analysisRunId: UUID,
    val bibliographyEntryId: UUID,
    val canonicalPaperId: UUID,
    val objectKey: String,
    val contentSha256: String,
    val mediaType: String,
    val language: String,
    val languageDetectorVersion: String,
    val configuration: AnalysisConfigurationSnapshot,
    val claims: List<EvidenceClaim>,
)
