package com.papertrail.api.scholarly.references.attempts.service

import com.papertrail.api.document.repository.SourceDocumentRepository
import org.springframework.stereotype.Component
import java.util.UUID

fun interface ReferenceResolutionTerminalCommitGuard {
    fun lockActiveRun(analysisRunId: UUID)
}

@Component
class ActiveSourceDocumentReferenceResolutionTerminalCommitGuard(
    private val sourceDocumentRepository: SourceDocumentRepository,
) : ReferenceResolutionTerminalCommitGuard {
    override fun lockActiveRun(analysisRunId: UUID) {
        sourceDocumentRepository.lockActiveAnalysisRun(analysisRunId)
    }
}
