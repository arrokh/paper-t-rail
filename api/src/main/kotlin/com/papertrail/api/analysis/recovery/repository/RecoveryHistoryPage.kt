package com.papertrail.api.analysis.recovery.repository

import com.papertrail.api.analysis.recovery.domain.RecoveryHistoryEvent
import java.util.UUID

data class RecoveryHistoryPage(
    val analysisRunId: UUID,
    val events: List<RecoveryHistoryEvent>,
    val nextCursor: String?,
)
