package com.papertrail.api.citation.repository

import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.analysis.http.ParsedAtomicClaimView
import com.papertrail.api.analysis.http.ParsedBibliographyEntryView
import com.papertrail.api.analysis.http.ParsedCitationContextView
import com.papertrail.api.analysis.http.ParsedCitationOccurrenceView
import com.papertrail.api.analysis.http.ParsedClaimCitationTargetView
import com.papertrail.api.analysis.http.ParsedDocumentView
import com.papertrail.api.analysis.http.ParsedParserProvenance
import com.papertrail.api.analysis.http.ParsedSectionView
import com.papertrail.api.citation.claims.domain.CitationContextClaims
import com.papertrail.api.citation.claims.domain.CitationTargetKey
import com.papertrail.api.citation.parsing.BibliographyNormalizationPolicySelection
import com.papertrail.api.citation.parsing.ParsedBibliographyIdentifier
import com.papertrail.api.citation.parsing.ParsedBibliographySourceLocation
import com.papertrail.api.citation.parsing.ParsedScientificDocument
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class ParsedDocumentRepository(
    private val jdbc: JdbcTemplate,
) {
    fun save(
        analysisRunId: UUID,
        sourceContentSha256: String,
        parsed: ParsedScientificDocument,
        rawTeiObjectKey: String,
        extractedClaims: List<CitationContextClaims>,
    ) {
        val bibliographyNormalizationPolicy = requireNotNull(parsed.bibliographyNormalizationPolicy) {
            "The source parser did not report the pinned bibliography normalization policy."
        }
        val claimsByContextSpan = extractedClaims.associateBy { it.contextStartOffset to it.contextEndOffset }
        require(claimsByContextSpan.size == extractedClaims.size &&
            claimsByContextSpan.keys == parsed.citationContexts.map { it.startOffset to it.endOffset }.toSet()
        ) { "Extracted Atomic Claims must match every Citation Context exactly once." }
        jdbc.update(
            """
            INSERT INTO parsed_document_parses (
                analysis_run_id, source_content_sha256, parser_id, parser_version, normalized_source_text, raw_tei_object_key,
                bibliography_normalization_policy_id, bibliography_normalization_policy_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            analysisRunId,
            sourceContentSha256,
            parsed.parserId,
            parsed.parserVersion,
            parsed.normalizedSourceText,
            rawTeiObjectKey,
            bibliographyNormalizationPolicy.policyId,
            bibliographyNormalizationPolicy.version,
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
                    parsed_authors, parsed_year, parsed_doi, reference_type, resolution_status,
                    source_element, source_text_content, source_local_reference_key, local_reference_key_origin, identifiers,
                    source_locations, provisional_artifact_signals, extraction_limitations, provenance_capture_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, 'NOT_ATTEMPTED', ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                """.trimIndent(),
                referenceIds.getValue(entry.localReferenceKey),
                analysisRunId,
                entry.entryOrder,
                entry.localReferenceKey,
                entry.rawText,
                entry.title,
                JsonUtil.toJson(entry.authors),
                entry.year,
                entry.doi,
                entry.referenceType,
                entry.sourceElement.takeIf { entry.provenanceCaptured },
                entry.sourceTextContent.takeIf { entry.provenanceCaptured },
                entry.sourceLocalReferenceKey.takeIf { entry.provenanceCaptured },
                entry.localReferenceKeyOrigin.takeIf { entry.provenanceCaptured },
                entry.identifiers.takeIf { entry.provenanceCaptured }?.let(JsonUtil::toJson),
                entry.sourceLocations.takeIf { entry.provenanceCaptured }?.let(JsonUtil::toJson),
                entry.provisionalArtifactSignals.takeIf { entry.provenanceCaptured }?.let(JsonUtil::toJson),
                entry.extractionLimitations.takeIf { entry.provenanceCaptured }?.let(JsonUtil::toJson),
                "CAPTURED".takeIf { entry.provenanceCaptured },
            )
        }

        parsed.citationContexts.forEach { context ->
            val contextId = UUID.randomUUID()
            val targetIdsByKey = mutableMapOf<CitationTargetKey, UUID>()
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
            context.occurrences.forEachIndexed { occurrenceOrdinal, occurrence ->
                val occurrenceId = UUID.randomUUID()
                jdbc.update(
                    """
                    INSERT INTO citation_occurrences (
                        id, analysis_run_id, citation_context_id, section_id,
                        marker_text, start_offset, end_offset, unmatched_bibliography_reference_keys
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    """.trimIndent(),
                    occurrenceId,
                    analysisRunId,
                    contextId,
                    sectionId,
                    occurrence.markerText,
                    occurrence.startOffset,
                    occurrence.endOffset,
                    occurrence.unmatchedBibliographyReferenceKeys?.let(JsonUtil::toJson),
                )
                occurrence.bibliographyReferenceKeys.distinct()
                    .forEachIndexed { targetOrder, referenceKey ->
                        val referenceId = referenceIds[referenceKey]
                            ?: throw IllegalArgumentException("A Citation Target refers to a missing Bibliography Entry.")
                        val targetId = UUID.randomUUID()
                        targetIdsByKey[CitationTargetKey(occurrenceOrdinal, referenceKey)] = targetId
                        jdbc.update(
                            """
                            INSERT INTO citation_targets (
                                id, analysis_run_id, citation_context_id, citation_occurrence_id, bibliography_entry_id, target_order
                            ) VALUES (?, ?, ?, ?, ?, ?)
                            """.trimIndent(),
                            targetId,
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
                    claim.candidate.text,
                    claim.candidate.sourceStartOffset,
                    claim.candidate.sourceEndOffset,
                )
                claim.citationTargetKeys.forEach { targetKey ->
                    val targetId = targetIdsByKey[targetKey]
                        ?: throw IllegalArgumentException("An Atomic Claim selected a Citation Target outside its Citation Context.")
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
            SELECT parser_id, parser_version, source_content_sha256, normalized_source_text,
                   bibliography_normalization_policy_id, bibliography_normalization_policy_version
              FROM parsed_document_parses WHERE analysis_run_id = ?
            """.trimIndent(),
            { rs, _ -> ParseRow(
                parserId = rs.getString("parser_id"),
                parserVersion = rs.getString("parser_version"),
                sourceContentSha256 = rs.getString("source_content_sha256"),
                normalizedSourceText = rs.getString("normalized_source_text"),
                bibliographyNormalizationPolicy = rs.getString("bibliography_normalization_policy_id")?.let { policyId ->
                    rs.getString("bibliography_normalization_policy_version")?.let { version ->
                        BibliographyNormalizationPolicySelection(policyId, version)
                    }
                },
            ) },
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
                   entry.reference_type, COALESCE(resolution.status, entry.resolution_status) AS resolution_status,
                   entry.source_element, entry.source_text_content, entry.source_local_reference_key, entry.local_reference_key_origin,
                   entry.identifiers::text AS identifiers, entry.source_locations::text AS source_locations,
                   entry.provisional_artifact_signals::text AS provisional_artifact_signals,
                   entry.extraction_limitations::text AS extraction_limitations,
                   entry.provenance_capture_status
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
                   o.unmatched_bibliography_reference_keys::text AS unmatched_reference_keys,
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
                    unmatchedBibliographyReferenceKeys = first.unmatchedReferenceKeys,
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
            bibliographyNormalizationPolicy = parse.bibliographyNormalizationPolicy,
        )
    }

    private fun listOfNotCapturedLimitation(provenanceCaptureStatus: String?): List<String> =
        if (provenanceCaptureStatus == "CAPTURED") emptyList() else listOf("BIBLIOGRAPHY_PROVENANCE_UNAVAILABLE")

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
        unmatchedReferenceKeys = getString("unmatched_reference_keys")?.let {
            JsonUtil.fromJson(it, JsonUtil.collectionType(List::class.java, String::class.java))
        },
    )

    private fun ResultSet.toBibliographyEntryView() = ParsedBibliographyEntryView(
        entryOrder = getInt("entry_order"),
        localReferenceKey = getString("local_reference_key"),
        rawText = getString("raw_text"),
        title = getString("parsed_title"),
        authors = JsonUtil.fromJson(getString("parsed_authors"), JsonUtil.collectionType(List::class.java, String::class.java)),
        year = getObject("parsed_year", Integer::class.java)?.toInt(),
        doi = getString("parsed_doi"),
        referenceType = getString("reference_type"),
        resolutionStatus = getString("resolution_status"),
        sourceTextContent = getString("source_text_content"),
        sourceElement = getString("source_element"),
        sourceLocalReferenceKey = getString("source_local_reference_key"),
        localReferenceKeyOrigin = getString("local_reference_key_origin") ?: "UNKNOWN",
        identifiers = getString("identifiers")?.let {
            JsonUtil.fromJson<List<ParsedBibliographyIdentifier>>(it, JsonUtil.collectionType(List::class.java, ParsedBibliographyIdentifier::class.java))
        }.orEmpty(),
        sourceLocations = getString("source_locations")?.let {
            JsonUtil.fromJson<List<ParsedBibliographySourceLocation>>(it, JsonUtil.collectionType(List::class.java, ParsedBibliographySourceLocation::class.java))
        }.orEmpty(),
        provisionalArtifactSignals = getString("provisional_artifact_signals")?.let {
            JsonUtil.fromJson<List<String>>(it, JsonUtil.collectionType(List::class.java, String::class.java))
        }.orEmpty(),
        extractionLimitations = getString("extraction_limitations")?.let {
            JsonUtil.fromJson<List<String>>(it, JsonUtil.collectionType(List::class.java, String::class.java))
        } ?: listOfNotCapturedLimitation(getString("provenance_capture_status")),
        provenanceCaptureStatus = getString("provenance_capture_status") ?: "UNAVAILABLE",
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
    private data class ParseRow(
        val parserId: String,
        val parserVersion: String,
        val sourceContentSha256: String,
        val normalizedSourceText: String,
        val bibliographyNormalizationPolicy: BibliographyNormalizationPolicySelection?,
    )
    private data class OccurrenceRow(
        val id: UUID,
        val contextId: UUID,
        val markerText: String,
        val startOffset: Int,
        val endOffset: Int,
        val referenceKey: String?,
        val unmatchedReferenceKeys: List<String>?,
    )
}
