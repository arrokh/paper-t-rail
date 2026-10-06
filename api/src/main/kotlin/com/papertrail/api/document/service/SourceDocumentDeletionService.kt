package com.papertrail.api.document.service

import com.papertrail.api.document.repository.SourceDocumentDeletionRepository
import com.papertrail.api.document.storage.SourceDocumentObjectStore
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class SourceDocumentDeletionService(
    private val repository: SourceDocumentDeletionRepository,
    private val transactionTemplate: TransactionTemplate,
    private val objectStore: SourceDocumentObjectStore,
) {
    fun delete(documentId: UUID) {
        val tombstoneResult = transactionTemplate.execute { repository.markDeleted(documentId) } ?: return
        when (tombstoneResult) {
            SourceDocumentDeletionRepository.TombstoneResult.MARKED -> Unit
            SourceDocumentDeletionRepository.TombstoneResult.ALREADY_DELETED -> return
            SourceDocumentDeletionRepository.TombstoneResult.NOT_FOUND ->
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Source Document not found.")
        }

        try {
            transactionTemplate.executeWithoutResult { purge(documentId) }
        } catch (exception: Exception) {
            logger.atWarn()
                .addKeyValue("documentId", documentId)
                .addKeyValue("errorType", exception.javaClass.simpleName)
                .log("Source Document deletion could not remove every stored object or database record; retry is safe")
            throw ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Source Document data could not be fully removed. Retry deletion.",
            )
        }
    }

    private fun purge(documentId: UUID) {
        if (!repository.lockDocumentForPurge(documentId)) return

        val objectKeys = repository.objectKeysToDelete(documentId)
        val canonicalPaperIds = repository.canonicalPaperIds(documentId)
        objectKeys.forEach(objectStore::delete)

        repository.deleteInboxEvents(documentId)
        repository.deleteOutboxEvents(documentId)
        repository.deleteAnalysisRuns(documentId)
        repository.deleteSourceDocument(documentId)
        canonicalPaperIds.forEach(repository::deleteUnreferencedCanonicalPaper)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SourceDocumentDeletionService::class.java)
    }
}
