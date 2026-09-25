package com.papertrail.api.references

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.runs.AnalysisConfigurationSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class ReferenceResolutionService(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val repository: ReferenceResolutionRepository,
    private val lookupFactories: List<ScholarlyMetadataLookupFactory>,
) {
    fun resolvePending(analysisRunId: UUID): ReferenceResolutionSummary {
        val context = loadRun(analysisRunId) ?: throw IllegalArgumentException("Analysis Run not found for reference resolution.")
        val referenceResolution = context.configuration.referenceResolution
        val providerId = referenceResolution.provider?.provider
        val threshold = referenceResolution.confidenceThreshold
        if (providerId == null || threshold == null || referenceResolution.scorePolicyVersion.isNullOrBlank() ||
            referenceResolution.executionStatus == "NOT_RUN"
        ) {
            return summarize(repository.reportEntries(analysisRunId))
        }
        val factory = lookupFactories.singleOrNull { it.providerId == providerId }
            ?: throw IllegalStateException("Configured scholarly metadata provider is unavailable.")
        val lookup = factory.forRun(context.configuration)
        val resolver = ConservativeReferenceResolver(
            scholarlyMetadata = lookup,
            matcher = ScholarlyMetadataMatcher(
                threshold = threshold,
                ambiguityMargin = ScholarlyMetadataMatcher.AMBIGUITY_MARGIN,
            ),
        )
        repository.pendingEntries(analysisRunId).forEach { stored ->
            val decision = resolver.resolve(
                BibliographyReference(stored.title, stored.authors, stored.year, stored.doi, stored.referenceType),
            )
            repository.save(analysisRunId, stored, decision, providerId)
        }
        return summarize(repository.reportEntries(analysisRunId))
    }

    fun report(analysisRunId: UUID): ReferenceResolutionReportResponse? {
        val context = loadRun(analysisRunId) ?: return null
        val entries = repository.reportEntries(analysisRunId)
        val summary = summarize(entries)
        val configuration = context.configuration.referenceResolution
        val policyConfigured = configuration.provider != null && !configuration.scorePolicyVersion.isNullOrBlank() && configuration.confidenceThreshold != null
        return ReferenceResolutionReportResponse(
            analysisRunId = analysisRunId,
            runStatus = context.runStatus,
            referenceResolution = ReferenceResolutionReport(
                executionStatus = when {
                    !policyConfigured || configuration.executionStatus == "NOT_RUN" -> "NOT_RUN"
                    summary.notAttempted == 0 && context.runStatus in setOf("PARSED", "COMPLETED", "COMPLETED_WITH_WARNINGS") -> "COMPLETED"
                    else -> "PENDING"
                },
                scorePolicyVersion = configuration.scorePolicyVersion,
                confidenceThreshold = configuration.confidenceThreshold,
                summary = summary,
                entries = entries,
            ),
        )
    }

    private fun summarize(entries: List<BibliographyResolutionReportEntry>) = ReferenceResolutionSummary(
        total = entries.size,
        resolved = entries.count { it.status == ReferenceResolutionStatus.RESOLVED.name },
        unresolved = entries.count { it.status == ReferenceResolutionStatus.UNRESOLVED.name },
        unsupportedReferenceType = entries.count { it.status == ReferenceResolutionStatus.UNSUPPORTED_REFERENCE_TYPE.name },
        notAttempted = entries.count { it.status == "NOT_ATTEMPTED" },
    )

    private fun loadRun(analysisRunId: UUID): RunResolutionContext? = jdbc.query(
        "SELECT status, configuration_snapshot::text AS configuration FROM analysis_runs WHERE id = ?",
        { rs, _ ->
            RunResolutionContext(
                runStatus = rs.getString("status"),
                configuration = objectMapper.readValue(rs.getString("configuration"), AnalysisConfigurationSnapshot::class.java),
            )
        },
        analysisRunId,
    ).firstOrNull()

    private data class RunResolutionContext(
        val runStatus: String,
        val configuration: AnalysisConfigurationSnapshot,
    )
}
