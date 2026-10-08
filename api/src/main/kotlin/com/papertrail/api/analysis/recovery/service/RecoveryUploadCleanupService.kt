package com.papertrail.api.analysis.recovery.service

import com.papertrail.api.analysis.recovery.repository.RecoveryBatchRepository
import com.papertrail.api.analysis.recovery.repository.RecoveryUploadCleanupRepository
import com.papertrail.api.infrastructure.storage.SourceDocumentObjectStore
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class RecoveryUploadCleanupService(
    private val batchRepository: RecoveryBatchRepository,
    private val cleanupRepository: RecoveryUploadCleanupRepository,
    private val objectStore: SourceDocumentObjectStore,
    private val settings: RecoveryStagingSettings,
) {
    @Scheduled(
        fixedDelayString = "\${paper-trail.recovery.staging.cleanup-interval-ms}",
        initialDelayString = "\${paper-trail.recovery.staging.cleanup-interval-ms}",
    )
    fun cleanExpiredRecoveryUploads() {
        val now = Instant.now()
        batchRepository.expireDueBatches(now, settings.cleanupBatchSize)
        val retryIntervalSeconds = settings.cleanupIntervalMs / 1_000
        cleanupRepository.claimDue(now, retryIntervalSeconds, settings.cleanupBatchSize).forEach { cleanup ->
            try {
                objectStore.delete(cleanup.objectKey)
                cleanupRepository.markDeleted(cleanup.id, Instant.now(), retryIntervalSeconds, cleanup.retryUntil)
            } catch (exception: Exception) {
                cleanupRepository.markFailure(cleanup.id, Instant.now(), retryIntervalSeconds, exception.javaClass.simpleName, cleanup.retryUntil)
                logger.atWarn()
                    .addKeyValue("cleanupId", cleanup.id)
                    .addKeyValue("errorType", exception.javaClass.simpleName)
                    .log("Recovery upload object cleanup failed; durable retry remains scheduled")
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RecoveryUploadCleanupService::class.java)
    }
}
