package com.papertrail.api.analysis.successor.repository

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ProviderSelection
import com.papertrail.api.analysis.recovery.domain.RecoveryBatch
import com.papertrail.api.analysis.recovery.domain.RecoveryUpload
import com.papertrail.api.analysis.recovery.repository.RecoveryHistoryRepository
import com.papertrail.api.analysis.successor.domain.SuccessorIdentifierAssistanceEntryPlan
import com.papertrail.api.analysis.successor.domain.SuccessorRunPlan
import com.papertrail.api.analysis.successor.domain.SuccessorWorkDisposition
import com.papertrail.api.analysis.repository.AnalysisRunPipelineProgressRepository
import com.papertrail.api.analysis.execution.repository.AnalysisRunExecutionRepository
import com.papertrail.api.analysis.successor.queue.SuccessorRunResolutionQueue
import com.papertrail.api.evidence.queue.CitedPaperIndexingQueue
import com.papertrail.api.evidence.queue.EvidenceIndexingEnqueueResult
import com.papertrail.api.infrastructure.crypto.sha256Hex
import com.papertrail.api.scholarly.acquisition.domain.UserSuppliedCitedPaperFullText
import com.papertrail.api.scholarly.acquisition.domain.UserSuppliedCitedPaperProvenance
import com.papertrail.api.scholarly.acquisition.repository.CitedPaperAccessRepository
import com.papertrail.api.scholarly.acquisition.repository.ResolvedCitedReference
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class SuccessorRunSubmissionRepository(
    private val jdbc: JdbcTemplate,
    private val citedPaperAccessRepository: CitedPaperAccessRepository,
    private val pipelineProgressRepository: AnalysisRunPipelineProgressRepository,
    private val analysisRunExecutionRepository: AnalysisRunExecutionRepository,
    private val citedPaperIndexingQueue: CitedPaperIndexingQueue,
    private val successorRunResolutionQueue: SuccessorRunResolutionQueue,
    private val recoveryHistoryRepository: RecoveryHistoryRepository? = null,
) {
    fun submissionContext(batchId: UUID): SubmissionContext? = jdbc.query(
        """
        SELECT batch.analysis_run_id AS predecessor_analysis_run_id, run.document_id
          FROM recovery_batches batch
          JOIN analysis_runs run ON run.id = batch.analysis_run_id
         WHERE batch.id = ?
        """.trimIndent(),
        { rs, _ ->
            SubmissionContext(
                predecessorAnalysisRunId = rs.getObject("predecessor_analysis_run_id", UUID::class.java),
                documentId = rs.getObject("document_id", UUID::class.java),
            )
        },
        batchId,
    ).firstOrNull()

    fun existingSubmission(batchId: UUID): PersistedSuccessorSubmission? = jdbc.query(
        """
        SELECT id, batch_id, predecessor_analysis_run_id, successor_analysis_run_id,
               idempotency_key, request_fingerprint, plan_fingerprint, submitted_revision,
               selected_entry_count, retained_entry_count, queued_entry_count,
               identifier_assistance_entry_count, resolution_queued_entry_count
          FROM recovery_batch_successor_submissions
         WHERE batch_id = ?
        """.trimIndent(),
        { rs, _ -> rs.toPersistedSubmission() },
        batchId,
    ).firstOrNull()

    fun lockPredecessor(analysisRunId: UUID): LockedPredecessor? = jdbc.query(
        """
        SELECT id, document_id, source_content_sha256, source_parser_id, source_parser_version,
               configuration_snapshot::text AS configuration_snapshot, status
          FROM analysis_runs
         WHERE id = ?
         FOR UPDATE
        """.trimIndent(),
        { rs, _ ->
            LockedPredecessor(
                analysisRunId = rs.getObject("id", UUID::class.java),
                documentId = rs.getObject("document_id", UUID::class.java),
                sourceContentSha256 = rs.getString("source_content_sha256"),
                sourceParserId = rs.getString("source_parser_id"),
                sourceParserVersion = rs.getString("source_parser_version"),
                configurationJson = rs.getString("configuration_snapshot"),
                status = rs.getString("status"),
            )
        },
        analysisRunId,
    ).firstOrNull()

    fun lockBatch(batchId: UUID): LockedBatch? = jdbc.query(
        """
        SELECT id, analysis_run_id, status, expires_at, revision
          FROM recovery_batches
         WHERE id = ?
         FOR UPDATE
        """.trimIndent(),
        { rs, _ ->
            LockedBatch(
                id = rs.getObject("id", UUID::class.java),
                analysisRunId = rs.getObject("analysis_run_id", UUID::class.java),
                status = rs.getString("status"),
                expiresAt = rs.getTimestamp("expires_at").toInstant(),
                revision = rs.getLong("revision"),
            )
        },
        batchId,
    ).firstOrNull()

    @Transactional
    fun recordFailedSelectedAccessAttempt(failure: SuccessorSelectedAccessAttemptFailed, now: Instant = Instant.now()) {
        val history = recoveryHistoryRepository ?: return
        val batchExists = jdbc.query(
            "SELECT id FROM recovery_batches WHERE id = ? FOR UPDATE",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            failure.batchId,
        ).isNotEmpty()
        if (!batchExists) return
        val attemptNumber = nextAttemptNumber(failure.batchId, failure.bibliographyEntryId, "selected-access")
        listOf("ATTEMPT_STARTED", "ATTEMPT_FAILED").forEach { eventType ->
            history.append(
                batchId = failure.batchId,
                analysisRunId = failure.predecessorAnalysisRunId,
                batchStatus = "OPEN",
                eventType = eventType,
                outcomeCode = if (eventType == "ATTEMPT_STARTED") "SELECTED_ACCESS_HANDOFF_STARTED" else "SELECTED_ACCESS_HANDOFF_FAILED",
                stageCode = "selected-access",
                attemptNumber = attemptNumber,
                bibliographyEntryId = failure.bibliographyEntryId,
                uploadId = failure.uploadId,
                createdAt = now,
            )
        }
    }

    private fun nextAttemptNumber(batchId: UUID, bibliographyEntryId: UUID, stageCode: String): Int =
        jdbc.queryForObject(
            "SELECT COALESCE(MAX(attempt_number), 0) + 1 FROM recovery_history_events WHERE batch_id = ? AND bibliography_entry_id = ? AND stage_code = ?",
            Int::class.java,
            batchId,
            bibliographyEntryId,
            stageCode,
        ) ?: 1


    fun lockSelectedUploads(batchId: UUID) {
        jdbc.query(
            """
            SELECT upload.id
              FROM recovery_upload_asset_selections selection
              JOIN recovery_batch_uploads upload
                ON upload.batch_id = selection.batch_id AND upload.id = selection.upload_id
             WHERE selection.batch_id = ?
             ORDER BY upload.id
             FOR UPDATE OF selection, upload
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            batchId,
        )
    }

    fun materialize(
        submissionId: UUID,
        successorRunId: UUID,
        batch: RecoveryBatch,
        predecessor: LockedPredecessor,
        plan: SuccessorRunPlan,
        configuration: AnalysisConfigurationSnapshot,
        configurationJson: String,
        requestFingerprint: String,
        idempotencyKey: UUID,
        selectedUploads: List<VerifiedSuccessorUpload>,
        now: Instant,
    ): PersistedSuccessorSubmission {
        check(plan.eligible) { "Only an eligible successor plan can be materialized." }
        check(plan.selectedEntries.isNotEmpty() || plan.identifierAssistanceEntries.isNotEmpty()) {
            "At least one selected PDF asset or DOI-assisted identity entry is required."
        }
        val selectedAssetsByEntry = selectedUploads.associateBy(VerifiedSuccessorUpload::predecessorBibliographyEntryId)
        val selectedPlansByEntry = plan.selectedEntries.associateBy { it.predecessorBibliographyEntryId }
        val identifierPlansByEntry = plan.identifierAssistanceEntries.associateBy { it.predecessorBibliographyEntryId }
        require(selectedAssetsByEntry.keys == selectedPlansByEntry.keys)
        require(selectedPlansByEntry.keys.intersect(identifierPlansByEntry.keys).isEmpty())
        plan.selectedEntries.forEach { selectedPlan ->
            val asset = selectedAssetsByEntry.getValue(selectedPlan.predecessorBibliographyEntryId)
            require(selectedPlan.uploadId == asset.upload.id)
            require(sha256Hex(asset.bytes) == selectedPlan.contentSha256)
        }
        val selectedPredecessorEntryIds = selectedPlansByEntry.keys
        val reprocessedPredecessorEntryIds = selectedPredecessorEntryIds + identifierPlansByEntry.keys

        jdbc.update(
            """
            INSERT INTO analysis_runs (
                id, document_id, source_content_sha256, source_parser_id, source_parser_version,
                configuration_snapshot, status, progress, created_at, started_at
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, 'PROCESSING',
                      '{"stage":"PROCESSING_SELECTED_RECOVERY","percent":70,"message":"Reassessing the selected supplied asset."}'::jsonb,
                      ?, ?)
            """.trimIndent(),
            successorRunId,
            predecessor.documentId,
            predecessor.sourceContentSha256,
            predecessor.sourceParserId,
            predecessor.sourceParserVersion,
            configurationJson,
            Timestamp.from(now),
            Timestamp.from(now),
        )
        analysisRunExecutionRepository.insertRecordingBestEffort(successorRunId, configuration.captureExecution, now)
        insertSubmission(
            submissionId = submissionId,
            batch = batch,
            predecessor = predecessor,
            successorRunId = successorRunId,
            idempotencyKey = idempotencyKey,
            requestFingerprint = requestFingerprint,
            plan = plan,
            configurationJson = configurationJson,
            createdAt = now,
            selectedCount = plan.selectedEntries.size,
            retainedCount = plan.retainedReferenceKeys.size,
            queuedCount = plan.selectedEntries.count { it.assistedDoi == null },
            identifierAssistanceCount = plan.identifierAssistanceEntries.size,
            resolutionQueuedCount = plan.identifierAssistanceEntries.size + plan.selectedEntries.count { it.assistedDoi != null },
        )

        val predecessorConfiguration = com.papertrail.api.utils.JsonUtil.fromJson(predecessor.configurationJson, AnalysisConfigurationSnapshot::class.java)
        cloneParsedSource(predecessor.analysisRunId, successorRunId, predecessor.sourceContentSha256, now)
        recordLineage(
            successorRunId, predecessor.analysisRunId, "source-parse", successorRunId, predecessor.analysisRunId,
            predecessor.analysisRunId, predecessor.analysisRunId, predecessor.sourceParserId, null, null,
            "SOURCE_PARSE_REUSED", now,
        )
        val sectionIds = cloneSections(predecessor.analysisRunId, successorRunId, now)
        val contextIds = cloneContexts(predecessor.analysisRunId, successorRunId, sectionIds, now)
        val bibliographyEntries = cloneBibliographyEntries(predecessor.analysisRunId, successorRunId, now)
        val occurrenceIds = cloneOccurrences(predecessor.analysisRunId, successorRunId, sectionIds, contextIds, now)
        val targetIds = cloneTargets(predecessor.analysisRunId, successorRunId, occurrenceIds, contextIds, bibliographyEntries, now)
        val claimIds = cloneClaims(predecessor.analysisRunId, successorRunId, contextIds, now)
        cloneClaimTargetLinks(predecessor.analysisRunId, successorRunId, contextIds, claimIds, targetIds, now)
        val assistedPdfPredecessorEntryIds = plan.selectedEntries.filter { it.assistedDoi != null }
            .map { it.predecessorBibliographyEntryId }.toSet()
        val assistedPredecessorEntryIds = assistedPdfPredecessorEntryIds + identifierPlansByEntry.keys
        cloneResolutions(
            predecessor.analysisRunId,
            successorRunId,
            bibliographyEntries,
            predecessorConfiguration,
            assistedPredecessorEntryIds,
            now,
        )

        val selectedSuccessorEntryIds: Map<UUID, Pair<UUID, ResolvedCitedReference?>> = selectedPredecessorEntryIds.associateWith { predecessorEntryId ->
            val successorEntryId = bibliographyEntries[predecessorEntryId]
                ?: error("Selected bibliography entry was not materialized.")
            val selectedPlan = selectedPlansByEntry.getValue(predecessorEntryId)
            val selectedReference = if (selectedPlan.assistedDoi == null) {
                reference(successorRunId, successorEntryId)
                    ?: error("Selected bibliography entry resolution is missing.")
            } else {
                null
            }
            if (selectedReference != null) {
                val verifiedAsset = selectedAssetsByEntry.getValue(predecessorEntryId)
                val attemptNumber = nextAttemptNumber(batch.id, predecessorEntryId, "selected-access")
                recoveryHistoryRepository?.append(
                    batchId = batch.id,
                    analysisRunId = predecessor.analysisRunId,
                    batchStatus = "SUBMITTED",
                    eventType = "ATTEMPT_STARTED",
                    outcomeCode = "SELECTED_ACCESS_HANDOFF_STARTED",
                    stageCode = "selected-access",
                    attemptNumber = attemptNumber,
                    bibliographyEntryId = predecessorEntryId,
                    uploadId = verifiedAsset.upload.id,
                    createdAt = now,
                )
                try {
                    saveSelectedAccess(
                        successorRunId = successorRunId,
                        batch = batch,
                        upload = verifiedAsset.upload,
                        reference = selectedReference,
                        bytes = verifiedAsset.bytes,
                        validationAttempt = verifiedAsset.validationAttempt,
                        now = now,
                    )
                } catch (exception: Exception) {
                    throw SuccessorSelectedAccessAttemptFailed(
                        batchId = batch.id,
                        predecessorAnalysisRunId = predecessor.analysisRunId,
                        bibliographyEntryId = predecessorEntryId,
                        uploadId = verifiedAsset.upload.id,
                        cause = exception,
                    )
                }
                recoveryHistoryRepository?.append(
                    batchId = batch.id,
                    analysisRunId = predecessor.analysisRunId,
                    batchStatus = "SUBMITTED",
                    eventType = "ATTEMPT_COMPLETED",
                    outcomeCode = "SELECTED_ACCESS_HANDOFF_COMPLETED",
                    stageCode = "selected-access",
                    attemptNumber = attemptNumber,
                    bibliographyEntryId = predecessorEntryId,
                    uploadId = verifiedAsset.upload.id,
                    createdAt = now,
                )
            }
            successorEntryId to selectedReference
        }

        cloneUnselectedAccess(predecessor.analysisRunId, successorRunId, bibliographyEntries, reprocessedPredecessorEntryIds, predecessorConfiguration, now)

        val verificationIds = cloneVerifications(
            predecessor.analysisRunId,
            successorRunId,
            bibliographyEntries,
            claimIds,
            selectedSuccessorEntryIds.mapNotNull { (predecessorEntryId, successorEntryAndReference) ->
                successorEntryAndReference.second?.canonicalPaperId?.let { predecessorEntryId to it }
            }.toMap(),
            assistedPredecessorEntryIds,
            now,
        )
        cloneUnselectedEvidence(
            predecessor.analysisRunId,
            successorRunId,
            bibliographyEntries,
            verificationIds,
            reprocessedPredecessorEntryIds,
            predecessorConfiguration,
            now,
        )
        selectedSuccessorEntryIds.values.forEach { (entryId, selectedReference) ->
            ensureSelectedVerifications(
                successorRunId = successorRunId,
                selectedSuccessorEntryId = entryId,
                canonicalPaperId = selectedReference?.canonicalPaperId,
                verificationScope = if (selectedReference == null) "NONE" else "FULL_TEXT",
                now = now,
            )
        }

        val entryByReference = jdbc.query(
            "SELECT id, local_reference_key FROM bibliography_entries WHERE analysis_run_id = ? ORDER BY entry_order, id",
            { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("local_reference_key") },
            successorRunId,
        )
        val selectedUploadByPredecessorEntry = selectedAssetsByEntry.mapValues { it.value.upload }
        val predecessorKeys = jdbc.query(
            "SELECT id, local_reference_key FROM bibliography_entries WHERE analysis_run_id = ? ORDER BY entry_order, id",
            { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("local_reference_key") },
            predecessor.analysisRunId,
        ).toMap()
        for ((oldEntryId, newEntryId) in bibliographyEntries) {
            val selectedPdfPlan = selectedPlansByEntry[oldEntryId]
            val identifierPlan = identifierPlansByEntry[oldEntryId]
            val upload = selectedUploadByPredecessorEntry[oldEntryId]
            val workKind = when {
                selectedPdfPlan != null -> "PDF_ASSET"
                identifierPlan != null -> "IDENTITY_ONLY"
                else -> "RETAINED"
            }
            val stagePlans = selectedPdfPlan?.stages ?: identifierPlan?.stages ?: plan.retainedStageDecisions
            val stageDispositions = stagePlans.map { stage ->
                mapOf("stage" to stage.stage, "disposition" to stage.disposition.name, "reasonCode" to stage.reasonCode)
            }
            jdbc.update(
                """
                INSERT INTO analysis_run_successor_work_manifest (
                    successor_analysis_run_id, predecessor_analysis_run_id, batch_id,
                    bibliography_entry_id, predecessor_bibliography_entry_id, local_reference_key,
                    disposition, selected_upload_id, selected_content_sha256, stage_dispositions, work_kind
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """.trimIndent(),
                successorRunId,
                predecessor.analysisRunId,
                batch.id,
                newEntryId,
                oldEntryId,
                predecessorKeys[oldEntryId] ?: "",
                if (workKind == "RETAINED") "RETAIN" else "REPROCESS",
                upload?.id,
                upload?.actualSha256,
                com.papertrail.api.utils.JsonUtil.toJson(stageDispositions),
                workKind,
            )
            stagePlans.forEach { stage ->
                recoveryHistoryRepository?.append(
                    batchId = batch.id,
                    analysisRunId = predecessor.analysisRunId,
                    batchStatus = "SUBMITTED",
                    eventType = when (stage.disposition) {
                        SuccessorWorkDisposition.REUSE -> "WORK_REUSED"
                        SuccessorWorkDisposition.RETAIN -> "WORK_RETAINED"
                        SuccessorWorkDisposition.REPROCESS -> "WORK_QUEUED"
                        SuccessorWorkDisposition.SKIP -> "WORK_SKIPPED"
                    },
                    outcomeCode = stage.reasonCode,
                    stageCode = stage.stage,
                    bibliographyEntryId = oldEntryId,
                    uploadId = upload?.id,
                    createdAt = now,
                )
            }
        }

        plan.identifierAssistanceEntries.forEach { identifierPlan ->
            val successorEntryId = bibliographyEntries.getValue(identifierPlan.predecessorBibliographyEntryId)
            insertDoiOnlyResolutionAssistance(
                successorRunId = successorRunId,
                predecessorRunId = predecessor.analysisRunId,
                successorEntryId = successorEntryId,
                predecessorEntryId = identifierPlan.predecessorBibliographyEntryId,
                batchId = batch.id,
                identifierType = identifierPlan.identifierType,
                identifierValue = identifierPlan.identifierValue,
                createdAt = now,
            )
            jdbc.update(
                "DELETE FROM recovery_batch_identifier_assistance WHERE batch_id = ? AND bibliography_entry_id = ?",
                batch.id,
                identifierPlan.predecessorBibliographyEntryId,
            )
        }

        plan.selectedEntries.filter { it.assistedDoi != null }.forEach { selectedPlan ->
            val successorEntryId = bibliographyEntries.getValue(selectedPlan.predecessorBibliographyEntryId)
            val verifiedUpload = selectedAssetsByEntry.getValue(selectedPlan.predecessorBibliographyEntryId)
            insertResolutionAssistance(
                successorRunId = successorRunId,
                predecessorRunId = predecessor.analysisRunId,
                successorEntryId = successorEntryId,
                predecessorEntryId = selectedPlan.predecessorBibliographyEntryId,
                batchId = batch.id,
                upload = verifiedUpload,
                contentSha256 = selectedPlan.contentSha256,
                assistedDoi = requireNotNull(selectedPlan.assistedDoi),
                createdAt = now,
            )
        }

        selectedSuccessorEntryIds.forEach { (predecessorEntryId, successorEntryAndReference) ->
            val successorEntryId = successorEntryAndReference.first
            val selectedReference = successorEntryAndReference.second ?: return@forEach
            val selectedPlan = selectedPlansByEntry.getValue(predecessorEntryId)
            val upload = selectedAssetsByEntry.getValue(predecessorEntryId).upload
            val assetId = jdbc.queryForObject(
                "SELECT asset_id FROM cited_paper_access WHERE analysis_run_id = ? AND bibliography_entry_id = ?",
                UUID::class.java,
                successorRunId,
                successorEntryId,
            ) ?: error("Selected asset access row was not persisted.")
            jdbc.update(
                """
                INSERT INTO analysis_run_successor_asset_ownership (
                    successor_analysis_run_id, predecessor_analysis_run_id, batch_id,
                    bibliography_entry_id, upload_id, asset_id, object_key, content_sha256
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                successorRunId, predecessor.analysisRunId, batch.id, successorEntryId, upload.id, assetId,
                upload.finalizedObjectKey, selectedPlan.contentSha256,
            )
        }

        val predecessorEntryBySuccessorId = bibliographyEntries.entries.associate { it.value to it.key }
        val progressEntries = entryByReference.map { (entryId, key) ->
            val predecessorId = predecessorEntryBySuccessorId.getValue(entryId)
            Triple(predecessorId, entryId, key)
        }
        val selectedSuccessorEntryIdSet = selectedSuccessorEntryIds.values.map { it.first }.toSet()
        val identifierOnlySuccessorEntryIds = plan.identifierAssistanceEntries
            .map { bibliographyEntries.getValue(it.predecessorBibliographyEntryId) }.toSet()
        val assistedSuccessorEntryIds = plan.selectedEntries.filter { it.assistedDoi != null }
            .map { bibliographyEntries.getValue(it.predecessorBibliographyEntryId) }.toSet()
        initializeProgress(
            predecessor.analysisRunId,
            successorRunId,
            progressEntries,
            selectedSuccessorEntryIdSet + identifierOnlySuccessorEntryIds,
            assistedSuccessorEntryIds + identifierOnlySuccessorEntryIds,
            identifierOnlySuccessorEntryIds,
        )

        selectedSuccessorEntryIds.forEach { (_, successorEntryAndReference) ->
            val selectedSuccessorEntryId = successorEntryAndReference.first
            if (selectedSuccessorEntryId in assistedSuccessorEntryIds) {
                successorRunResolutionQueue.enqueue(
                    analysisRunId = successorRunId,
                    bibliographyEntryId = selectedSuccessorEntryId,
                    documentId = predecessor.documentId,
                    sourceContentSha256 = predecessor.sourceContentSha256,
                    correlationId = successorRunId,
                    causationId = submissionId,
                    traceparent = "00-${successorRunId.toString().replace("-", "")}-0000000000000001-01",
                )
            } else {
                val queued = citedPaperIndexingQueue.enqueueIfEligible(
                    analysisRunId = successorRunId,
                    bibliographyEntryId = selectedSuccessorEntryId,
                    documentId = predecessor.documentId,
                    sourceContentSha256 = predecessor.sourceContentSha256,
                    correlationId = successorRunId,
                    causationId = submissionId,
                    traceparent = "00-${successorRunId.toString().replace("-", "")}-0000000000000001-01",
                )
                if (queued != EvidenceIndexingEnqueueResult.QUEUED) {
                    throw IllegalStateException("Selected successor indexing work could not be queued.")
                }
            }
        }

        identifierOnlySuccessorEntryIds.forEach { successorEntryId ->
            successorRunResolutionQueue.enqueue(
                analysisRunId = successorRunId,
                bibliographyEntryId = successorEntryId,
                documentId = predecessor.documentId,
                sourceContentSha256 = predecessor.sourceContentSha256,
                correlationId = successorRunId,
                causationId = submissionId,
                traceparent = "00-${successorRunId.toString().replace("-", "")}-0000000000000001-01",
            )
        }

        val updated = jdbc.update(
            "UPDATE recovery_batches SET status = 'SUBMITTED', revision = revision + 1 WHERE id = ? AND status = 'OPEN' AND revision = ?",
            batch.id,
            batch.revision,
        )
        if (updated != 1) throw IllegalStateException("Recovery Batch changed while the successor was being committed.")
        recoveryHistoryRepository?.append(
            batchId = batch.id,
            analysisRunId = predecessor.analysisRunId,
            batchStatus = "SUBMITTED",
            eventType = "SUCCESSOR_SUBMITTED",
            outcomeCode = "SUCCESSOR_CREATED",
            successorAnalysisRunId = successorRunId,
            successorStatus = "PROCESSING",
            selectedEntryCount = plan.selectedEntries.size,
            identifierAssistanceEntryCount = plan.identifierAssistanceEntries.size,
            resolutionQueuedEntryCount = plan.identifierAssistanceEntries.size + plan.selectedEntries.count { it.assistedDoi != null },
            createdAt = now,
        )

        return PersistedSuccessorSubmission(
            id = submissionId,
            batchId = batch.id,
            predecessorAnalysisRunId = predecessor.analysisRunId,
            successorAnalysisRunId = successorRunId,
            idempotencyKey = idempotencyKey,
            requestFingerprint = requestFingerprint,
            planFingerprint = plan.planFingerprint,
            revision = batch.revision,
            selectedEntryCount = plan.selectedEntries.size,
            retainedEntryCount = entryByReference.size - plan.selectedEntries.size - plan.identifierAssistanceEntries.size,
            queuedEntryCount = selectedSuccessorEntryIdSet.count { it !in assistedSuccessorEntryIds },
            identifierAssistanceEntryCount = plan.identifierAssistanceEntries.size,
            resolutionQueuedEntryCount = plan.identifierAssistanceEntries.size + plan.selectedEntries.count { it.assistedDoi != null },
        )
    }

    private fun insertSubmission(
        submissionId: UUID,
        batch: RecoveryBatch,
        predecessor: LockedPredecessor,
        successorRunId: UUID,
        idempotencyKey: UUID,
        requestFingerprint: String,
        plan: SuccessorRunPlan,
        configurationJson: String,
        createdAt: Instant,
        selectedCount: Int,
        retainedCount: Int,
        queuedCount: Int,
        identifierAssistanceCount: Int,
        resolutionQueuedCount: Int,
    ) {
        jdbc.update(
            """
            INSERT INTO recovery_batch_successor_submissions (
                id, batch_id, predecessor_analysis_run_id, successor_analysis_run_id,
                idempotency_key, request_fingerprint, plan_fingerprint, submitted_revision,
                configuration_snapshot, selected_entry_count, retained_entry_count, queued_entry_count,
                identifier_assistance_entry_count, resolution_queued_entry_count, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            submissionId,
            batch.id,
            predecessor.analysisRunId,
            successorRunId,
            idempotencyKey,
            requestFingerprint,
            plan.planFingerprint,
            batch.revision,
            configurationJson,
            selectedCount,
            retainedCount,
            queuedCount,
            identifierAssistanceCount,
            resolutionQueuedCount,
            Timestamp.from(createdAt),
        )
    }

    private fun cloneParsedSource(predecessorRunId: UUID, successorRunId: UUID, sourceHash: String, now: Instant) {
        val copied = jdbc.update(
            """
            INSERT INTO parsed_document_parses (
                analysis_run_id, source_content_sha256, parser_id, parser_version,
                normalized_source_text, created_at, bibliography_normalization_policy_id,
                bibliography_normalization_policy_version, raw_tei_object_key
            )
            SELECT ?, source_content_sha256, parser_id, parser_version,
                   normalized_source_text, ?, bibliography_normalization_policy_id,
                   bibliography_normalization_policy_version, raw_tei_object_key
              FROM parsed_document_parses
             WHERE analysis_run_id = ? AND source_content_sha256 = ?
            """.trimIndent(),
            successorRunId,
            Timestamp.from(now),
            predecessorRunId,
            sourceHash,
        )
        check(copied == 1) { "Predecessor source parse was not available for reuse." }
    }

    private fun cloneSections(predecessorRunId: UUID, successorRunId: UUID, now: Instant): Map<UUID, UUID> {
        val rows = jdbc.query(
            "SELECT id, section_order, heading, text, start_offset, end_offset, source_metadata::text FROM parsed_document_sections WHERE analysis_run_id = ? ORDER BY section_order",
            { rs, _ -> SectionRow(rs.uuid("id"), rs.getInt("section_order"), rs.getString("heading"), rs.getString("text"), rs.getInt("start_offset"), rs.getInt("end_offset"), rs.getString("source_metadata")) },
            predecessorRunId,
        )
        return rows.associate { row ->
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO parsed_document_sections (id, analysis_run_id, section_order, heading, text, start_offset, end_offset, source_metadata) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                id, successorRunId, row.order, row.heading, row.text, row.start, row.end, row.sourceMetadata,
            )
            recordLineage(successorRunId, predecessorRunId, "source-section", id, row.id, predecessorRunId, row.id, null, null, null, "SOURCE_PARSE_REUSED", now)
            row.id to id
        }.toMap()
    }

    private fun cloneContexts(predecessorRunId: UUID, successorRunId: UUID, sections: Map<UUID, UUID>, now: Instant): Map<UUID, UUID> {
        val rows = jdbc.query(
            "SELECT id, section_id, context_text, boundary_kind, start_offset, end_offset FROM citation_contexts WHERE analysis_run_id = ? ORDER BY start_offset, id",
            { rs, _ -> ContextRow(rs.uuid("id"), rs.uuid("section_id"), rs.getString("context_text"), rs.getString("boundary_kind"), rs.getInt("start_offset"), rs.getInt("end_offset")) },
            predecessorRunId,
        )
        return rows.associate { row ->
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO citation_contexts (id, analysis_run_id, section_id, context_text, boundary_kind, start_offset, end_offset) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, successorRunId, sections.getValue(row.sectionId), row.text, row.boundaryKind, row.start, row.end,
            )
            recordLineage(successorRunId, predecessorRunId, "citation-context", id, row.id, predecessorRunId, row.id, null, null, null, "SOURCE_PARSE_REUSED", now)
            row.id to id
        }.toMap()
    }

    private fun cloneBibliographyEntries(predecessorRunId: UUID, successorRunId: UUID, now: Instant): Map<UUID, UUID> {
        val rows = jdbc.query(
            """
            SELECT id, entry_order, local_reference_key, raw_text, parsed_title, parsed_authors::text,
                   parsed_year, parsed_doi, reference_type, resolution_status, source_element,
                   source_text_content, source_local_reference_key, local_reference_key_origin,
                   identifiers::text, source_locations::text, provisional_artifact_signals::text,
                   extraction_limitations::text, provenance_capture_status
              FROM bibliography_entries
             WHERE analysis_run_id = ?
             ORDER BY entry_order, id
            """.trimIndent(),
            { rs, _ ->
                BibliographyRow(
                    id = rs.uuid("id"), order = rs.getInt("entry_order"), key = rs.getString("local_reference_key"),
                    rawText = rs.getString("raw_text"), title = rs.getString("parsed_title"), authors = rs.getString("parsed_authors"),
                    year = rs.getObject("parsed_year") as Int?, doi = rs.getString("parsed_doi"), referenceType = rs.getString("reference_type"),
                    resolutionStatus = rs.getString("resolution_status"), sourceElement = rs.getString("source_element"),
                    sourceTextContent = rs.getString("source_text_content"), sourceLocalKey = rs.getString("source_local_reference_key"),
                    localKeyOrigin = rs.getString("local_reference_key_origin"), identifiers = rs.getString("identifiers"),
                    sourceLocations = rs.getString("source_locations"), artifactSignals = rs.getString("provisional_artifact_signals"),
                    extractionLimitations = rs.getString("extraction_limitations"), captureStatus = rs.getString("provenance_capture_status"),
                )
            },
            predecessorRunId,
        )
        return rows.associate { row ->
            val id = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO bibliography_entries (
                    id, analysis_run_id, entry_order, local_reference_key, raw_text, parsed_title,
                    parsed_authors, parsed_year, parsed_doi, reference_type, resolution_status, created_at,
                    source_element, source_text_content, source_local_reference_key, local_reference_key_origin,
                    identifiers, source_locations, provisional_artifact_signals, extraction_limitations, provenance_capture_status
                ) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                """.trimIndent(),
                id, successorRunId, row.order, row.key, row.rawText, row.title, row.authors, row.year, row.doi,
                row.referenceType, row.resolutionStatus, Timestamp.from(now), row.sourceElement, row.sourceTextContent,
                row.sourceLocalKey, row.localKeyOrigin, row.identifiers, row.sourceLocations, row.artifactSignals,
                row.extractionLimitations, row.captureStatus,
            )
            recordLineage(successorRunId, predecessorRunId, "bibliography-entry", id, row.id, predecessorRunId, row.id, null, null, null, "SOURCE_PARSE_REUSED", now)
            row.id to id
        }.toMap()
    }

    private fun cloneOccurrences(predecessorRunId: UUID, successorRunId: UUID, sections: Map<UUID, UUID>, contexts: Map<UUID, UUID>, now: Instant): Map<UUID, UUID> {
        val rows = jdbc.query(
            "SELECT id, citation_context_id, section_id, marker_text, start_offset, end_offset, unmatched_bibliography_reference_keys::text FROM citation_occurrences WHERE analysis_run_id = ? ORDER BY start_offset, id",
            { rs, _ -> OccurrenceRow(rs.uuid("id"), rs.uuid("citation_context_id"), rs.uuid("section_id"), rs.getString("marker_text"), rs.getInt("start_offset"), rs.getInt("end_offset"), rs.getString("unmatched_bibliography_reference_keys")) },
            predecessorRunId,
        )
        return rows.associate { row ->
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO citation_occurrences (id, analysis_run_id, citation_context_id, section_id, marker_text, start_offset, end_offset, unmatched_bibliography_reference_keys) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                id, successorRunId, contexts.getValue(row.contextId), sections.getValue(row.sectionId), row.marker, row.start, row.end, row.unmatchedKeys,
            )
            recordLineage(successorRunId, predecessorRunId, "citation-occurrence", id, row.id, predecessorRunId, row.id, null, null, null, "SOURCE_PARSE_REUSED", now)
            row.id to id
        }.toMap()
    }

    private fun cloneTargets(predecessorRunId: UUID, successorRunId: UUID, occurrences: Map<UUID, UUID>, contexts: Map<UUID, UUID>, entries: Map<UUID, UUID>, now: Instant): Map<UUID, UUID> {
        val rows = jdbc.query(
            "SELECT id, citation_occurrence_id, citation_context_id, bibliography_entry_id, target_order FROM citation_targets WHERE analysis_run_id = ? ORDER BY citation_occurrence_id, target_order",
            { rs, _ -> TargetRow(rs.uuid("id"), rs.uuid("citation_occurrence_id"), rs.uuid("citation_context_id"), rs.uuid("bibliography_entry_id"), rs.getInt("target_order")) },
            predecessorRunId,
        )
        return rows.associate { row ->
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO citation_targets (id, analysis_run_id, citation_occurrence_id, citation_context_id, bibliography_entry_id, target_order) VALUES (?, ?, ?, ?, ?, ?)",
                id, successorRunId, occurrences.getValue(row.occurrenceId), contexts.getValue(row.contextId), entries.getValue(row.entryId), row.order,
            )
            recordLineage(successorRunId, predecessorRunId, "citation-target", id, row.id, predecessorRunId, row.id, null, null, null, "SOURCE_PARSE_REUSED", now)
            row.id to id
        }.toMap()
    }

    private fun cloneClaims(predecessorRunId: UUID, successorRunId: UUID, contexts: Map<UUID, UUID>, now: Instant): Map<UUID, UUID> {
        val rows = jdbc.query(
            "SELECT id, citation_context_id, claim_text, source_start_offset, source_end_offset FROM atomic_claims WHERE analysis_run_id = ? ORDER BY source_start_offset, id",
            { rs, _ -> ClaimRow(rs.uuid("id"), rs.uuid("citation_context_id"), rs.getString("claim_text"), rs.getInt("source_start_offset"), rs.getInt("source_end_offset")) },
            predecessorRunId,
        )
        return rows.associate { row ->
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO atomic_claims (id, analysis_run_id, citation_context_id, claim_text, source_start_offset, source_end_offset, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, successorRunId, contexts.getValue(row.contextId), row.text, row.start, row.end, Timestamp.from(now),
            )
            recordLineage(successorRunId, predecessorRunId, "atomic-claim", id, row.id, predecessorRunId, row.id, null, null, null, "CLAIM_OUTPUT_REUSED", now)
            row.id to id
        }.toMap()
    }

    private fun cloneClaimTargetLinks(predecessorRunId: UUID, successorRunId: UUID, contexts: Map<UUID, UUID>, claims: Map<UUID, UUID>, targets: Map<UUID, UUID>, now: Instant) {
        val rows = jdbc.query(
            "SELECT id, citation_context_id, atomic_claim_id, citation_target_id, association_kind FROM atomic_claim_citation_targets WHERE analysis_run_id = ? ORDER BY atomic_claim_id, citation_target_id",
            { rs, _ -> ClaimTargetRow(rs.uuid("id"), rs.uuid("citation_context_id"), rs.uuid("atomic_claim_id"), rs.uuid("citation_target_id"), rs.getString("association_kind")) },
            predecessorRunId,
        )
        rows.forEach { row ->
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO atomic_claim_citation_targets (id, analysis_run_id, citation_context_id, atomic_claim_id, citation_target_id, association_kind, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, successorRunId, contexts.getValue(row.contextId), claims.getValue(row.claimId), targets.getValue(row.targetId), row.kind, Timestamp.from(now),
            )
            recordLineage(successorRunId, predecessorRunId, "claim-citation-target", id, row.id, predecessorRunId, row.id, null, null, null, "CLAIM_OUTPUT_REUSED", now)
        }
    }

    private fun cloneResolutions(
        predecessorRunId: UUID,
        successorRunId: UUID,
        entries: Map<UUID, UUID>,
        predecessorConfiguration: AnalysisConfigurationSnapshot,
        reprocessedEntryIds: Set<UUID>,
        now: Instant,
    ) {
        val rows = jdbc.query(
            """
            SELECT resolution.bibliography_entry_id, resolution.status, resolution.reason_code,
                   resolution.canonical_paper_id, resolution.matched_doi, resolution.matched_title,
                   resolution.matched_authors::text, resolution.matched_year, resolution.confidence_score,
                   resolution.match_method, resolution.provider_id, resolution.candidate_evidence::text,
                   chain.analysis_run_id AS chain_analysis_run_id,
                   chain.owner_token AS chain_owner_token, chain.owner_fence AS chain_owner_fence,
                   chain.lease_expires_at AS chain_lease_expires_at,
                   chain.terminal_outcome AS chain_terminal_outcome,
                   chain.metadata_origin_provider_id AS chain_metadata_origin_provider_id
              FROM bibliography_entry_resolutions resolution
              LEFT JOIN reference_resolution_entry_chain_states chain
                ON chain.analysis_run_id = resolution.analysis_run_id
               AND chain.bibliography_entry_id = resolution.bibliography_entry_id
             WHERE resolution.analysis_run_id = ?
            """.trimIndent(),
            { rs, _ ->
                val chainState = if (rs.getObject("chain_analysis_run_id") == null) null else ProviderChainStateRow(
                    ownerToken = rs.uuid("chain_owner_token"),
                    ownerFence = rs.getLong("chain_owner_fence"),
                    leaseExpiresAt = rs.getTimestamp("chain_lease_expires_at").toInstant(),
                    terminalOutcome = rs.getString("chain_terminal_outcome"),
                    metadataOriginProviderId = rs.getString("chain_metadata_origin_provider_id"),
                )
                ResolutionRow(
                    entryId = rs.uuid("bibliography_entry_id"),
                    status = rs.getString("status"),
                    reasonCode = rs.getString("reason_code"),
                    canonicalPaperId = rs.uuidOrNull("canonical_paper_id"),
                    matchedDoi = rs.getString("matched_doi"),
                    matchedTitle = rs.getString("matched_title"),
                    matchedAuthors = rs.getString("matched_authors"),
                    matchedYear = rs.getObject("matched_year") as Int?,
                    confidence = rs.getObject("confidence_score") as Double?,
                    matchMethod = rs.getString("match_method"),
                    providerId = rs.getString("provider_id"),
                    candidateEvidence = rs.getString("candidate_evidence"),
                    chainState = chainState,
                )
            },
            predecessorRunId,
        ).filterNot { it.entryId in reprocessedEntryIds }
        rows.forEach { row ->
            val successorEntryId = entries.getValue(row.entryId)
            jdbc.update(
                """
                INSERT INTO bibliography_entry_resolutions (
                    analysis_run_id, bibliography_entry_id, status, reason_code, canonical_paper_id,
                    matched_doi, matched_title, matched_authors, matched_year, confidence_score,
                    match_method, provider_id, resolved_at, candidate_evidence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?::jsonb)
                """.trimIndent(),
                successorRunId, successorEntryId, row.status, row.reasonCode, row.canonicalPaperId,
                row.matchedDoi, row.matchedTitle, row.matchedAuthors, row.matchedYear, row.confidence,
                row.matchMethod, row.providerId, Timestamp.from(now), row.candidateEvidence,
            )
            row.chainState?.takeIf { it.terminalOutcome != null }?.let { chainState ->
                cloneProviderChainHistory(predecessorRunId, successorRunId, row.entryId, successorEntryId, chainState)
            }
            val originatingProviderId = if (row.chainState == null) row.providerId else row.chainState.metadataOriginProviderId
            recordLineage(
                successorRunId, predecessorRunId, "bibliography-resolution", successorEntryId, row.entryId,
                predecessorRunId, row.entryId, originatingProviderId, null, null, "IDENTITY_OUTPUT_REUSED", now,
            )
        }

        val terminalChainStatesWithoutResolution = jdbc.query(
            """
                SELECT chain.bibliography_entry_id, chain.owner_token, chain.owner_fence,
                       chain.lease_expires_at, chain.terminal_outcome, chain.metadata_origin_provider_id
                  FROM reference_resolution_entry_chain_states chain
                  LEFT JOIN bibliography_entry_resolutions resolution
                    ON resolution.analysis_run_id = chain.analysis_run_id
                   AND resolution.bibliography_entry_id = chain.bibliography_entry_id
                 WHERE chain.analysis_run_id = ?
                   AND chain.terminal_outcome IS NOT NULL
                   AND resolution.bibliography_entry_id IS NULL
            """.trimIndent(),
            { rs, _ ->
                ChainHistoryRow(
                    entryId = rs.uuid("bibliography_entry_id"),
                    chainState = ProviderChainStateRow(
                        ownerToken = rs.uuid("owner_token"),
                        ownerFence = rs.getLong("owner_fence"),
                        leaseExpiresAt = rs.getTimestamp("lease_expires_at").toInstant(),
                        terminalOutcome = rs.getString("terminal_outcome"),
                        metadataOriginProviderId = rs.getString("metadata_origin_provider_id"),
                    ),
                )
            },
            predecessorRunId,
        ).filterNot { it.entryId in reprocessedEntryIds }

        terminalChainStatesWithoutResolution.forEach { row ->
            val successorEntryId = entries.getValue(row.entryId)
            cloneProviderChainHistory(
                predecessorRunId,
                successorRunId,
                row.entryId,
                successorEntryId,
                row.chainState,
            )
        }
    }

    private fun cloneProviderChainHistory(
        predecessorRunId: UUID,
        successorRunId: UUID,
        predecessorEntryId: UUID,
        successorEntryId: UUID,
        chainState: ProviderChainStateRow,
    ) {
        val terminalOutcome = requireNotNull(chainState.terminalOutcome)
        jdbc.update(
            """
                INSERT INTO reference_resolution_entry_chain_states (
                    analysis_run_id, bibliography_entry_id, owner_token, owner_fence,
                    lease_expires_at, terminal_outcome, metadata_origin_provider_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            successorRunId,
            successorEntryId,
            chainState.ownerToken,
            chainState.ownerFence,
            Timestamp.from(chainState.leaseExpiresAt),
            terminalOutcome,
            chainState.metadataOriginProviderId,
        )

        val lookups = jdbc.query(
            """
                SELECT lookup_ordinal, provider_id, source, outcome_code, recorded_at
                  FROM reference_resolution_provider_lookup_results
                 WHERE analysis_run_id = ? AND bibliography_entry_id = ?
                 ORDER BY lookup_ordinal
            """.trimIndent(),
            { rs, _ -> ProviderLookupRow(
                lookupOrdinal = rs.getInt("lookup_ordinal"),
                providerId = rs.getString("provider_id"),
                source = rs.getString("source"),
                outcomeCode = rs.getString("outcome_code"),
                recordedAt = rs.getTimestamp("recorded_at").toInstant(),
            ) },
            predecessorRunId,
            predecessorEntryId,
        )
        lookups.forEach { lookup ->
            jdbc.update(
                """
                    INSERT INTO reference_resolution_provider_lookup_results (
                        analysis_run_id, bibliography_entry_id, lookup_ordinal, provider_id,
                        source, outcome_code, recorded_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                successorRunId,
                successorEntryId,
                lookup.lookupOrdinal,
                lookup.providerId,
                lookup.source,
                lookup.outcomeCode,
                Timestamp.from(lookup.recordedAt),
            )
        }

        val attempts = jdbc.query(
            """
                SELECT reservation_ordinal, lookup_ordinal, provider_id, provider_attempt_ordinal,
                       owner_token, owner_fence, state, outcome_code, next_attempt_not_before,
                       http_status, duration_millis, response_bytes, reserved_at, started_at,
                       completed_at, unknown_at, deadline_at
                  FROM reference_resolution_provider_attempts
                 WHERE analysis_run_id = ? AND bibliography_entry_id = ?
                 ORDER BY reservation_ordinal
            """.trimIndent(),
            { rs, _ -> ProviderAttemptRow(
                reservationOrdinal = rs.getInt("reservation_ordinal"),
                lookupOrdinal = rs.getInt("lookup_ordinal"),
                providerId = rs.getString("provider_id"),
                providerAttemptOrdinal = rs.getInt("provider_attempt_ordinal"),
                ownerToken = rs.uuid("owner_token"),
                ownerFence = rs.getLong("owner_fence"),
                state = rs.getString("state"),
                outcomeCode = rs.getString("outcome_code"),
                nextAttemptNotBefore = rs.getTimestamp("next_attempt_not_before")?.toInstant(),
                httpStatus = rs.intOrNull("http_status"),
                durationMillis = rs.longOrNull("duration_millis"),
                responseBytes = rs.longOrNull("response_bytes"),
                reservedAt = rs.getTimestamp("reserved_at").toInstant(),
                startedAt = rs.getTimestamp("started_at")?.toInstant(),
                completedAt = rs.getTimestamp("completed_at")?.toInstant(),
                unknownAt = rs.getTimestamp("unknown_at")?.toInstant(),
                deadlineAt = rs.getTimestamp("deadline_at").toInstant(),
            ) },
            predecessorRunId,
            predecessorEntryId,
        )
        attempts.forEach { attempt ->
            jdbc.update(
                """
                    INSERT INTO reference_resolution_provider_attempts (
                        attempt_id, analysis_run_id, bibliography_entry_id, reservation_ordinal,
                        lookup_ordinal, provider_id, provider_attempt_ordinal, owner_token, owner_fence,
                        state, outcome_code, next_attempt_not_before, http_status, duration_millis,
                        response_bytes, reserved_at, started_at, completed_at, unknown_at, deadline_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(),
                successorRunId,
                successorEntryId,
                attempt.reservationOrdinal,
                attempt.lookupOrdinal,
                attempt.providerId,
                attempt.providerAttemptOrdinal,
                attempt.ownerToken,
                attempt.ownerFence,
                attempt.state,
                attempt.outcomeCode,
                attempt.nextAttemptNotBefore?.let(Timestamp::from),
                attempt.httpStatus,
                attempt.durationMillis,
                attempt.responseBytes,
                Timestamp.from(attempt.reservedAt),
                attempt.startedAt?.let(Timestamp::from),
                attempt.completedAt?.let(Timestamp::from),
                attempt.unknownAt?.let(Timestamp::from),
                Timestamp.from(attempt.deadlineAt),
            )
        }
    }

    private fun cloneUnselectedAccess(
        predecessorRunId: UUID,
        successorRunId: UUID,
        entries: Map<UUID, UUID>,
        selectedEntryIds: Set<UUID>,
        predecessorConfiguration: AnalysisConfigurationSnapshot,
        now: Instant,
    ) {
        val rows = jdbc.query(
            """
            SELECT bibliography_entry_id, canonical_paper_id, access_status, access_reason, access_reasons::text,
                   provider_id, metadata_available, abstract_available, source_url, license_identifier,
                   location_version, location_host_type, discovered_at, object_key, content_sha256,
                   content_media_type, language, language_detector_version, access_origin,
                   rights_declaration_version, rights_declaration_text, rights_declared_at,
                   originating_analysis_run_id, originating_recovery_batch_id, originating_recovery_upload_id, asset_id
              FROM cited_paper_access WHERE analysis_run_id = ?
            """.trimIndent(),
            { rs, _ -> AccessRow(
                entryId = rs.uuid("bibliography_entry_id"), canonicalPaperId = rs.uuid("canonical_paper_id"),
                accessStatus = rs.getString("access_status"), accessReason = rs.getString("access_reason"), accessReasons = rs.getString("access_reasons"),
                providerId = rs.getString("provider_id"), metadata = rs.getBoolean("metadata_available"), abstract = rs.getBoolean("abstract_available"),
                sourceUrl = rs.getString("source_url"), license = rs.getString("license_identifier"), locationVersion = rs.getString("location_version"),
                hostType = rs.getString("location_host_type"), discoveredAt = rs.getTimestamp("discovered_at").toInstant(), objectKey = rs.getString("object_key"),
                hash = rs.getString("content_sha256"), mediaType = rs.getString("content_media_type"), language = rs.getString("language"),
                detectorVersion = rs.getString("language_detector_version"), origin = rs.getString("access_origin"),
                rightsVersion = rs.getString("rights_declaration_version"), rightsText = rs.getString("rights_declaration_text"),
                rightsAt = rs.getTimestamp("rights_declared_at")?.toInstant(), originRun = rs.getObject("originating_analysis_run_id", UUID::class.java),
                originBatch = rs.getObject("originating_recovery_batch_id", UUID::class.java), originUpload = rs.getObject("originating_recovery_upload_id", UUID::class.java),
                assetId = rs.uuid("asset_id"),
            ) },
            predecessorRunId,
        ).filterNot { it.entryId in selectedEntryIds }
        rows.forEach { row ->
            val newEntryId = entries[row.entryId] ?: return@forEach
            val newAssetId = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO cited_paper_access (
                    analysis_run_id, bibliography_entry_id, canonical_paper_id, access_status, access_reason,
                    access_reasons, provider_id, metadata_available, abstract_available, source_url, license_identifier,
                    location_version, location_host_type, discovered_at, object_key, content_sha256, content_media_type,
                    language, language_detector_version, asset_id, access_origin, rights_declaration_version,
                    rights_declaration_text, rights_declared_at, originating_analysis_run_id,
                    originating_recovery_batch_id, originating_recovery_upload_id
                ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                successorRunId, newEntryId, row.canonicalPaperId, row.accessStatus, row.accessReason,
                row.accessReasons, row.providerId, row.metadata, row.abstract, row.sourceUrl, row.license,
                row.locationVersion, row.hostType, Timestamp.from(row.discoveredAt), row.objectKey, row.hash,
                row.mediaType, row.language, row.detectorVersion, newAssetId, row.origin, row.rightsVersion,
                row.rightsText, row.rightsAt?.let(Timestamp::from), row.originRun, row.originBatch, row.originUpload,
            )
            val userSuppliedAccess = row.origin == "USER_SUPPLIED"
            val originRunId = if (userSuppliedAccess) {
                row.originRun ?: error("User-supplied access is missing its originating Recovery Batch run.")
            } else {
                row.originRun ?: predecessorRunId
            }
            val originArtifactId = if (userSuppliedAccess) {
                row.originUpload ?: error("User-supplied access is missing its originating Recovery Upload.")
            } else {
                row.assetId
            }
            recordLineage(
                successorRunId, predecessorRunId, "cited-paper-access", newAssetId, row.assetId,
                originRunId, originArtifactId, if (userSuppliedAccess) null else row.providerId,
                null, null, "UNSELECTED_ACCESS_RETAINED", now, userSuppliedAccess,
            )
        }
    }

    private fun insertResolutionAssistance(
        successorRunId: UUID,
        predecessorRunId: UUID,
        successorEntryId: UUID,
        predecessorEntryId: UUID,
        batchId: UUID,
        upload: VerifiedSuccessorUpload,
        contentSha256: String,
        assistedDoi: String,
        createdAt: Instant,
    ) {
        check(upload.upload.actualSha256 == contentSha256 && upload.validationAttempt.contentSha256 == contentSha256) {
            "Assisted identifier provenance must match the selected exact upload version."
        }
        val doiConfirmation = jdbc.query(
            """
            SELECT id, candidate_fingerprint
              FROM recovery_upload_identifier_confirmations
             WHERE batch_id = ? AND upload_id = ? AND validation_attempt_id = ?
               AND content_sha256 = ? AND identifier_type = 'DOI' AND identifier_value = ?
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("candidate_fingerprint") },
            batchId,
            upload.upload.id,
            upload.validationAttempt.id,
            contentSha256,
            assistedDoi,
        ).singleOrNull() ?: error("An explicit DOI-candidate confirmation is missing for the selected upload.")
        jdbc.update(
            """
            INSERT INTO analysis_run_successor_resolution_assistance (
                analysis_run_id, successor_analysis_run_id, predecessor_analysis_run_id, batch_id,
                bibliography_entry_id, predecessor_bibliography_entry_id, upload_id,
                validation_attempt_id, doi_confirmation_id, content_sha256, identifier_type,
                identifier_value, candidate_fingerprint, source_code, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DOI', ?, ?, 'HUMAN_CONFIRMED_PDF_DOI_CANDIDATE', ?)
            """.trimIndent(),
            successorRunId,
            successorRunId,
            predecessorRunId,
            batchId,
            successorEntryId,
            predecessorEntryId,
            upload.upload.id,
            upload.validationAttempt.id,
            doiConfirmation.first,
            contentSha256,
            assistedDoi,
            doiConfirmation.second,
            Timestamp.from(createdAt),
        )
    }

    private fun insertDoiOnlyResolutionAssistance(
        successorRunId: UUID,
        predecessorRunId: UUID,
        successorEntryId: UUID,
        predecessorEntryId: UUID,
        batchId: UUID,
        identifierType: String,
        identifierValue: String,
        createdAt: Instant,
    ) {
        val stagedValue = jdbc.query(
            "SELECT identifier_type, identifier_value FROM recovery_batch_identifier_assistance WHERE batch_id = ? AND bibliography_entry_id = ? FOR UPDATE",
            { rs, _ -> rs.getString("identifier_type") to rs.getString("identifier_value") },
            batchId,
            predecessorEntryId,
        ).singleOrNull() ?: error("DOI assistance changed before successor submission.")
        check(stagedValue.first == identifierType && stagedValue.second == identifierValue) {
            "DOI assistance changed before successor submission."
        }
        jdbc.update(
            """
            INSERT INTO analysis_run_successor_doi_assistance (
                successor_analysis_run_id, predecessor_analysis_run_id, batch_id,
                bibliography_entry_id, predecessor_bibliography_entry_id,
                identifier_type, identifier_value, source_code, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'USER_CONFIRMED_DOI_NO_PDF', ?)
            """.trimIndent(),
            successorRunId,
            predecessorRunId,
            batchId,
            successorEntryId,
            predecessorEntryId,
            identifierType,
            identifierValue,
            Timestamp.from(createdAt),
        )
    }

    private fun saveSelectedAccess(
        successorRunId: UUID,
        batch: RecoveryBatch,
        upload: RecoveryUpload,
        reference: ResolvedCitedReference,
        bytes: ByteArray,
        validationAttempt: com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationAttempt,
        now: Instant,
    ) {
        val provenance = UserSuppliedCitedPaperProvenance(
            contentSha256 = upload.actualSha256 ?: error("Selected upload hash is missing."),
            mediaType = "application/pdf",
            language = validationAttempt.detectedLanguage ?: "en",
            languageDetectorVersion = validationAttempt.languageDetectorVersion,
            rightsDeclarationVersion = requireNotNull(batch.rightsDeclaration).version,
            rightsDeclarationText = requireNotNull(batch.rightsDeclaration).text,
            rightsDeclaredAt = requireNotNull(batch.rightsDeclaredAt),
            originatingAnalysisRunId = batch.analysisRunId,
            originatingRecoveryBatchId = batch.id,
            originatingRecoveryUploadId = upload.id,
        )
        val saved = citedPaperAccessRepository.saveUserSupplied(
            analysisRunId = successorRunId,
            reference = reference,
            objectKey = upload.finalizedObjectKey,
            fullText = UserSuppliedCitedPaperFullText(bytes, "application/pdf"),
            provenance = provenance,
            discoveredAt = now,
        )
        check(saved) { "Selected user-supplied access already exists in the fresh successor run." }
    }

    private fun reference(analysisRunId: UUID, bibliographyEntryId: UUID): ResolvedCitedReference? = jdbc.query(
        """
        SELECT entry.parsed_title, entry.parsed_authors::text, entry.parsed_year, entry.parsed_doi,
               entry.reference_type, resolution.canonical_paper_id
          FROM bibliography_entries entry
          JOIN bibliography_entry_resolutions resolution
            ON resolution.analysis_run_id = entry.analysis_run_id
           AND resolution.bibliography_entry_id = entry.id
         WHERE entry.analysis_run_id = ? AND entry.id = ? AND resolution.status = 'RESOLVED'
        """.trimIndent(),
        { rs, _ ->
            ResolvedCitedReference(
                bibliographyEntryId = bibliographyEntryId,
                title = rs.getString("parsed_title"),
                authors = com.papertrail.api.utils.JsonUtil.fromJson<List<String>>(rs.getString("parsed_authors")),
                year = rs.getObject("parsed_year") as Int?,
                doi = rs.getString("parsed_doi"),
                referenceType = rs.getString("reference_type"),
                canonicalPaperId = rs.uuid("canonical_paper_id"),
            )
        },
        analysisRunId,
        bibliographyEntryId,
    ).firstOrNull()

    private fun cloneVerifications(
        predecessorRunId: UUID,
        successorRunId: UUID,
        entries: Map<UUID, UUID>,
        claims: Map<UUID, UUID>,
        selectedCanonicalPaperIds: Map<UUID, UUID>,
        reprocessedEntryIds: Set<UUID>,
        now: Instant,
    ): Map<UUID, UUID> {
        val rows = jdbc.query(
            """
            SELECT id, atomic_claim_id, bibliography_entry_id, canonical_paper_id, processing_status,
                   verification_scope, terminal_reason, final_status, evidence_conflict,
                   processing_failure_reason, aggregator_version
              FROM claim_paper_verifications
             WHERE analysis_run_id = ?
             ORDER BY bibliography_entry_id, atomic_claim_id, id
            """.trimIndent(),
            { rs, _ -> VerificationRow(
                id = rs.uuid("id"), claimId = rs.uuid("atomic_claim_id"), entryId = rs.uuid("bibliography_entry_id"),
                canonicalPaperId = rs.getObject("canonical_paper_id", UUID::class.java), status = rs.getString("processing_status"),
                scope = rs.getString("verification_scope"), terminalReason = rs.getString("terminal_reason"), finalStatus = rs.getString("final_status"),
                evidenceConflict = rs.getBoolean("evidence_conflict"), failureReason = rs.getString("processing_failure_reason"), aggregatorVersion = rs.getString("aggregator_version"),
            ) },
            predecessorRunId,
        ).filterNot { it.entryId in reprocessedEntryIds }
        val ids = mutableMapOf<UUID, UUID>()
        rows.forEach { row ->
            val newEntryId = entries[row.entryId] ?: return@forEach
            val newClaimId = claims[row.claimId] ?: return@forEach
            val selected = row.entryId in selectedCanonicalPaperIds
            val id = UUID.randomUUID()
            ids[row.id] = id
            jdbc.update(
                """
                INSERT INTO claim_paper_verifications (
                    id, analysis_run_id, atomic_claim_id, bibliography_entry_id, canonical_paper_id,
                    processing_status, verification_scope, terminal_reason, final_status, evidence_conflict,
                    processing_failure_reason, aggregator_version, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, successorRunId, newClaimId, newEntryId,
                selectedCanonicalPaperIds[row.entryId] ?: row.canonicalPaperId,
                if (selected) "PENDING" else row.status,
                if (selected) "FULL_TEXT" else row.scope,
                if (selected) null else row.terminalReason,
                if (selected) null else row.finalStatus,
                if (selected) false else row.evidenceConflict,
                if (selected) null else row.failureReason,
                if (selected) null else row.aggregatorVersion,
                Timestamp.from(now), Timestamp.from(now),
            )
            if (!selected) recordLineage(successorRunId, predecessorRunId, "claim-paper-verification", id, row.id, predecessorRunId, row.id, null, null, null, "UNSELECTED_VERIFICATION_RETAINED", now)
        }
        return ids
    }

    private fun ensureSelectedVerifications(
        successorRunId: UUID,
        selectedSuccessorEntryId: UUID,
        canonicalPaperId: UUID?,
        verificationScope: String,
        now: Instant,
    ) {
        val existingPairs = jdbc.query(
            "SELECT atomic_claim_id FROM claim_paper_verifications WHERE analysis_run_id = ? AND bibliography_entry_id = ?",
            { rs, _ -> rs.uuid("atomic_claim_id") },
            successorRunId,
            selectedSuccessorEntryId,
        ).toSet()
        if (canonicalPaperId != null) {
            jdbc.update(
                "UPDATE claim_paper_verifications SET canonical_paper_id = ?, verification_scope = 'FULL_TEXT', updated_at = ? WHERE analysis_run_id = ? AND bibliography_entry_id = ? AND processing_status = 'PENDING' AND verification_scope = 'NONE' AND (canonical_paper_id IS NULL OR canonical_paper_id = ?)",
                canonicalPaperId, Timestamp.from(now), successorRunId, selectedSuccessorEntryId, canonicalPaperId,
            )
        }
        val selectedClaimIds = jdbc.query(
            """
            SELECT DISTINCT link.atomic_claim_id
              FROM atomic_claim_citation_targets link
              JOIN citation_targets target
                ON target.analysis_run_id = link.analysis_run_id AND target.id = link.citation_target_id
             WHERE link.analysis_run_id = ? AND target.bibliography_entry_id = ?
            """.trimIndent(),
            { rs, _ -> rs.uuid("atomic_claim_id") },
            successorRunId,
            selectedSuccessorEntryId,
        )
        selectedClaimIds.filterNot { it in existingPairs }.forEach { claimId ->
            jdbc.update(
                "INSERT INTO claim_paper_verifications (id, analysis_run_id, atomic_claim_id, bibliography_entry_id, canonical_paper_id, processing_status, verification_scope, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)",
                UUID.randomUUID(), successorRunId, claimId, selectedSuccessorEntryId, canonicalPaperId, verificationScope, Timestamp.from(now), Timestamp.from(now),
            )
        }
    }

    private fun cloneUnselectedEvidence(
        predecessorRunId: UUID,
        successorRunId: UUID,
        entries: Map<UUID, UUID>,
        verifications: Map<UUID, UUID>,
        selectedEntryIds: Set<UUID>,
        predecessorConfiguration: AnalysisConfigurationSnapshot,
        now: Instant,
    ) {
        val indexingRows = jdbc.query(
            """
            SELECT bibliography_entry_id, status, failure_reason, cited_paper_parse_id, retrieval_profile_id,
                   vector_candidate_limit, lexical_candidate_limit, final_candidate_limit,
                   reciprocal_rank_fusion_constant, embedding_provider, embedding_model, embedding_version,
                   embedding_dimension, embedding_profile_hash, candidate_count
              FROM cited_paper_indexing
             WHERE analysis_run_id = ?
             ORDER BY bibliography_entry_id
            """.trimIndent(),
            { rs, _ -> IndexingRow(
                entryId = rs.uuid("bibliography_entry_id"), status = rs.getString("status"),
                failureReason = rs.getString("failure_reason"), parseId = rs.uuidOrNull("cited_paper_parse_id"),
                profileId = rs.getString("retrieval_profile_id"), vectorLimit = rs.getInt("vector_candidate_limit"),
                lexicalLimit = rs.getInt("lexical_candidate_limit"), finalLimit = rs.getInt("final_candidate_limit"),
                fusionConstant = rs.getInt("reciprocal_rank_fusion_constant"), embeddingProvider = rs.getString("embedding_provider"),
                embeddingModel = rs.getString("embedding_model"), embeddingVersion = rs.getString("embedding_version"),
                embeddingDimension = rs.getInt("embedding_dimension"), embeddingProfileHash = rs.getString("embedding_profile_hash"),
                candidateCount = rs.getInt("candidate_count"),
            ) },
            predecessorRunId,
        ).filterNot { it.entryId in selectedEntryIds }
        val successorChunks = mutableMapOf<UUID, UUID>()

        indexingRows.forEach { indexing ->
            val successorEntryId = entries[indexing.entryId] ?: return@forEach
            val successorParseId = indexing.parseId?.let { oldParseId ->
                val parse = jdbc.query(
                    "SELECT canonical_paper_id, content_sha256, parser_id, parser_version, language, language_detector_version, normalized_text FROM cited_paper_parses WHERE id = ? AND analysis_run_id = ? AND bibliography_entry_id = ?",
                    { rs, _ -> CitedParseRow(rs.uuid("canonical_paper_id"), rs.getString("content_sha256"), rs.getString("parser_id"), rs.getString("parser_version"), rs.getString("language"), rs.getString("language_detector_version"), rs.getString("normalized_text")) },
                    oldParseId, predecessorRunId, indexing.entryId,
                ).singleOrNull() ?: error("Predecessor Cited Paper parse is missing for retained indexing output.")
                val newParseId = UUID.randomUUID()
                jdbc.update(
                    "INSERT INTO cited_paper_parses (id, analysis_run_id, bibliography_entry_id, canonical_paper_id, content_sha256, parser_id, parser_version, language, language_detector_version, normalized_text, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    newParseId, successorRunId, successorEntryId, parse.canonicalPaperId, parse.contentSha256,
                    parse.parserId, parse.parserVersion, parse.language, parse.languageDetectorVersion, parse.normalizedText, Timestamp.from(now),
                )
                recordLineage(successorRunId, predecessorRunId, "cited-paper-parse", newParseId, oldParseId, predecessorRunId, oldParseId, parse.parserId, null, null, "UNSELECTED_EVIDENCE_RETAINED", now)
                val chunks = jdbc.query(
                    "SELECT id, chunk_order, section_order, section_heading, paragraph_start, paragraph_end, page_number, text FROM paper_chunks WHERE cited_paper_parse_id = ? AND analysis_run_id = ? AND bibliography_entry_id = ? ORDER BY chunk_order",
                    { rs, _ -> PaperChunkRow(rs.uuid("id"), rs.getInt("chunk_order"), rs.getInt("section_order"), rs.getString("section_heading"), rs.getInt("paragraph_start"), rs.getInt("paragraph_end"), rs.getObject("page_number", Integer::class.java)?.toInt(), rs.getString("text")) },
                    oldParseId, predecessorRunId, indexing.entryId,
                )
                chunks.forEach { chunk ->
                    val newChunkId = UUID.randomUUID()
                    successorChunks[chunk.id] = newChunkId
                    jdbc.update(
                        "INSERT INTO paper_chunks (id, cited_paper_parse_id, analysis_run_id, bibliography_entry_id, chunk_order, section_order, section_heading, paragraph_start, paragraph_end, page_number, text, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        newChunkId, newParseId, successorRunId, successorEntryId, chunk.order, chunk.sectionOrder,
                        chunk.sectionHeading, chunk.paragraphStart, chunk.paragraphEnd, chunk.pageNumber, chunk.text, Timestamp.from(now),
                    )
                    recordLineage(successorRunId, predecessorRunId, "paper-chunk", newChunkId, chunk.id, predecessorRunId, chunk.id, null, null, null, "UNSELECTED_EVIDENCE_RETAINED", now)
                }
                cloneEmbeddings(predecessorRunId, successorRunId, indexing.entryId, successorEntryId, successorChunks, now)
                newParseId
            }

            jdbc.update(
                "INSERT INTO cited_paper_indexing (analysis_run_id, bibliography_entry_id, status, failure_reason, cited_paper_parse_id, retrieval_profile_id, vector_candidate_limit, lexical_candidate_limit, final_candidate_limit, reciprocal_rank_fusion_constant, embedding_provider, embedding_model, embedding_version, embedding_dimension, embedding_profile_hash, candidate_count, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                successorRunId, successorEntryId, indexing.status, indexing.failureReason, successorParseId,
                indexing.profileId, indexing.vectorLimit, indexing.lexicalLimit, indexing.finalLimit,
                indexing.fusionConstant, indexing.embeddingProvider, indexing.embeddingModel, indexing.embeddingVersion,
                indexing.embeddingDimension, indexing.embeddingProfileHash, indexing.candidateCount, Timestamp.from(now), Timestamp.from(now),
            )
            recordLineage(successorRunId, predecessorRunId, "cited-paper-indexing", successorEntryId, indexing.entryId, predecessorRunId, indexing.entryId, indexing.embeddingProvider, null, null, "UNSELECTED_EVIDENCE_RETAINED", now)
        }

        val candidates = jdbc.query(
            "SELECT id, verification_id, paper_chunk_id, bibliography_entry_id, profile_hash, vector_rank, lexical_rank, fused_rank, fusion_score, retrieval_profile_id FROM evidence_candidates WHERE analysis_run_id = ? ORDER BY bibliography_entry_id, fused_rank",
            { rs, _ -> EvidenceCandidateRow(rs.uuid("id"), rs.uuid("verification_id"), rs.uuid("paper_chunk_id"), rs.uuid("bibliography_entry_id"), rs.getString("profile_hash"), rs.getObject("vector_rank", Integer::class.java)?.toInt(), rs.getObject("lexical_rank", Integer::class.java)?.toInt(), rs.getInt("fused_rank"), rs.getDouble("fusion_score"), rs.getString("retrieval_profile_id")) },
            predecessorRunId,
        ).filterNot { it.entryId in selectedEntryIds }
        candidates.forEach { candidate ->
            val newEntryId = entries[candidate.entryId] ?: return@forEach
            val newVerificationId = verifications[candidate.verificationId] ?: error("Retained Evidence Candidate has no successor Verification.")
            val newChunkId = successorChunks[candidate.chunkId] ?: error("Retained Evidence Candidate has no successor Paper Chunk.")
            val newCandidateId = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO evidence_candidates (id, verification_id, paper_chunk_id, analysis_run_id, bibliography_entry_id, profile_hash, vector_rank, lexical_rank, fused_rank, fusion_score, retrieval_profile_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                newCandidateId, newVerificationId, newChunkId, successorRunId, newEntryId, candidate.profileHash,
                candidate.vectorRank, candidate.lexicalRank, candidate.fusedRank, candidate.fusionScore,
                candidate.profileId, Timestamp.from(now),
            )
            recordLineage(successorRunId, predecessorRunId, "evidence-candidate", newCandidateId, candidate.id, predecessorRunId, candidate.id, null, null, null, "UNSELECTED_EVIDENCE_RETAINED", now)
            cloneEvidenceJudgement(predecessorRunId, successorRunId, candidate, newCandidateId, newVerificationId, newEntryId, now)
            clonePassageSpans(predecessorRunId, successorRunId, candidate.id, candidate.verificationId, candidate.entryId, newCandidateId, newVerificationId, newEntryId, now)
        }
    }

    private fun cloneEmbeddings(
        predecessorRunId: UUID,
        successorRunId: UUID,
        predecessorEntryId: UUID,
        successorEntryId: UUID,
        chunks: Map<UUID, UUID>,
        now: Instant,
    ) {
        if (chunks.isEmpty()) return
        val rows = jdbc.query(
            "SELECT id, paper_chunk_id, provider_id, model_id, provider_version, dimension, profile_hash, embedding::text AS embedding FROM paper_chunk_embeddings WHERE analysis_run_id = ? AND bibliography_entry_id = ? ORDER BY paper_chunk_id, profile_hash",
            { rs, _ -> EmbeddingRow(rs.uuid("id"), rs.uuid("paper_chunk_id"), rs.getString("provider_id"), rs.getString("model_id"), rs.getString("provider_version"), rs.getInt("dimension"), rs.getString("profile_hash"), rs.getString("embedding")) },
            predecessorRunId,
            predecessorEntryId,
        )
        rows.forEach { row ->
            val newChunkId = chunks[row.chunkId] ?: return@forEach
            val newId = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO paper_chunk_embeddings (id, paper_chunk_id, analysis_run_id, bibliography_entry_id, provider_id, model_id, provider_version, dimension, profile_hash, embedding, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?)",
                newId, newChunkId, successorRunId, successorEntryId, row.provider, row.model, row.version, row.dimension, row.profileHash, row.embedding, Timestamp.from(now),
            )
            recordLineage(successorRunId, predecessorRunId, "paper-chunk-embedding", newId, row.id, predecessorRunId, row.id, row.provider, null, null, "UNSELECTED_EVIDENCE_RETAINED", now)
        }
    }

    private fun cloneEvidenceJudgement(
        predecessorRunId: UUID,
        successorRunId: UUID,
        candidate: EvidenceCandidateRow,
        newCandidateId: UUID,
        newVerificationId: UUID,
        newEntryId: UUID,
        now: Instant,
    ) {
        val row = jdbc.query(
            "SELECT id, system_one_provider, system_one_model, system_one_version, judgement, evidence_role, confidence, directness, claim_scope_match, study_design_quality, relevance, raw_scores::text FROM evidence_judgements WHERE evidence_candidate_id = ? AND verification_id = ? AND analysis_run_id = ? AND bibliography_entry_id = ?",
            { rs, _ -> EvidenceJudgementRow(rs.uuid("id"), rs.getString("system_one_provider"), rs.getString("system_one_model"), rs.getString("system_one_version"), rs.getString("judgement"), rs.getString("evidence_role"), rs.getDouble("confidence"), rs.getDouble("directness"), rs.getDouble("claim_scope_match"), rs.getDouble("study_design_quality"), rs.getDouble("relevance"), rs.getString("raw_scores")) },
            candidate.id, candidate.verificationId, predecessorRunId, candidate.entryId,
        ).singleOrNull() ?: return
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO evidence_judgements (id, evidence_candidate_id, verification_id, analysis_run_id, bibliography_entry_id, system_one_provider, system_one_model, system_one_version, judgement, evidence_role, confidence, directness, claim_scope_match, study_design_quality, relevance, raw_scores, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)",
            id, newCandidateId, newVerificationId, successorRunId, newEntryId, row.provider, row.model, row.version,
            row.judgement, row.role, row.confidence, row.directness, row.claimScopeMatch, row.studyDesignQuality,
            row.relevance, row.rawScores, Timestamp.from(now),
        )
        recordLineage(successorRunId, predecessorRunId, "evidence-judgement", id, row.id, predecessorRunId, row.id, row.provider, null, null, "UNSELECTED_EVIDENCE_RETAINED", now)
    }

    private fun clonePassageSpans(
        predecessorRunId: UUID,
        successorRunId: UUID,
        candidateId: UUID,
        predecessorVerificationId: UUID,
        predecessorEntryId: UUID,
        newCandidateId: UUID,
        newVerificationId: UUID,
        newEntryId: UUID,
        now: Instant,
    ) {
        val rows = jdbc.query(
            "SELECT id, splitting_policy_version, span_index, core_start_offset, core_end_offset, context_start_offset, context_end_offset, token_counts::text, system_one_provider, system_one_model, system_one_version, judgement_rubric_version, status, failure_reason, judgement, evidence_role, confidence, directness, claim_scope_match, study_design_quality, relevance, raw_scores::text FROM laya_evidence_passage_spans WHERE evidence_candidate_id = ? AND verification_id = ? AND analysis_run_id = ? AND bibliography_entry_id = ? ORDER BY span_index",
            { rs, _ -> LayaSpanRow(rs.uuid("id"), rs.getString("splitting_policy_version"), rs.getInt("span_index"), rs.getInt("core_start_offset"), rs.getInt("core_end_offset"), rs.getInt("context_start_offset"), rs.getInt("context_end_offset"), rs.getString("token_counts"), rs.getString("system_one_provider"), rs.getString("system_one_model"), rs.getString("system_one_version"), rs.getString("judgement_rubric_version"), rs.getString("status"), rs.getString("failure_reason"), rs.getString("judgement"), rs.getString("evidence_role"), rs.getObject("confidence") as Double?, rs.getObject("directness") as Double?, rs.getObject("claim_scope_match") as Double?, rs.getObject("study_design_quality") as Double?, rs.getObject("relevance") as Double?, rs.getString("raw_scores")) },
            candidateId, predecessorVerificationId, predecessorRunId, predecessorEntryId,
        )
        rows.forEach { row ->
            val id = UUID.randomUUID()
            jdbc.update(
                "INSERT INTO laya_evidence_passage_spans (id, evidence_candidate_id, verification_id, analysis_run_id, bibliography_entry_id, splitting_policy_version, span_index, core_start_offset, core_end_offset, context_start_offset, context_end_offset, token_counts, system_one_provider, system_one_model, system_one_version, judgement_rubric_version, status, failure_reason, judgement, evidence_role, confidence, directness, claim_scope_match, study_design_quality, relevance, raw_scores, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
                id, newCandidateId, newVerificationId, successorRunId, newEntryId, row.policyVersion, row.index,
                row.coreStart, row.coreEnd, row.contextStart, row.contextEnd, row.tokenCounts, row.provider, row.model,
                row.providerVersion, row.rubricVersion, row.status, row.failureReason, row.judgement, row.role,
                row.confidence, row.directness, row.claimScopeMatch, row.studyDesignQuality, row.relevance,
                row.rawScores, Timestamp.from(now), Timestamp.from(now),
            )
            recordLineage(successorRunId, predecessorRunId, "evidence-passage-span", id, row.id, predecessorRunId, row.id, row.provider, null, null, "UNSELECTED_EVIDENCE_RETAINED", now)
        }
    }

    private fun initializeProgress(
        predecessorRunId: UUID,
        successorRunId: UUID,
        entries: List<Triple<UUID, UUID, String>>,
        selectedEntryIds: Set<UUID>,
        assistedResolutionEntryIds: Set<UUID>,
        identityOnlyEntryIds: Set<UUID>,
    ) {
        pipelineProgressRepository.initializeRun(successorRunId)
        pipelineProgressRepository.mark(successorRunId, "source", "parse-document", "document", "Source Document", "COMPLETED", "SOURCE_PARSE_REUSED")
        val predecessorProgress = jdbc.query(
            "SELECT stage_id, step_id, item_id, status, reason_code FROM analysis_run_pipeline_items WHERE analysis_run_id = ?",
            { rs, _ -> ProgressRow(rs.getString("stage_id"), rs.getString("step_id"), rs.getString("item_id"), rs.getString("status"), rs.getString("reason_code")) },
            predecessorRunId,
        ).associateBy { Triple(it.stageId, it.stepId, it.itemId) }
        entries.forEach { (predecessorEntryId, successorEntryId, key) ->
            val itemId = successorEntryId.toString()
            listOf("references" to "resolve-entry", "access" to "acquire-source", "evidence" to "prepare-evidence", "verification" to "assess-and-aggregate").forEach { (stage, step) ->
                val selected = successorEntryId in selectedEntryIds
                val predecessorItem = predecessorProgress[Triple(stage, step, predecessorEntryId.toString())]
                val awaitingAssistedResolution = successorEntryId in assistedResolutionEntryIds
                val identityOnly = successorEntryId in identityOnlyEntryIds
                val (status, reason) = when {
                    identityOnly && stage == "references" -> "WAITING" to null
                    identityOnly -> "SKIPPED" to "DOI_ONLY_NO_PDF_ASSET"
                    selected && stage == "references" && awaitingAssistedResolution -> "WAITING" to null
                    selected && stage == "references" -> "COMPLETED" to "REFERENCE_OUTCOME_REUSED"
                    selected && stage == "access" && awaitingAssistedResolution -> "WAITING" to null
                    selected && stage == "access" -> "COMPLETED" to "USER_SUPPLIED_ASSET_SELECTED"
                    selected && stage in setOf("evidence", "verification") -> "WAITING" to null
                    predecessorItem != null -> predecessorItem.status to predecessorItem.reasonCode
                    stage == "references" -> "COMPLETED" to "REFERENCE_OUTCOME_REUSED"
                    stage == "access" -> "COMPLETED" to "ACCESS_OUTCOME_REUSED"
                    else -> "SKIPPED" to "LEGACY_PROGRESS_NOT_RECORDED"
                }
                pipelineProgressRepository.mark(successorRunId, stage, step, itemId, key, status, reason)
            }
        }
    }

    private fun recordLineage(
        successorRunId: UUID,
        predecessorRunId: UUID,
        artifactType: String,
        successorArtifactId: UUID,
        predecessorArtifactId: UUID?,
        originatingRunId: UUID,
        originatingArtifactId: UUID,
        providerId: String?,
        configurationFingerprint: String?,
        consentFingerprint: String?,
        reasonCode: String,
        now: Instant,
        userSuppliedAccess: Boolean = false,
    ) {
        if (predecessorArtifactId == null) return
        val inherited = jdbc.query(
            "SELECT originating_analysis_run_id, originating_artifact_id, originating_provider_id, originating_configuration_fingerprint, originating_consent_fingerprint, originating_data_categories::text, originating_retention_disclosure FROM analysis_run_successor_artifact_lineage WHERE successor_analysis_run_id = ? AND artifact_type = ? AND successor_artifact_id = ?",
            { rs, _ -> InheritedLineage(
                originatingRunId = rs.uuid("originating_analysis_run_id"),
                originatingArtifactId = rs.uuid("originating_artifact_id"),
                providerId = rs.getString("originating_provider_id"),
                configurationFingerprint = rs.getString("originating_configuration_fingerprint"),
                consentFingerprint = rs.getString("originating_consent_fingerprint"),
                dataCategories = com.papertrail.api.utils.JsonUtil.fromJson(rs.getString("originating_data_categories"), com.papertrail.api.utils.JsonUtil.collectionType(List::class.java, String::class.java)),
                retentionDisclosure = rs.getString("originating_retention_disclosure"),
            ) },
            predecessorRunId,
            artifactType,
            predecessorArtifactId,
        ).firstOrNull()
        val actualOriginRunId = if (userSuppliedAccess) originatingRunId else inherited?.originatingRunId ?: originatingRunId
        val actualOriginArtifactId = if (userSuppliedAccess) originatingArtifactId else inherited?.originatingArtifactId ?: originatingArtifactId

        val originConfiguration = if (userSuppliedAccess) null else configurationSnapshot(actualOriginRunId, "Origin")
        val predecessorConfiguration = if (userSuppliedAccess) null else configurationSnapshot(predecessorRunId, "Predecessor")
        val originStageSelection = originConfiguration?.lineageProviderSelection(artifactType, inherited?.providerId ?: providerId)
        val inferProviderFromStage = !(artifactType == "cited-paper-access" && providerId == null && inherited?.providerId == null)
        val actualProviderId = if (userSuppliedAccess) null else inherited?.providerId ?: providerId ?: if (inferProviderFromStage) originStageSelection?.provider else null
        if (artifactType == "cited-paper-access" && actualProviderId != null && originStageSelection?.provider != actualProviderId) {
            error("Cited-paper access provider provenance does not match its originating configuration.")
        }
        val originProviderSelections = originConfiguration?.providerSelections().orEmpty()
        val selectionsForProvider = actualProviderId?.let { id -> originProviderSelections.filter { it.provider == id } }.orEmpty()
        if (selectionsForProvider.any { it.trustBoundary == "UNREVIEWED" }) {
            error("Outputs from an unreviewed provider cannot be reused in a successor run.")
        }
        val externalSelections = selectionsForProvider.filter { it.trustBoundary == "EXTERNAL" }
        val externalProvider = !userSuppliedAccess && (externalSelections.isNotEmpty() || originStageSelection?.let {
            it.provider == actualProviderId && it.trustBoundary == "EXTERNAL"
        } == true)
        val originConsent = if (externalProvider && actualProviderId != null) {
            val categories = externalSelections.flatMap(ProviderSelection::dataCategories).distinct().sorted()
            val disclosures = externalSelections.map(ProviderSelection::retentionDisclosure).distinct()
            val disclosure = disclosures.singleOrNull()
            checkNotNull(originConfiguration).externalProviderConsents.singleOrNull { consent ->
                consent.providerId == actualProviderId && consent.dataCategories == categories &&
                    disclosure != null && consent.retentionDisclosure == disclosure && !disclosure.isNullOrBlank()
            }
        } else {
            null
        }
        if (externalProvider) {
            val predecessorSnapshot = checkNotNull(predecessorConfiguration)
            val originatingSelection = originStageSelection?.takeIf { it.provider == actualProviderId }
            check(originatingSelection?.trustBoundary == "EXTERNAL" && originConsent != null) {
                "External-provider output is missing matching originating consent."
            }
            val consentHash = com.papertrail.api.infrastructure.crypto.sha256Hex(com.papertrail.api.utils.JsonUtil.toJson(originConsent).toByteArray())
            val predecessorSelections = predecessorSnapshot.providerSelections()
            val predecessorStageSelection = predecessorSnapshot.lineageProviderSelection(artifactType, actualProviderId)
            val predecessorExternalSelections = predecessorSelections.filter {
                it.provider == actualProviderId && it.trustBoundary == "EXTERNAL"
            }
            val predecessorCategories = predecessorExternalSelections.flatMap(ProviderSelection::dataCategories).distinct().sorted()
            val predecessorDisclosures = predecessorExternalSelections.map(ProviderSelection::retentionDisclosure).distinct()
            val predecessorDisclosure = predecessorDisclosures.singleOrNull()
            val predecessorConsent = predecessorSnapshot.externalProviderConsents.singleOrNull { consent ->
                consent.providerId == actualProviderId && consent.dataCategories == predecessorCategories &&
                    predecessorDisclosure != null && consent.retentionDisclosure == predecessorDisclosure
            }
            check(predecessorStageSelection?.provider == actualProviderId && predecessorStageSelection?.trustBoundary == "EXTERNAL" &&
                predecessorConsent == originConsent) {
                "Successor configuration does not preserve the exact consent for reused external-provider output."
            }
            if (inherited != null) {
                check(inherited.providerId == actualProviderId && inherited.dataCategories == originConsent.dataCategories &&
                    inherited.retentionDisclosure == originConsent.retentionDisclosure && inherited.consentFingerprint == consentHash) {
                    "Inherited external-provider output provenance no longer matches its originating consent."
                }
            }
        }
        val dataCategories = if (userSuppliedAccess) emptyList() else
            originConsent?.dataCategories ?: inherited?.dataCategories ?: originStageSelection?.dataCategories.orEmpty()
        val retentionDisclosure = if (userSuppliedAccess) null else
            originConsent?.retentionDisclosure ?: inherited?.retentionDisclosure ?: originStageSelection?.retentionDisclosure
        val resolvedConfigurationFingerprint = if (userSuppliedAccess) null else inherited?.configurationFingerprint ?: configurationFingerprint
            ?: checkNotNull(originConfiguration).lineageConfigurationFingerprint(artifactType, originStageSelection)
        val resolvedConsentFingerprint = if (userSuppliedAccess) null else inherited?.consentFingerprint ?: consentFingerprint ?: originConsent?.let {
            com.papertrail.api.infrastructure.crypto.sha256Hex(com.papertrail.api.utils.JsonUtil.toJson(it).toByteArray())
        }
        jdbc.update(
            """
            INSERT INTO analysis_run_successor_artifact_lineage (
                successor_analysis_run_id, predecessor_analysis_run_id, artifact_type,
                successor_artifact_id, predecessor_artifact_id, originating_analysis_run_id,
                originating_artifact_id, originating_provider_id, originating_configuration_fingerprint,
                originating_consent_fingerprint, originating_data_categories, originating_retention_disclosure,
                reuse_reason_code, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
            ON CONFLICT (successor_analysis_run_id, artifact_type, successor_artifact_id) DO NOTHING
            """.trimIndent(),
            successorRunId, predecessorRunId, artifactType, successorArtifactId, predecessorArtifactId,
            actualOriginRunId, actualOriginArtifactId, actualProviderId, resolvedConfigurationFingerprint, resolvedConsentFingerprint,
            com.papertrail.api.utils.JsonUtil.toJson(dataCategories), retentionDisclosure, reasonCode, Timestamp.from(now),
        )
    }

    private fun configurationSnapshot(analysisRunId: UUID, role: String): AnalysisConfigurationSnapshot {
        val json = jdbc.query(
            "SELECT configuration_snapshot::text FROM analysis_runs WHERE id = ?",
            { rs, _ -> rs.getString(1) },
            analysisRunId,
        ).singleOrNull() ?: error("$role Analysis Run configuration is missing for reused output.")
        return com.papertrail.api.utils.JsonUtil.fromJson(json, AnalysisConfigurationSnapshot::class.java)
    }

    private fun AnalysisConfigurationSnapshot.providerSelections(): List<ProviderSelection> = listOfNotNull(
        claimExtractor,
        embedding,
        systemOne,
        sourceParser,
        languageDetector,
        referenceResolution.provider,
        openAccess,
        citedPaperParserSelection(),
    ) + referenceResolution.providerChain?.providers.orEmpty()

    private fun AnalysisConfigurationSnapshot.lineageProviderSelection(
        artifactType: String,
        providerId: String? = null,
    ): ProviderSelection? = when (artifactType) {
        "source-parse", "source-section", "citation-context", "bibliography-entry", "citation-occurrence", "citation-target" -> sourceParser
        "atomic-claim", "claim-citation-target" -> claimExtractor
        "bibliography-resolution" -> referenceResolution.providerChain?.providers?.firstOrNull { it.provider == providerId }
            ?: referenceResolution.provider
        "cited-paper-access" -> openAccess
        "claim-paper-verification", "evidence-judgement", "evidence-passage-span" -> systemOne
        "cited-paper-parse", "paper-chunk" -> citedPaperParserSelection()
        "cited-paper-indexing", "paper-chunk-embedding", "evidence-candidate" -> embedding
        else -> null
    }

    private fun AnalysisConfigurationSnapshot.lineageConfigurationFingerprint(
        artifactType: String,
        selection: ProviderSelection?,
    ): String? = when (artifactType) {
        "bibliography-resolution" -> referenceResolution.providerConfigurationFingerprint ?: selection?.configurationFingerprint
        "cited-paper-access" -> openAccessProviderConfigurationFingerprint ?: selection?.configurationFingerprint
        else -> selection?.configurationFingerprint
    }

    private fun ResultSet.uuid(column: String): UUID = getObject(column, UUID::class.java)

    private fun ResultSet.uuidOrNull(column: String): UUID? = getObject(column, UUID::class.java)

    private fun ResultSet.intOrNull(column: String): Int? = getObject(column)?.let { (it as Number).toInt() }

    private fun ResultSet.longOrNull(column: String): Long? = getObject(column)?.let { (it as Number).toLong() }

    private fun ResultSet.toPersistedSubmission() = PersistedSuccessorSubmission(
        id = uuid("id"), batchId = uuid("batch_id"), predecessorAnalysisRunId = uuid("predecessor_analysis_run_id"),
        successorAnalysisRunId = uuid("successor_analysis_run_id"), idempotencyKey = uuid("idempotency_key"),
        requestFingerprint = getString("request_fingerprint"), planFingerprint = getString("plan_fingerprint"), revision = getLong("submitted_revision"),
        selectedEntryCount = getInt("selected_entry_count"), retainedEntryCount = getInt("retained_entry_count"), queuedEntryCount = getInt("queued_entry_count"),
        identifierAssistanceEntryCount = getInt("identifier_assistance_entry_count"), resolutionQueuedEntryCount = getInt("resolution_queued_entry_count"),
    )

    private data class SectionRow(val id: UUID, val order: Int, val heading: String?, val text: String, val start: Int, val end: Int, val sourceMetadata: String)
    private data class ContextRow(val id: UUID, val sectionId: UUID, val text: String, val boundaryKind: String, val start: Int, val end: Int)
    private data class BibliographyRow(val id: UUID, val order: Int, val key: String, val rawText: String, val title: String?, val authors: String, val year: Int?, val doi: String?, val referenceType: String, val resolutionStatus: String, val sourceElement: String?, val sourceTextContent: String?, val sourceLocalKey: String?, val localKeyOrigin: String?, val identifiers: String?, val sourceLocations: String?, val artifactSignals: String?, val extractionLimitations: String?, val captureStatus: String?)
    private data class OccurrenceRow(val id: UUID, val contextId: UUID, val sectionId: UUID, val marker: String, val start: Int, val end: Int, val unmatchedKeys: String?)
    private data class TargetRow(val id: UUID, val occurrenceId: UUID, val contextId: UUID, val entryId: UUID, val order: Int)
    private data class ClaimRow(val id: UUID, val contextId: UUID, val text: String, val start: Int, val end: Int)
    private data class ClaimTargetRow(val id: UUID, val contextId: UUID, val claimId: UUID, val targetId: UUID, val kind: String)
    private data class ResolutionRow(val entryId: UUID, val status: String, val reasonCode: String, val canonicalPaperId: UUID?, val matchedDoi: String?, val matchedTitle: String?, val matchedAuthors: String, val matchedYear: Int?, val confidence: Double?, val matchMethod: String?, val providerId: String, val candidateEvidence: String, val chainState: ProviderChainStateRow?)
    private data class ProviderChainStateRow(val ownerToken: UUID, val ownerFence: Long, val leaseExpiresAt: Instant, val terminalOutcome: String?, val metadataOriginProviderId: String?)
    private data class ChainHistoryRow(val entryId: UUID, val chainState: ProviderChainStateRow)
    private data class ProviderLookupRow(val lookupOrdinal: Int, val providerId: String, val source: String, val outcomeCode: String, val recordedAt: Instant)
    private data class ProviderAttemptRow(val reservationOrdinal: Int, val lookupOrdinal: Int, val providerId: String, val providerAttemptOrdinal: Int, val ownerToken: UUID, val ownerFence: Long, val state: String, val outcomeCode: String?, val nextAttemptNotBefore: Instant?, val httpStatus: Int?, val durationMillis: Long?, val responseBytes: Long?, val reservedAt: Instant, val startedAt: Instant?, val completedAt: Instant?, val unknownAt: Instant?, val deadlineAt: Instant)
    private data class AccessRow(val entryId: UUID, val canonicalPaperId: UUID, val accessStatus: String, val accessReason: String?, val accessReasons: String, val providerId: String?, val metadata: Boolean, val abstract: Boolean, val sourceUrl: String?, val license: String?, val locationVersion: String?, val hostType: String?, val discoveredAt: Instant, val objectKey: String?, val hash: String?, val mediaType: String?, val language: String?, val detectorVersion: String?, val origin: String, val rightsVersion: String?, val rightsText: String?, val rightsAt: Instant?, val originRun: UUID?, val originBatch: UUID?, val originUpload: UUID?, val assetId: UUID)
    private data class VerificationRow(val id: UUID, val claimId: UUID, val entryId: UUID, val canonicalPaperId: UUID?, val status: String, val scope: String, val terminalReason: String?, val finalStatus: String?, val evidenceConflict: Boolean, val failureReason: String?, val aggregatorVersion: String?)
    private data class IndexingRow(val entryId: UUID, val status: String, val failureReason: String?, val parseId: UUID?, val profileId: String, val vectorLimit: Int, val lexicalLimit: Int, val finalLimit: Int, val fusionConstant: Int, val embeddingProvider: String, val embeddingModel: String, val embeddingVersion: String, val embeddingDimension: Int, val embeddingProfileHash: String, val candidateCount: Int)
    private data class CitedParseRow(val canonicalPaperId: UUID, val contentSha256: String, val parserId: String, val parserVersion: String, val language: String, val languageDetectorVersion: String, val normalizedText: String)
    private data class PaperChunkRow(val id: UUID, val order: Int, val sectionOrder: Int, val sectionHeading: String?, val paragraphStart: Int, val paragraphEnd: Int, val pageNumber: Int?, val text: String)
    private data class EmbeddingRow(val id: UUID, val chunkId: UUID, val provider: String, val model: String, val version: String, val dimension: Int, val profileHash: String, val embedding: String)
    private data class EvidenceCandidateRow(val id: UUID, val verificationId: UUID, val chunkId: UUID, val entryId: UUID, val profileHash: String, val vectorRank: Int?, val lexicalRank: Int?, val fusedRank: Int, val fusionScore: Double, val profileId: String)
    private data class EvidenceJudgementRow(val id: UUID, val provider: String, val model: String?, val version: String, val judgement: String, val role: String, val confidence: Double, val directness: Double, val claimScopeMatch: Double, val studyDesignQuality: Double, val relevance: Double, val rawScores: String)
    private data class LayaSpanRow(val id: UUID, val policyVersion: String, val index: Int, val coreStart: Int, val coreEnd: Int, val contextStart: Int, val contextEnd: Int, val tokenCounts: String, val provider: String, val model: String, val providerVersion: String, val rubricVersion: String, val status: String, val failureReason: String?, val judgement: String?, val role: String?, val confidence: Double?, val directness: Double?, val claimScopeMatch: Double?, val studyDesignQuality: Double?, val relevance: Double?, val rawScores: String?)
    private data class ProgressRow(val stageId: String, val stepId: String, val itemId: String, val status: String, val reasonCode: String?)
    private data class InheritedLineage(val originatingRunId: UUID, val originatingArtifactId: UUID, val providerId: String?, val configurationFingerprint: String?, val consentFingerprint: String?, val dataCategories: List<String>, val retentionDisclosure: String?)
}

data class SubmissionContext(val predecessorAnalysisRunId: UUID, val documentId: UUID)

data class VerifiedSuccessorUpload(
    val predecessorBibliographyEntryId: UUID,
    val upload: RecoveryUpload,
    val bytes: ByteArray,
    val validationAttempt: com.papertrail.api.analysis.recovery.domain.RecoveryUploadValidationAttempt,
)

data class LockedPredecessor(
    val analysisRunId: UUID,
    val documentId: UUID,
    val sourceContentSha256: String,
    val sourceParserId: String,
    val sourceParserVersion: String,
    val configurationJson: String,
    val status: String,
)

data class LockedBatch(val id: UUID, val analysisRunId: UUID, val status: String, val expiresAt: Instant, val revision: Long)

class SuccessorSelectedAccessAttemptFailed(
    val batchId: UUID,
    val predecessorAnalysisRunId: UUID,
    val bibliographyEntryId: UUID,
    val uploadId: UUID,
    cause: Throwable,
) : RuntimeException("Selected successor access could not be materialized.", cause)

data class PersistedSuccessorSubmission(
    val id: UUID,
    val batchId: UUID,
    val predecessorAnalysisRunId: UUID,
    val successorAnalysisRunId: UUID,
    val idempotencyKey: UUID,
    val requestFingerprint: String,
    val planFingerprint: String,
    val revision: Long,
    val selectedEntryCount: Int,
    val retainedEntryCount: Int,
    val queuedEntryCount: Int,
    val identifierAssistanceEntryCount: Int = 0,
    val resolutionQueuedEntryCount: Int = 0,
    val replayed: Boolean = false,
) {
    fun replay() = copy(replayed = true)
}
