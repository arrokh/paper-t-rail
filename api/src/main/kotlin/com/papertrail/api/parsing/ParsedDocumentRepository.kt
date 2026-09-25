package com.papertrail.api.parsing

import com.fasterxml.jackson.databind.ObjectMapper
import com.papertrail.api.claims.CitationContextClaims
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class ParsedDocumentRepository(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    fun save(
        analysisRunId: UUID,
        sourceContentSha256: String,
        parsed: ParsedScientificDocument,
        rawTeiObjectKey: String,
        extractedClaims: List<CitationContextClaims>,
    ) {
        val claimsByContextSpan = extractedClaims.associateBy { it.contextStartOffset to it.contextEndOffset }
        require(claimsByContextSpan.size == extractedClaims.size &&
            claimsByContextSpan.keys == parsed.citationContexts.map { it.startOffset to it.endOffset }.toSet()
        ) { "Extracted Atomic Claims must match every Citation Context exactly once." }
        jdbc.update(
            """
            INSERT INTO parsed_document_parses (
                analysis_run_id, source_content_sha256, parser_id, parser_version, normalized_source_text, raw_tei_object_key
            ) VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            analysisRunId,
            sourceContentSha256,
            parsed.parserId,
            parsed.parserVersion,
            parsed.normalizedSourceText,
            rawTeiObjectKey,
        )

        val sectionIds = parsed.sections.associate { it.sectionOrder to UUID.randomUUID() }
        parsed.sections.forEach { section ->
            jdbc.update(
                """
                INSERT INTO parsed_document_sections (
                    id, analysis_run_id, section_order, heading, text, start_offset, end_offset
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                sectionIds.getValue(section.sectionOrder),
                analysisRunId,
                section.sectionOrder,
                section.heading,
                section.text,
                section.startOffset,
                section.endOffset,
            )
        }

        val referenceIds = parsed.bibliographyEntries.associate { it.localReferenceKey to UUID.randomUUID() }
        parsed.bibliographyEntries.forEach { entry ->
            jdbc.update(
                """
                INSERT INTO bibliography_entries (
                    id, analysis_run_id, entry_order, local_reference_key, raw_text, parsed_title,
                    parsed_authors, parsed_year, parsed_doi, reference_type, resolution_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'NOT_ATTEMPTED')
                """.trimIndent(),
                referenceIds.getValue(entry.localReferenceKey),
                analysisRunId,
                entry.entryOrder,
                entry.localReferenceKey,
                entry.rawText,
                entry.title,
                objectMapper.writeValueAsString(entry.authors),
                entry.year,
                entry.doi,
                entry.referenceType,
            )
        }

        parsed.citationContexts.forEach { context ->
            val contextId = UUID.randomUUID()
            val contextTargetIds = mutableListOf<UUID>()
            val sectionId = sectionIds.getValue(context.sectionOrder)
            jdbc.update(
                """
                INSERT INTO citation_contexts (
                    id, analysis_run_id, section_id, context_text, boundary_kind, start_offset, end_offset
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                contextId,
                analysisRunId,
                sectionId,
                context.text,
                context.boundaryKind,
                context.startOffset,
                context.endOffset,
            )
            context.occurrences.forEach { occurrence ->
                val occurrenceId = UUID.randomUUID()
                jdbc.update(
                    """
                    INSERT INTO citation_occurrences (
                        id, analysis_run_id, citation_context_id, section_id,
                        marker_text, start_offset, end_offset
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    occurrenceId,
                    analysisRunId,
                    contextId,
                    sectionId,
                    occurrence.markerText,
                    occurrence.startOffset,
                    occurrence.endOffset,
                )
                occurrence.bibliographyReferenceKeys.distinct()
                    .forEachIndexed { targetOrder, referenceKey ->
                        val referenceId = referenceIds[referenceKey] ?: return@forEachIndexed
                        jdbc.update(
                            """
                            INSERT INTO citation_targets (
                                id, analysis_run_id, citation_context_id, citation_occurrence_id, bibliography_entry_id, target_order
                            ) VALUES (?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            UUID.randomUUID().also { contextTargetIds.add(it) },
                            analysisRunId,
                            contextId,
                            occurrenceId,
                            referenceId,
                            targetOrder,
                        )
                    }
            }

            val contextClaims = claimsByContextSpan.getValue(context.startOffset to context.endOffset)
            contextClaims.claims.forEach { claim ->
                val claimId = UUID.randomUUID()
                jdbc.update(
                    """
                    INSERT INTO atomic_claims (
                        id, analysis_run_id, citation_context_id, claim_text, source_start_offset, source_end_offset
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    claimId,
                    analysisRunId,
                    contextId,
                    claim.text,
                    claim.sourceStartOffset,
                    claim.sourceEndOffset,
                )
                contextTargetIds.distinct().forEach { targetId ->
                    jdbc.update(
                        """
                        INSERT INTO atomic_claim_citation_targets (
                            id, analysis_run_id, citation_context_id, atomic_claim_id, citation_target_id, association_kind
                        ) VALUES (?, ?, ?, ?, ?, 'INFERRED_PROVISIONAL')
                        """.trimIndent(),
                        UUID.randomUUID(),
                        analysisRunId,
                        contextId,
                        claimId,
                        targetId,
                    )
                }
            }
        }
    }

    fun rawTeiObjectKey(analysisRunId: UUID): String? = jdbc.query(
        "SELECT raw_tei_object_key FROM parsed_document_parses WHERE analysis_run_id = ?",
        { rs, _ -> rs.getString("raw_tei_object_key") },
        analysisRunId,
    ).firstOrNull()

    fun find(analysisRunId: UUID): ParsedDocumentView? {
        val parse = jdbc.query(
            """
            SELECT parser_id, parser_version, source_content_sha256, normalized_source_text
              FROM parsed_document_parses WHERE analysis_run_id = ?
            """.trimIndent(),
            { rs, _ -> ParseRow(rs.getString("parser_id"), rs.getString("parser_version"), rs.getString("source_content_sha256"), rs.getString("normalized_source_text")) },
            analysisRunId,
        ).firstOrNull() ?: return null
        val sections = jdbc.query(
            """
            SELECT id, section_order, heading, text, start_offset, end_offset
              FROM parsed_document_sections WHERE analysis_run_id = ? ORDER BY section_order
            """.trimIndent(),
            { rs, _ -> ParsedSectionView(rs.getObject("id", UUID::class.java), rs.getInt("section_order"), rs.getString("heading"), rs.getString("text"), rs.getInt("start_offset"), rs.getInt("end_offset")) },
            analysisRunId,
        )
        val bibliographyEntries = jdbc.query(
            """
            SELECT entry.entry_order, entry.local_reference_key, entry.raw_text, entry.parsed_title,
                   entry.parsed_authors::text AS parsed_authors, entry.parsed_year, entry.parsed_doi,
                   entry.reference_type, COALESCE(resolution.status, entry.resolution_status) AS resolution_status
              FROM bibliography_entries entry
              LEFT JOIN bibliography_entry_resolutions resolution
                ON resolution.analysis_run_id = entry.analysis_run_id
               AND resolution.bibliography_entry_id = entry.id
             WHERE entry.analysis_run_id = ? ORDER BY entry.entry_order
            """.trimIndent(),
            { rs, _ -> rs.toBibliographyEntryView() },
            analysisRunId,
        )
        val occurrencesByContext = linkedMapOf<UUID, MutableList<ParsedCitationOccurrenceView>>()
        val occurrenceRows = jdbc.query(
            """
            SELECT o.id, o.citation_context_id, o.marker_text, o.start_offset, o.end_offset,
                   b.local_reference_key
              FROM citation_occurrences o
              LEFT JOIN citation_targets t
                ON t.analysis_run_id = o.analysis_run_id AND t.citation_occurrence_id = o.id
              LEFT JOIN bibliography_entries b
                ON b.analysis_run_id = t.analysis_run_id AND b.id = t.bibliography_entry_id
             WHERE o.analysis_run_id = ?
             ORDER BY o.start_offset, t.target_order
            """.trimIndent(),
            { rs, _ -> rs.toOccurrenceRow() },
            analysisRunId,
        )
        occurrenceRows.groupBy { it.contextId }.forEach { (contextId, rows) ->
            occurrencesByContext[contextId] = rows.groupBy { it.id }.values.map { duplicateRows ->
                val first = duplicateRows.first()
                ParsedCitationOccurrenceView(
                    id = first.id,
                    markerText = first.markerText,
                    startOffset = first.startOffset,
                    endOffset = first.endOffset,
                    bibliographyReferenceKeys = duplicateRows.mapNotNull { it.referenceKey }.distinct(),
                )
            }.toMutableList()
        }
        val claimsByContext = linkedMapOf<UUID, MutableList<ParsedAtomicClaimView>>()
        val claimRows = jdbc.query(
            """
            SELECT c.id, c.citation_context_id, c.claim_text, c.source_start_offset, c.source_end_offset,
                   link.association_kind, target.id AS target_id, occurrence.marker_text,
                   entry.local_reference_key, entry.parsed_title
              FROM atomic_claims c
              LEFT JOIN atomic_claim_citation_targets link
                ON link.analysis_run_id = c.analysis_run_id AND link.atomic_claim_id = c.id
              LEFT JOIN citation_targets target
                ON target.analysis_run_id = link.analysis_run_id
               AND target.id = link.citation_target_id
               AND target.citation_context_id = link.citation_context_id
              LEFT JOIN citation_occurrences occurrence
                ON occurrence.analysis_run_id = target.analysis_run_id
               AND occurrence.id = target.citation_occurrence_id
              LEFT JOIN bibliography_entries entry
                ON entry.analysis_run_id = target.analysis_run_id
               AND entry.id = target.bibliography_entry_id
             WHERE c.analysis_run_id = ?
             ORDER BY c.source_start_offset, occurrence.start_offset, target.target_order
            """.trimIndent(),
            { rs, _ -> rs.toClaimTargetRow() },
            analysisRunId,
        )
        claimRows.groupBy(ClaimTargetRow::contextId).forEach { (contextId, rows) ->
            claimsByContext[contextId] = rows.groupBy(ClaimTargetRow::claimId).values.map { duplicateRows ->
                val first = duplicateRows.first()
                ParsedAtomicClaimView(
                    id = first.claimId,
                    text = first.text,
                    sourceStartOffset = first.sourceStartOffset,
                    sourceEndOffset = first.sourceEndOffset,
                    citationTargets = duplicateRows.mapNotNull { row ->
                        val targetId = row.targetId ?: return@mapNotNull null
                        ParsedClaimCitationTargetView(
                            id = targetId,
                            markerText = row.markerText ?: return@mapNotNull null,
                            bibliographyReferenceKey = row.referenceKey ?: return@mapNotNull null,
                            bibliographyTitle = row.referenceTitle,
                            associationKind = row.associationKind ?: return@mapNotNull null,
                        )
                    }.distinctBy(ParsedClaimCitationTargetView::id),
                )
            }.toMutableList()
        }
        val contexts = jdbc.query(
            """
            SELECT id, section_id, context_text, boundary_kind, start_offset, end_offset
              FROM citation_contexts WHERE analysis_run_id = ? ORDER BY start_offset
            """.trimIndent(),
            { rs, _ ->
                val id = rs.getObject("id", UUID::class.java)
                ParsedCitationContextView(
                    id = id,
                    sectionId = rs.getObject("section_id", UUID::class.java),
                    boundaryKind = rs.getString("boundary_kind"),
                    text = rs.getString("context_text"),
                    startOffset = rs.getInt("start_offset"),
                    endOffset = rs.getInt("end_offset"),
                    occurrences = occurrencesByContext[id].orEmpty(),
                    atomicClaims = claimsByContext[id].orEmpty(),
                )
            },
            analysisRunId,
        )
        return ParsedDocumentView(
            parser = ParsedParserProvenance(parse.parserId, parse.parserVersion),
            sourceContentSha256 = parse.sourceContentSha256,
            normalizedSourceText = parse.normalizedSourceText,
            sections = sections,
            citationContexts = contexts,
            bibliographyEntries = bibliographyEntries,
        )
    }

    private fun ResultSet.toClaimTargetRow() = ClaimTargetRow(
        claimId = getObject("id", UUID::class.java),
        contextId = getObject("citation_context_id", UUID::class.java),
        text = getString("claim_text"),
        sourceStartOffset = getInt("source_start_offset"),
        sourceEndOffset = getInt("source_end_offset"),
        associationKind = getString("association_kind"),
        targetId = getObject("target_id", UUID::class.java),
        markerText = getString("marker_text"),
        referenceKey = getString("local_reference_key"),
        referenceTitle = getString("parsed_title"),
    )

    private fun ResultSet.toOccurrenceRow() = OccurrenceRow(
        id = getObject("id", UUID::class.java),
        contextId = getObject("citation_context_id", UUID::class.java),
        markerText = getString("marker_text"),
        startOffset = getInt("start_offset"),
        endOffset = getInt("end_offset"),
        referenceKey = getString("local_reference_key"),
    )

    private fun ResultSet.toBibliographyEntryView() = ParsedBibliographyEntryView(
        entryOrder = getInt("entry_order"),
        localReferenceKey = getString("local_reference_key"),
        rawText = getString("raw_text"),
        title = getString("parsed_title"),
        authors = objectMapper.readValue(getString("parsed_authors"), objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java)),
        year = getObject("parsed_year", Integer::class.java)?.toInt(),
        doi = getString("parsed_doi"),
        referenceType = getString("reference_type"),
        resolutionStatus = getString("resolution_status"),
    )

    private data class ClaimTargetRow(
        val claimId: UUID,
        val contextId: UUID,
        val text: String,
        val sourceStartOffset: Int,
        val sourceEndOffset: Int,
        val associationKind: String?,
        val targetId: UUID?,
        val markerText: String?,
        val referenceKey: String?,
        val referenceTitle: String?,
    )
    private data class ParseRow(val parserId: String, val parserVersion: String, val sourceContentSha256: String, val normalizedSourceText: String)
    private data class OccurrenceRow(val id: UUID, val contextId: UUID, val markerText: String, val startOffset: Int, val endOffset: Int, val referenceKey: String?)
}

data class ParsedDocumentView(
    val parser: ParsedParserProvenance,
    val sourceContentSha256: String,
    val normalizedSourceText: String,
    val sections: List<ParsedSectionView>,
    val citationContexts: List<ParsedCitationContextView>,
    val bibliographyEntries: List<ParsedBibliographyEntryView>,
)

data class ParsedParserProvenance(val provider: String, val version: String)
data class ParsedSectionView(val id: UUID, val sectionOrder: Int, val heading: String?, val text: String, val startOffset: Int, val endOffset: Int)
data class ParsedCitationOccurrenceView(val id: UUID, val markerText: String, val startOffset: Int, val endOffset: Int, val bibliographyReferenceKeys: List<String>)
data class ParsedCitationContextView(
    val id: UUID,
    val sectionId: UUID,
    val boundaryKind: String,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val occurrences: List<ParsedCitationOccurrenceView>,
    val atomicClaims: List<ParsedAtomicClaimView> = emptyList(),
)
data class ParsedAtomicClaimView(
    val id: UUID,
    val text: String,
    val sourceStartOffset: Int,
    val sourceEndOffset: Int,
    val citationTargets: List<ParsedClaimCitationTargetView>,
)
data class ParsedClaimCitationTargetView(
    val id: UUID,
    val markerText: String,
    val bibliographyReferenceKey: String,
    val bibliographyTitle: String?,
    val associationKind: String,
)
data class ParsedBibliographyEntryView(val entryOrder: Int, val localReferenceKey: String, val rawText: String, val title: String?, val authors: List<String>, val year: Int?, val doi: String?, val referenceType: String, val resolutionStatus: String)
