package com.papertrail.api.references

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.runs.AnalysisConfigurationSnapshot
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.sql.ResultSet
import java.util.UUID

@Repository
class ReferenceResolutionRepository(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    private val transactionTemplate: TransactionTemplate,
) {
    fun pendingEntries(analysisRunId: UUID): List<StoredBibliographyReference> = jdbc.query(
        """
        SELECT b.id, b.entry_order, b.local_reference_key, b.raw_text, b.parsed_title,
               b.parsed_authors::text AS parsed_authors, b.parsed_year, b.parsed_doi, b.reference_type
          FROM bibliography_entries b
          LEFT JOIN bibliography_entry_resolutions r
            ON r.analysis_run_id = b.analysis_run_id AND r.bibliography_entry_id = b.id
         WHERE b.analysis_run_id = ? AND r.bibliography_entry_id IS NULL
         ORDER BY b.entry_order
        """.trimIndent(),
        { rs, _ -> rs.toStoredReference() },
        analysisRunId,
    )

    fun save(
        analysisRunId: UUID,
        reference: StoredBibliographyReference,
        decision: ReferenceResolutionDecision,
        providerId: String,
    ) {
        transactionTemplate.executeWithoutResult {
            val canonicalPaperId = decision.work?.let { work -> saveCanonicalPaper(work, providerId) }
            jdbc.update(
                """
                INSERT INTO bibliography_entry_resolutions (
                    analysis_run_id, bibliography_entry_id, status, reason_code, canonical_paper_id,
                    matched_doi, matched_title, matched_authors, matched_year, confidence_score,
                    match_method, provider_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)
                ON CONFLICT (analysis_run_id, bibliography_entry_id) DO NOTHING
                """.trimIndent(),
                analysisRunId,
                reference.id,
                decision.status.name,
                decision.reasonCode,
                canonicalPaperId,
                decision.work?.doi?.let(DoiNormalizer::normalize),
                decision.work?.title,
                objectMapper.writeValueAsString(decision.work?.authors.orEmpty()),
                decision.work?.year,
                decision.score,
                decision.matchMethod,
                providerId,
            )
        }
    }

    fun reportEntries(analysisRunId: UUID): List<BibliographyResolutionReportEntry> = jdbc.query(
        """
        SELECT b.entry_order, b.local_reference_key, b.raw_text, b.parsed_title,
               b.parsed_authors::text AS parsed_authors, b.parsed_year, b.parsed_doi, b.reference_type,
               r.status, r.reason_code, r.canonical_paper_id, r.matched_doi, r.matched_title,
               r.matched_authors::text AS matched_authors, r.matched_year, r.confidence_score,
               r.match_method
          FROM bibliography_entries b
          LEFT JOIN bibliography_entry_resolutions r
            ON r.analysis_run_id = b.analysis_run_id AND r.bibliography_entry_id = b.id
         WHERE b.analysis_run_id = ?
         ORDER BY b.entry_order
        """.trimIndent(),
        { rs, _ -> rs.toReportEntry() },
        analysisRunId,
    )

    private fun saveCanonicalPaper(work: ScholarlyWork, providerId: String): UUID {
        val doi = DoiNormalizer.normalize(work.doi)
        val identityKey = doi?.let { "doi:$it" } ?: metadataIdentityKey(work)
        return jdbc.queryForObject(
            """
            INSERT INTO canonical_papers (
                id, identity_key, doi, title, authors, publication_year, metadata_provider_id
            ) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
            ON CONFLICT (identity_key) DO UPDATE SET identity_key = canonical_papers.identity_key
            RETURNING id
            """.trimIndent(),
            UUID::class.java,
            UUID.randomUUID(),
            identityKey,
            doi,
            work.title,
            objectMapper.writeValueAsString(work.authors),
            work.year,
            providerId,
        ) ?: error("Canonical Paper identity could not be persisted.")
    }

    private fun metadataIdentityKey(work: ScholarlyWork): String {
        val canonicalMetadata = listOf(
            work.title.trim().lowercase(),
            work.authors.map { it.trim().lowercase() }.sorted().joinToString("|"),
            work.year?.toString().orEmpty(),
        ).joinToString("\u0000")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(canonicalMetadata.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "metadata:$digest"
    }

    private fun ResultSet.toStoredReference() = StoredBibliographyReference(
        id = getObject("id", UUID::class.java),
        entryOrder = getInt("entry_order"),
        localReferenceKey = getString("local_reference_key"),
        rawText = getString("raw_text"),
        title = getString("parsed_title"),
        authors = objectMapper.readValue(getString("parsed_authors"), objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java)),
        year = getObject("parsed_year", Integer::class.java)?.toInt(),
        doi = getString("parsed_doi"),
        referenceType = getString("reference_type"),
    )

    private fun ResultSet.toReportEntry(): BibliographyResolutionReportEntry {
        val status = getString("status")
        val canonicalPaperId = getObject("canonical_paper_id", UUID::class.java)
        val candidate = getString("matched_title")?.let { title ->
            ReportCanonicalPaper(
                id = requireNotNull(canonicalPaperId) { "Resolved Bibliography Entry is missing its Canonical Paper identity." },
                doi = getString("matched_doi"),
                title = title,
                authors = objectMapper.readValue(getString("matched_authors"), objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java)),
                year = getObject("matched_year", Integer::class.java)?.toInt(),
            )
        }
        return BibliographyResolutionReportEntry(
            entryOrder = getInt("entry_order"),
            localReferenceKey = getString("local_reference_key"),
            rawText = getString("raw_text"),
            title = getString("parsed_title"),
            authors = objectMapper.readValue(getString("parsed_authors"), objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java)),
            year = getObject("parsed_year", Integer::class.java)?.toInt(),
            doi = getString("parsed_doi"),
            referenceType = getString("reference_type"),
            status = status ?: "NOT_ATTEMPTED",
            reasonCode = getString("reason_code"),
            canonicalPaper = candidate,
            confidenceScore = getObject("confidence_score", java.lang.Double::class.java)?.toDouble(),
            matchMethod = getString("match_method"),
        )
    }
}

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

    fun report(analysisRunId: UUID): EvidenceCoverageReport? {
        val context = loadRun(analysisRunId) ?: return null
        val entries = repository.reportEntries(analysisRunId)
        val summary = summarize(entries)
        val configuration = context.configuration.referenceResolution
        val policyConfigured = configuration.provider != null && !configuration.scorePolicyVersion.isNullOrBlank() && configuration.confidenceThreshold != null
        return EvidenceCoverageReport(
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
}

private data class RunResolutionContext(
    val runStatus: String,
    val configuration: AnalysisConfigurationSnapshot,
)

data class StoredBibliographyReference(
    val id: UUID,
    val entryOrder: Int,
    val localReferenceKey: String,
    val rawText: String,
    val title: String?,
    val authors: List<String>,
    val year: Int?,
    val doi: String?,
    val referenceType: String,
)

data class EvidenceCoverageReport(
    val analysisRunId: UUID,
    val runStatus: String,
    val referenceResolution: ReferenceResolutionReport,
)

data class ReferenceResolutionReport(
    val executionStatus: String,
    val scorePolicyVersion: String?,
    val confidenceThreshold: Double?,
    val summary: ReferenceResolutionSummary,
    val entries: List<BibliographyResolutionReportEntry>,
)

data class ReferenceResolutionSummary(
    val total: Int,
    val resolved: Int,
    val unresolved: Int,
    val unsupportedReferenceType: Int,
    val notAttempted: Int,
)

data class BibliographyResolutionReportEntry(
    val entryOrder: Int,
    val localReferenceKey: String,
    val rawText: String,
    val title: String?,
    val authors: List<String>,
    val year: Int?,
    val doi: String?,
    val referenceType: String,
    val status: String,
    val reasonCode: String?,
    val canonicalPaper: ReportCanonicalPaper?,
    val confidenceScore: Double?,
    val matchMethod: String?,
)

data class ReportCanonicalPaper(
    val id: UUID,
    val doi: String?,
    val title: String,
    val authors: List<String>,
    val year: Int?,
)
