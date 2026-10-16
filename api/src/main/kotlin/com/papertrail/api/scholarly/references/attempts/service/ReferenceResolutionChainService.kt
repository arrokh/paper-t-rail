package com.papertrail.api.scholarly.references.attempts.service

import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainAuthorization
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainClaimResult
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainOutcome
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionQueueFailureArbitration
import com.papertrail.api.scholarly.references.attempts.repository.ReferenceResolutionAttemptRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Service
class ReferenceResolutionChainService(
    private val repository: ReferenceResolutionAttemptRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    fun claim(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        ownerToken: UUID = UUID.randomUUID(),
    ): ReferenceResolutionChainClaimResult {
        ensureNoAmbientTransaction()
        return requireNotNull(transactionTemplate.execute {
            repository.claim(analysisRunId, bibliographyEntryId, ownerToken, OWNER_LEASE_MILLIS)
        })
    }

    fun liveOwnerLeaseExpiry(analysisRunId: UUID, bibliographyEntryId: UUID): Instant? {
        ensureNoAmbientTransaction()
        return repository.liveOwnerLeaseExpiry(analysisRunId, bibliographyEntryId)
    }

    fun arbitrateQueueFailure(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        persistFailure: () -> Unit,
        persistResolved: () -> Unit,
    ): ReferenceResolutionQueueFailureArbitration {
        ensureNoAmbientTransaction()
        return requireNotNull(transactionTemplate.execute {
            if (!repository.lockBibliographyEntry(analysisRunId, bibliographyEntryId)) {
                persistFailure()
                return@execute ReferenceResolutionQueueFailureArbitration.FailureRecorded
            }
            if (repository.hasFinalResolution(analysisRunId, bibliographyEntryId)) {
                persistResolved()
                return@execute ReferenceResolutionQueueFailureArbitration.ResolutionAlreadySaved
            }
            repository.queueFailureDeferUntil(analysisRunId, bibliographyEntryId)?.let { until ->
                return@execute ReferenceResolutionQueueFailureArbitration.Defer(until)
            }
            if (!repository.terminalizeExpiredQueueFailure(
                    analysisRunId,
                    bibliographyEntryId,
                    ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE,
                )
            ) {
                val deferUntil = requireNotNull(repository.queueFailureDeferUntil(analysisRunId, bibliographyEntryId)) {
                    "Queue-failure terminalization lost entry ownership without a recoverable lease."
                }
                return@execute ReferenceResolutionQueueFailureArbitration.Defer(deferUntil)
            }
            persistFailure()
            ReferenceResolutionQueueFailureArbitration.FailureRecorded
        })
    }

    fun renew(authorization: ReferenceResolutionChainAuthorization): ReferenceResolutionChainAuthorization? {
        ensureNoAmbientTransaction()
        return transactionTemplate.execute {
            repository.renew(authorization, OWNER_LEASE_MILLIS)
        }
    }

    fun finalizeChain(
        authorization: ReferenceResolutionChainAuthorization,
        outcome: ReferenceResolutionChainOutcome,
        metadataOriginProviderId: String?,
    ): Boolean {
        ensureNoAmbientTransaction()
        return requireNotNull(transactionTemplate.execute {
            repository.finalizeChain(authorization, outcome, metadataOriginProviderId)
        })
    }

    private fun ensureNoAmbientTransaction() {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Reference-resolution chain operations require their own short transaction."
        }
    }

    companion object {
        private const val OWNER_LEASE_MILLIS = 30_000L
    }
}
