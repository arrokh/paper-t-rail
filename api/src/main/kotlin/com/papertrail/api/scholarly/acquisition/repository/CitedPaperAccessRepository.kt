package com.papertrail.api.scholarly.acquisition.repository

import com.papertrail.api.utils.JsonUtil
import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.scholarly.acquisition.domain.AcquiredFullText
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessCause
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessDecision
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessReason
import com.papertrail.api.scholarly.acquisition.domain.CitedPaperAccessOrigin
import com.papertrail.api.scholarly.acquisition.domain.UserSuppliedCitedPaperFullText
import com.papertrail.api.scholarly.acquisition.domain.UserSuppliedCitedPaperProvenance
import com.papertrail.api.scholarly.acquisition.domain.OpenAccessDiscovery
import com.papertrail.api.scholarly.acquisition.report.CitedPaperAccessReport
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class CitedPaperAccessRepository(
    private val jdbc: JdbcTemplate,
) {
    fun loadRun(analysisRunId: UUID): AccessRunContext? = jdbc.query(
        "SELECT document_id, status, configuration_snapshot::text AS configuration FROM analysis_runs WHERE id = ?",
        { rs, _ ->
            AccessRunContext(
                documentId = rs.getObject("document_id", UUID::class.java),
                status = rs.getString("status"),
                configuration = JsonUtil.fromJson(rs.getString("configuration"), AnalysisConfigurationSnapshot::class.java),
            )
        },
        analysisRunId,
    ).firstOrNull()

    fun accessExists(analysisRunId: UUID, bibliographyEntryId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM cited_paper_access WHERE analysis_run_id = ? AND bibliography_entry_id = ?)",
        Boolean::class.java,
        analysisRunId,
        bibliographyEntryId,
    ) == true

    fun resolvedReference(analysisRunId: UUID, bibliographyEntryId: UUID): ResolvedCitedReference? = jdbc.query(
        """
        SELECT b.id, b.parsed_title, b.parsed_authors::text AS parsed_authors, b.parsed_year, b.parsed_doi, b.reference_type,
               p.id AS canonical_paper_id, p.doi AS canonical_doi
          FROM bibliography_entry_resolutions r
          JOIN bibliography_entries b
            ON b.analysis_run_id = r.analysis_run_id AND b.id = r.bibliography_entry_id
          JOIN canonical_papers p ON p.id = r.canonical_paper_id
         WHERE r.analysis_run_id = ? AND r.bibliography_entry_id = ? AND r.status = 'RESOLVED'
        """.trimIndent(),
        { rs, _ ->
            ResolvedCitedReference(
                bibliographyEntryId = rs.getObject("id", UUID::class.java),
                title = rs.getString("parsed_title"),
                authors = JsonUtil.fromJson(
                    rs.getString("parsed_authors"),
                    JsonUtil.collectionType(List::class.java, String::class.java),
                ),
                year = rs.getObject("parsed_year", Integer::class.java)?.toInt(),
                doi = rs.getString("parsed_doi") ?: rs.getString("canonical_doi"),
                referenceType = rs.getString("reference_type"),
                canonicalPaperId = rs.getObject("canonical_paper_id", UUID::class.java),
            )
        },
        analysisRunId,
        bibliographyEntryId,
    ).firstOrNull()

    fun save(
        analysisRunId: UUID,
        reference: ResolvedCitedReference,
        discovery: OpenAccessDiscovery?,
        decision: CitedPaperAccessDecision,
        accessReason: CitedPaperAccessReason?,
        accessReasons: List<CitedPaperAccessCause>,
        locationUrl: String?,
        license: String?,
        version: String?,
        hostType: String?,
        providerId: String,
        discoveredAt: Instant,
        objectKey: String?,
        fullText: AcquiredFullText?,
        language: String?,
        languageDetectorVersion: String?,
    ): Boolean {
        val contentHash = fullText?.bytes?.let(::sha256Hex)
        val inserted = jdbc.update(
            """
            INSERT INTO cited_paper_access (
                analysis_run_id, bibliography_entry_id, canonical_paper_id, access_status,
                provider_id, access_reason, access_reasons, metadata_available, abstract_available, source_url, license_identifier,
                location_version, location_host_type, discovered_at, object_key, content_sha256,
                content_media_type, language, language_detector_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (analysis_run_id, bibliography_entry_id) DO NOTHING
            """.trimIndent(),
            analysisRunId,
            reference.bibliographyEntryId,
            reference.canonicalPaperId,
            decision.accessStatus.name,
            providerId,
            accessReason?.name,
            JsonUtil.toJson(accessReasons.map { it.name }),
            discovery?.metadataAvailable == true || discovery?.abstractAvailable == true || discovery?.locations?.isNotEmpty() == true || fullText != null,
            discovery?.abstractAvailable == true,
            locationUrl,
            license,
            version,
            hostType,
            Timestamp.from(discoveredAt),
            objectKey,
            contentHash,
            fullText?.mediaType?.substringBefore(';')?.trim()?.lowercase(),
            language,
            languageDetectorVersion,
        )
        return inserted == 1
    }

    fun saveUserSupplied(
        analysisRunId: UUID,
        reference: ResolvedCitedReference,
        objectKey: String,
        fullText: UserSuppliedCitedPaperFullText,
        provenance: UserSuppliedCitedPaperProvenance,
        discoveredAt: Instant,
    ): Boolean {
        require(objectKey.isNotBlank()) { "Supplied Cited Paper object key must not be blank" }
        val contentHash = sha256Hex(fullText.bytes)
        require(contentHash == provenance.contentSha256) { "Supplied Cited Paper hash must match the persisted bytes" }
        require(fullText.mediaType.substringBefore(';').trim().equals(provenance.mediaType, ignoreCase = true)) {
            "Supplied Cited Paper media type must match its provenance"
        }

        return jdbc.update(
            """
            INSERT INTO cited_paper_access (
                analysis_run_id, bibliography_entry_id, canonical_paper_id, access_status,
                provider_id, metadata_available, abstract_available, source_url, license_identifier,
                discovered_at, object_key, content_sha256, content_media_type, language, language_detector_version,
                access_origin, rights_declaration_version, rights_declaration_text,
                rights_declared_at, originating_analysis_run_id, originating_recovery_batch_id, originating_recovery_upload_id
            ) VALUES (?, ?, ?, 'FULL_TEXT_AVAILABLE', NULL, TRUE, FALSE, NULL, NULL, ?, ?, ?, ?, ?, ?,
                      'USER_SUPPLIED', ?, ?, ?, ?, ?, ?)
            ON CONFLICT (analysis_run_id, bibliography_entry_id) DO NOTHING
            """.trimIndent(),
            analysisRunId,
            reference.bibliographyEntryId,
            reference.canonicalPaperId,
            Timestamp.from(discoveredAt),
            objectKey,
            contentHash,
            provenance.mediaType.lowercase(),
            provenance.language,
            provenance.languageDetectorVersion,
            provenance.rightsDeclarationVersion,
            provenance.rightsDeclarationText,
            Timestamp.from(provenance.rightsDeclaredAt),
            provenance.originatingAnalysisRunId,
            provenance.originatingRecoveryBatchId,
            provenance.originatingRecoveryUploadId,
        ) == 1
    }

    /** Persists provenance for bytes verified before the worker transaction; the object is hash-checked again by the indexing path. */
    fun saveVerifiedUserSuppliedAssetReference(
        analysisRunId: UUID,
        reference: ResolvedCitedReference,
        objectKey: String,
        provenance: UserSuppliedCitedPaperProvenance,
        discoveredAt: Instant,
    ): Boolean {
        require(objectKey.isNotBlank()) { "Supplied Cited Paper object key must not be blank" }
        return jdbc.update(
            """
            INSERT INTO cited_paper_access (
                analysis_run_id, bibliography_entry_id, canonical_paper_id, access_status,
                provider_id, metadata_available, abstract_available, source_url, license_identifier,
                discovered_at, object_key, content_sha256, content_media_type, language, language_detector_version,
                access_origin, rights_declaration_version, rights_declaration_text,
                rights_declared_at, originating_analysis_run_id, originating_recovery_batch_id, originating_recovery_upload_id
            ) VALUES (?, ?, ?, 'FULL_TEXT_AVAILABLE', NULL, TRUE, FALSE, NULL, NULL, ?, ?, ?, ?, ?, ?,
                      'USER_SUPPLIED', ?, ?, ?, ?, ?, ?)
            ON CONFLICT (analysis_run_id, bibliography_entry_id) DO NOTHING
            """.trimIndent(),
            analysisRunId,
            reference.bibliographyEntryId,
            reference.canonicalPaperId,
            Timestamp.from(discoveredAt),
            objectKey,
            provenance.contentSha256,
            provenance.mediaType.lowercase(),
            provenance.language,
            provenance.languageDetectorVersion,
            provenance.rightsDeclarationVersion,
            provenance.rightsDeclarationText,
            Timestamp.from(provenance.rightsDeclaredAt),
            provenance.originatingAnalysisRunId,
            provenance.originatingRecoveryBatchId,
            provenance.originatingRecoveryUploadId,
        ) == 1
    }

    fun isObjectKeyReferenced(objectKey: String): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM cited_paper_access WHERE object_key = ?)",
        Boolean::class.java,
        objectKey,
    ) == true

    fun reportEntries(analysisRunId: UUID): List<CitedPaperAccessReportEntry> = jdbc.query(
        """
        SELECT b.id AS bibliography_entry_id, b.local_reference_key, a.access_status, a.access_reason,
               a.access_reasons::text AS access_reasons, a.provider_id, a.source_url, a.license_identifier, a.location_version, a.location_host_type,
               a.discovered_at, a.content_sha256, a.language, a.language_detector_version, a.access_origin,
               a.content_media_type, a.rights_declaration_version, a.rights_declaration_text, a.rights_declared_at,
               a.originating_analysis_run_id, a.originating_recovery_batch_id, a.originating_recovery_upload_id
          FROM cited_paper_access a
          JOIN bibliography_entries b
            ON b.analysis_run_id = a.analysis_run_id AND b.id = a.bibliography_entry_id
         WHERE a.analysis_run_id = ?
         ORDER BY b.entry_order
        """.trimIndent(),
        { rs, _ -> rs.toAccessReportEntry() },
        analysisRunId,
    )

    data class AccessRunContext(
        val documentId: UUID,
        val status: String,
        val configuration: AnalysisConfigurationSnapshot,
    )

    private fun ResultSet.toAccessReportEntry(): CitedPaperAccessReportEntry = CitedPaperAccessReportEntry(
        bibliographyEntryId = getObject("bibliography_entry_id", UUID::class.java),
        localReferenceKey = getString("local_reference_key"),
        report = CitedPaperAccessReport(
            accessStatus = getString("access_status"),
            accessOrigin = CitedPaperAccessOrigin.valueOf(getString("access_origin")),
            accessReason = getString("access_reason"),
            accessReasons = JsonUtil.fromJson(
                getString("access_reasons"),
                JsonUtil.collectionType(List::class.java, String::class.java),
            ),
            providerId = getString("provider_id"),
            sourceUrl = getString("source_url"),
            license = getString("license_identifier"),
            version = getString("location_version"),
            hostType = getString("location_host_type"),
            discoveredAt = getTimestamp("discovered_at").toInstant(),
            contentSha256 = getString("content_sha256"),
            mediaType = getString("content_media_type"),
            language = getString("language"),
            languageDetectorVersion = getString("language_detector_version"),
            rightsDeclarationVersion = getString("rights_declaration_version"),
            rightsDeclarationText = getString("rights_declaration_text"),
            rightsDeclaredAt = getTimestamp("rights_declared_at")?.toInstant(),
            originatingAnalysisRunId = getObject("originating_analysis_run_id", UUID::class.java),
            originatingRecoveryBatchId = getObject("originating_recovery_batch_id", UUID::class.java),
            originatingRecoveryUploadId = getObject("originating_recovery_upload_id", UUID::class.java),
        ),
    )
}
