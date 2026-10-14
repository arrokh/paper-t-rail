package com.papertrail.api.analysis.recovery.service

import com.papertrail.api.analysis.recovery.domain.RecoveryWorkEventType
import com.papertrail.api.analysis.recovery.repository.RecoveryHistoryPage
import com.papertrail.api.analysis.recovery.repository.RecoveryHistoryRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class RecoveryHistoryService(
    private val repository: RecoveryHistoryRepository,
) {
    fun page(analysisRunId: UUID, cursor: String?, limit: Int): RecoveryHistoryPage =
        repository.page(analysisRunId, cursor, limit)

    fun recordSuccessorWorkEvent(
        successorAnalysisRunId: UUID,
        bibliographyEntryId: UUID,
        eventType: RecoveryWorkEventType,
        outcomeCode: String,
        stageCode: String,
        attemptNumber: Int? = null,
        sourceEventId: UUID? = null,
    ) {
        require(eventType.requiresAttemptNumber == (attemptNumber != null)) {
            "Recovery work event attempt numbers must match the event type."
        }
        require(attemptNumber == null || attemptNumber > 0) { "Recovery work event attempt numbers must be positive." }
        repository.appendForSuccessor(
            successorAnalysisRunId = successorAnalysisRunId,
            bibliographyEntryId = bibliographyEntryId,
            eventType = eventType.code,
            outcomeCode = outcomeCode,
            stageCode = stageCode,
            attemptNumber = attemptNumber,
            sourceEventId = sourceEventId,
        )
    }
}
