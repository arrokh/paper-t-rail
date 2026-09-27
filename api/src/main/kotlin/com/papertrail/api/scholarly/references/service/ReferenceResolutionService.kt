package com.papertrail.api.scholarly.references.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookupFactory
import com.papertrail.api.scholarly.references.report.BibliographyResolutionReportEntry
import com.papertrail.api.scholarly.references.report.ReferenceResolutionReport
import com.papertrail.api.scholarly.references.report.ReferenceResolutionReportResponse
import com.papertrail.api.scholarly.references.report.ReferenceResolutionSummary
import com.papertrail.api.scholarly.references.resolver.ReferenceResolutionStatus
import com.papertrail.api.scholarly.references.repository.ReferenceResolutionRepository
import com.papertrail.api.scholarly.acquisition.repository.CitedPaperAccessRepository
import com.papertrail.api.scholarly.references.resolver.ConservativeReferenceResolver
import com.papertrail.api.scholarly.references.resolver.ScholarlyMetadataMatcher
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ReferenceResolutionSnapshot
import com.papertrail.api.evidence.report.EvidenceCoverageReport
import com.papertrail.api.evidence.report.EvidenceCoverageReportRepository
import com.papertrail.api.evidence.verification.repository.ClaimReferenceVerificationRepository
import com.papertrail.api.document.service.lockActiveAnalysisRun
import com.papertrail.api.document.service.requireActiveAnalysisRun
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

@Service
class ReferenceResolutionService(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
    private val claimReferenceVerificationRepository: ClaimReferenceVerificationRepository,
    private val repository: ReferenceResolutionRepository,
    private val citedPaperAccessRepository: CitedPaperAccessRepository,
    private val evidenceCoverageReportRepository: EvidenceCoverageReportRepository,
    private val lookupFactories: List<ScholarlyMetadataLookupFactory>,
) {
    fun isResolutionConfigured(analysisRunId: UUID): Boolean {
        val context = loadRun(analysisRunId) ?: throw IllegalArgumentException("Analysis Run not found for reference resolution.")
        return context.configuration.referenceResolution.isConfigured()
    }

    fun resolveEntry(analysisRunId: UUID, bibliographyEntryId: UUID) {
        val context = loadRun(analysisRunId) ?: throw IllegalArgumentException("Analysis Run not found for reference resolution.")
        val configuration = context.configuration.referenceResolution
        require(configuration.isConfigured()) { "Reference resolution is not configured for this Analysis Run." }
        val stored = repository.pendingEntry(analysisRunId, bibliographyEntryId)
        if (stored == null) {
            if (repository.entryExists(analysisRunId, bibliographyEntryId) &&
                repository.resolutionExists(analysisRunId, bibliographyEntryId)
            ) return
            throw IllegalArgumentException("Bibliography Entry is not part of this Analysis Run or has no pending resolution.")
        }
        val providerId = requireNotNull(configuration.provider?.provider)
        val threshold = requireNotNull(configuration.confidenceThreshold)
        val factory = lookupFactories.singleOrNull { it.providerId == providerId }
            ?: throw IllegalStateException("Configured scholarly metadata provider is unavailable.")
        val resolver = ConservativeReferenceResolver(
            scholarlyMetadata = factory.forRun(context.configuration),
            matcher = ScholarlyMetadataMatcher(
                threshold = threshold,
                ambiguityMargin = ScholarlyMetadataMatcher.AMBIGUITY_MARGIN,
            ),
        )
        jdbc.requireActiveAnalysisRun(analysisRunId)
        val decision = resolver.resolve(
            BibliographyReference(stored.title, stored.authors, stored.year, stored.doi, stored.referenceType),
        )
        transactionTemplate.executeWithoutResult {
            jdbc.lockActiveAnalysisRun(analysisRunId)
            val canonicalPaperId = repository.save(analysisRunId, stored, decision, providerId)
            claimReferenceVerificationRepository.applyResolution(
                analysisRunId = analysisRunId,
                bibliographyEntryId = stored.id,
                status = decision.status,
                reason = decision.reasonCode,
                canonicalPaperId = canonicalPaperId,
            )
        }
    }

    fun failResolution(analysisRunId: UUID, bibliographyEntryId: UUID, reason: String) {
        claimReferenceVerificationRepository.failReference(analysisRunId, bibliographyEntryId, reason)
    }

    fun summary(analysisRunId: UUID): ReferenceResolutionSummary = summarize(repository.reportEntries(analysisRunId))

    fun report(analysisRunId: UUID): ReferenceResolutionReportResponse? {
        val context = loadRun(analysisRunId) ?: return null
        val entries = repository.reportEntries(analysisRunId)
        val indexingByReference = evidenceCoverageReportRepository.indexingReportsByReference(analysisRunId)
        val accessByReference = citedPaperAccessRepository.reportEntries(analysisRunId).associate { accessEntry ->
            accessEntry.localReferenceKey to accessEntry.report.copy(
                evidenceIndexing = indexingByReference[accessEntry.bibliographyEntryId],
            )
        }
        val outcomesByReference = evidenceCoverageReportRepository.outcomesByReference(analysisRunId)
        val reportEntries = entries.map { entry ->
            val outcomes = outcomesByReference[entry.localReferenceKey].orEmpty()
            entry.copy(
                citedPaperAccess = accessByReference[entry.localReferenceKey]?.copy(verificationOutcomes = outcomes),
                verificationOutcomes = outcomes,
            )
        }
        val summary = summarize(entries)
        val aggregation = context.configuration.aggregation
        val configuration = context.configuration.referenceResolution
        val policyConfigured = configuration.provider != null && !configuration.scorePolicyVersion.isNullOrBlank() && configuration.confidenceThreshold != null
        return ReferenceResolutionReportResponse(
            analysisRunId = analysisRunId,
            runStatus = context.runStatus,
            evidenceCoverage = EvidenceCoverageReport(
                executionStatus = when {
                    !context.semanticPipelineConfigured -> "NOT_RUN"
                    context.runStatus == "COMPLETED" -> "COMPLETED"
                    context.runStatus == "COMPLETED_WITH_WARNINGS" -> "COMPLETED_WITH_WARNINGS"
                    context.runStatus == "FAILED" -> "FAILED"
                    else -> "PENDING"
                },
                verificationPolicyVersion = aggregation.verificationPolicyVersion,
                aggregationPolicyVersion = aggregation.aggregationPolicyVersion,
                thresholds = aggregation.thresholds,
                summary = evidenceCoverageReportRepository.summary(analysisRunId),
            ),
            referenceResolution = ReferenceResolutionReport(
                executionStatus = when {
                    !policyConfigured || configuration.executionStatus == "NOT_RUN" -> "NOT_RUN"
                    context.runStatus == "COMPLETED_WITH_WARNINGS" -> "COMPLETED_WITH_WARNINGS"
                    summary.notAttempted == 0 && summary.failed == 0 && context.runStatus in setOf("PARSED", "COMPLETED") -> "COMPLETED"
                    else -> "PENDING"
                },
                scorePolicyVersion = configuration.scorePolicyVersion,
                confidenceThreshold = configuration.confidenceThreshold,
                summary = summary,
                entries = reportEntries,
            ),
        )
    }

    private fun summarize(entries: List<BibliographyResolutionReportEntry>) = ReferenceResolutionSummary(
        total = entries.size,
        resolved = entries.count { it.status == ReferenceResolutionStatus.RESOLVED.name },
        unresolved = entries.count { it.status == ReferenceResolutionStatus.UNRESOLVED.name },
        unsupportedReferenceType = entries.count { it.status == ReferenceResolutionStatus.UNSUPPORTED_REFERENCE_TYPE.name },
        notAttempted = entries.count { it.status == "NOT_ATTEMPTED" },
        failed = entries.count { it.status == "RESOLUTION_FAILED" },
    )

    private fun ReferenceResolutionSnapshot.isConfigured(): Boolean =
        provider?.provider != null && !scorePolicyVersion.isNullOrBlank() && confidenceThreshold != null && executionStatus != "NOT_RUN"

    private fun loadRun(analysisRunId: UUID): RunResolutionContext? = jdbc.query(
        """
        SELECT status,
               configuration_snapshot::text AS configuration,
               analysis_run_has_conflict_aware_evidence_coverage(configuration_snapshot) AS semantic_pipeline_configured
          FROM analysis_runs run
          JOIN source_documents document ON document.id = run.document_id
         WHERE run.id = ?
           AND NOT EXISTS (
               SELECT 1 FROM source_document_tombstones tombstone WHERE tombstone.document_id = document.id
           )
        """.trimIndent(),
        { rs, _ ->
            RunResolutionContext(
                runStatus = rs.getString("status"),
                configuration = objectMapper.readValue(rs.getString("configuration"), AnalysisConfigurationSnapshot::class.java),
                semanticPipelineConfigured = rs.getBoolean("semantic_pipeline_configured"),
            )
        },
        analysisRunId,
    ).firstOrNull()

    private data class RunResolutionContext(
        val runStatus: String,
        val configuration: AnalysisConfigurationSnapshot,
        val semanticPipelineConfigured: Boolean,
    )
}
