package com.papertrail.api.review.repository

import com.papertrail.api.review.domain.HumanReview
import com.papertrail.api.review.domain.HumanReviewAction
import com.papertrail.api.review.domain.HumanReviewTarget
import com.papertrail.api.scholarly.acquisition.domain.TerminalVerificationStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class JdbcHumanReviewRepository(
    private val jdbc: JdbcTemplate,
) : HumanReviewRepository {
    override fun target(verificationId: UUID): HumanReviewTarget? = jdbc.query(
        """
        SELECT analysis_run_id,
               processing_status = 'COMPLETED' AND final_status IS NOT NULL AS has_final_result
          FROM claim_paper_verifications
         WHERE id = ?
        """.trimIndent(),
        { rs, _ ->
            HumanReviewTarget(
                analysisRunId = rs.getObject("analysis_run_id", UUID::class.java),
                hasFinalResult = rs.getBoolean("has_final_result"),
            )
        },
        verificationId,
    ).firstOrNull()

    override fun save(
        analysisRunId: UUID,
        verificationId: UUID,
        action: HumanReviewAction,
        overrideStatus: TerminalVerificationStatus?,
        note: String?,
    ): HumanReview {
        val id = UUID.randomUUID()
        return jdbc.query(
            """
            INSERT INTO human_reviews (
                id, analysis_run_id, verification_id, action, override_status, note
            ) VALUES (?, ?, ?, ?, ?, ?)
            RETURNING created_at
            """.trimIndent(),
            { rs, _ ->
                HumanReview(
                    id = id,
                    analysisRunId = analysisRunId,
                    verificationId = verificationId,
                    action = action,
                    overrideStatus = overrideStatus,
                    note = note,
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                )
            },
            id,
            analysisRunId,
            verificationId,
            action.name,
            overrideStatus?.name,
            note,
        ).single()
    }

    override fun byRun(analysisRunId: UUID): Map<UUID, List<HumanReview>> = jdbc.query(
        """
        SELECT id, analysis_run_id, verification_id, action, override_status, note, created_at
          FROM human_reviews
         WHERE analysis_run_id = ?
         ORDER BY verification_id, created_at, id
        """.trimIndent(),
        { rs, _ -> rs.toHumanReview() },
        analysisRunId,
    ).groupBy(HumanReview::verificationId)

    private fun ResultSet.toHumanReview() = HumanReview(
        id = getObject("id", UUID::class.java),
        analysisRunId = getObject("analysis_run_id", UUID::class.java),
        verificationId = getObject("verification_id", UUID::class.java),
        action = HumanReviewAction.valueOf(getString("action")),
        overrideStatus = getString("override_status")?.let(TerminalVerificationStatus::valueOf),
        note = getString("note"),
        createdAt = getTimestamp("created_at").toInstant(),
    )
}
