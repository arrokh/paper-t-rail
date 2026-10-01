package com.papertrail.api.analysis.service

import com.papertrail.api.analysis.http.AnalysisRunPipelineProgress
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class AnalysisRunPipelineProgressRepository(
    private val jdbc: JdbcTemplate,
) {
    fun initializeRun(analysisRunId: UUID) {
        mark(analysisRunId, "source", "parse-document", "document", "Source Document", "WAITING")
    }

    fun initializeBibliographyItems(
        analysisRunId: UUID,
        entries: List<BibliographyWorkItem>,
        resolutionConfigured: Boolean,
        accessConfigured: Boolean,
        evidenceConfigured: Boolean,
        verificationConfigured: Boolean,
    ) {
        entries.forEach { entry ->
            val itemId = entry.id.toString()
            mark(
                analysisRunId,
                "references",
                "resolve-entry",
                itemId,
                entry.referenceKey,
                if (resolutionConfigured) "WAITING" else "SKIPPED",
                if (resolutionConfigured) null else "REFERENCE_RESOLUTION_NOT_CONFIGURED",
            )
            mark(
                analysisRunId,
                "access",
                "acquire-source",
                itemId,
                entry.referenceKey,
                if (resolutionConfigured && accessConfigured) "WAITING" else "SKIPPED",
                if (resolutionConfigured && accessConfigured) null else "ACCESS_PATH_NOT_CONFIGURED",
            )
            mark(
                analysisRunId,
                "evidence",
                "prepare-evidence",
                itemId,
                entry.referenceKey,
                if (resolutionConfigured && accessConfigured && evidenceConfigured) "WAITING" else "SKIPPED",
                if (resolutionConfigured && accessConfigured && evidenceConfigured) null else "EVIDENCE_PATH_NOT_CONFIGURED",
            )
            mark(
                analysisRunId,
                "verification",
                "assess-and-aggregate",
                itemId,
                entry.referenceKey,
                if (resolutionConfigured && accessConfigured && evidenceConfigured && verificationConfigured) "WAITING" else "SKIPPED",
                if (resolutionConfigured && accessConfigured && evidenceConfigured && verificationConfigured) null else "VERIFICATION_PATH_NOT_CONFIGURED",
            )
        }
    }

    fun mark(
        analysisRunId: UUID,
        stageId: String,
        stepId: String,
        itemId: String,
        label: String,
        status: String,
        reasonCode: String? = null,
    ) {
        require(status in ITEM_STATUSES) { "Unsupported pipeline item status '$status'." }
        require(VALID_STEPS[stageId] == stepId) { "Unsupported pipeline step '$stageId/$stepId'." }
        jdbc.update(
            """
            INSERT INTO analysis_run_pipeline_items (
                analysis_run_id, stage_id, step_id, item_id, label, status, reason_code,
                started_at, completed_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?,
                      CASE WHEN ? = 'IN_PROGRESS' THEN now() ELSE NULL END,
                      CASE WHEN ? IN ('COMPLETED', 'SKIPPED', 'FAILED') THEN now() ELSE NULL END,
                      now())
            ON CONFLICT (analysis_run_id, stage_id, step_id, item_id) DO UPDATE
               SET label = EXCLUDED.label,
                   status = CASE
                       WHEN analysis_run_pipeline_items.status IN ('COMPLETED', 'SKIPPED', 'FAILED')
                       THEN analysis_run_pipeline_items.status
                       ELSE EXCLUDED.status
                   END,
                   reason_code = CASE
                       WHEN analysis_run_pipeline_items.status IN ('COMPLETED', 'SKIPPED', 'FAILED')
                       THEN analysis_run_pipeline_items.reason_code
                       ELSE EXCLUDED.reason_code
                   END,
                   started_at = COALESCE(analysis_run_pipeline_items.started_at, EXCLUDED.started_at),
                   completed_at = CASE
                       WHEN EXCLUDED.status IN ('COMPLETED', 'SKIPPED', 'FAILED') THEN COALESCE(analysis_run_pipeline_items.completed_at, EXCLUDED.completed_at)
                       ELSE analysis_run_pipeline_items.completed_at
                   END,
                   updated_at = now()
            """.trimIndent(),
            analysisRunId,
            stageId,
            stepId,
            itemId,
            label.take(MAX_LABEL_LENGTH),
            status,
            reasonCode,
            status,
            status,
        )
    }

    fun markBibliographyItem(
        analysisRunId: UUID,
        stageId: String,
        stepId: String,
        bibliographyEntryId: UUID,
        status: String,
        reasonCode: String? = null,
    ) {
        val referenceKey = jdbc.queryForObject(
            "SELECT local_reference_key FROM bibliography_entries WHERE analysis_run_id = ? AND id = ?",
            String::class.java,
            analysisRunId,
            bibliographyEntryId,
        ) ?: return
        mark(analysisRunId, stageId, stepId, bibliographyEntryId.toString(), referenceKey, status, reasonCode)
    }

    fun find(analysisRunId: UUID): AnalysisRunPipelineProgress? {
        val rows = jdbc.query(
            """
            SELECT stage_id, step_id, item_id, label, status, reason_code
              FROM analysis_run_pipeline_items
             WHERE analysis_run_id = ?
             ORDER BY CASE stage_id
                        WHEN 'source' THEN 1 WHEN 'references' THEN 2 WHEN 'access' THEN 3
                        WHEN 'evidence' THEN 4 WHEN 'verification' THEN 5 ELSE 6 END,
                      step_id, label, item_id
            """.trimIndent(),
            { rs, _ ->
                PipelineItemRow(
                    stageId = rs.getString("stage_id"),
                    stepId = rs.getString("step_id"),
                    id = rs.getString("item_id"),
                    label = rs.getString("label"),
                    status = rs.getString("status"),
                    reasonCode = rs.getString("reason_code"),
                )
            },
            analysisRunId,
        )
        if (rows.isEmpty()) return null

        val stages = rows.groupBy(PipelineItemRow::stageId).map { (stageId, stageRows) ->
            val steps = stageRows.groupBy(PipelineItemRow::stepId).map { (stepId, stepRows) ->
                AnalysisRunPipelineProgress.Step(
                    id = stepId,
                    status = aggregateStatus(stepRows.map(PipelineItemRow::status)),
                    counts = counts(stepRows.map(PipelineItemRow::status)),
                    items = stepRows.map { AnalysisRunPipelineProgress.Item(it.id, it.label, it.status, it.reasonCode) },
                )
            }
            AnalysisRunPipelineProgress.Stage(
                id = stageId,
                status = aggregateStatus(stageRows.map(PipelineItemRow::status)),
                counts = counts(stageRows.map(PipelineItemRow::status)),
                steps = steps,
            )
        }
        return AnalysisRunPipelineProgress(stages)
    }

    private fun aggregateStatus(statuses: List<String>): String = when {
        statuses.isEmpty() || statuses.all { it == "SKIPPED" } -> "SKIPPED"
        statuses.any { it == "IN_PROGRESS" } -> "IN_PROGRESS"
        statuses.any { it == "WAITING" } -> if (statuses.any { it == "COMPLETED" || it == "FAILED" }) "IN_PROGRESS" else "WAITING"
        statuses.any { it == "FAILED" } && statuses.any { it == "COMPLETED" } -> "COMPLETED_WITH_WARNINGS"
        statuses.any { it == "FAILED" } -> "FAILED"
        else -> "COMPLETED"
    }

    private fun counts(statuses: List<String>) = AnalysisRunPipelineProgress.Counts(
        total = statuses.size,
        waiting = statuses.count { it == "WAITING" },
        inProgress = statuses.count { it == "IN_PROGRESS" },
        completed = statuses.count { it == "COMPLETED" },
        skipped = statuses.count { it == "SKIPPED" },
        failed = statuses.count { it == "FAILED" },
    )

    data class BibliographyWorkItem(val id: UUID, val referenceKey: String)
    private data class PipelineItemRow(
        val stageId: String,
        val stepId: String,
        val id: String,
        val label: String,
        val status: String,
        val reasonCode: String?,
    )

    companion object {
        private const val MAX_LABEL_LENGTH = 120
        private val ITEM_STATUSES = setOf("WAITING", "IN_PROGRESS", "COMPLETED", "SKIPPED", "FAILED")
        private val VALID_STEPS = mapOf(
            "source" to "parse-document",
            "references" to "resolve-entry",
            "access" to "acquire-source",
            "evidence" to "prepare-evidence",
            "verification" to "assess-and-aggregate",
        )
    }
}
