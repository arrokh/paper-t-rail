package com.papertrail.api.scholarly.references.attempts.service

import com.papertrail.api.analysis.configuration.ProviderChainLimitsSnapshot
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptMetrics
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptNoSendReason
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptOutcomeCode
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptReservation
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptResult
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptState
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionProviderLookupOutcomeCode
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionProviderLookupSummary
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionNextActionWindow
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptStartResult
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptSummary
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainAuthorization
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainOutcome
import com.papertrail.api.scholarly.references.attempts.repository.ReferenceResolutionAttemptRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

@Service
class ReferenceResolutionAttemptService(
    private val repository: ReferenceResolutionAttemptRepository,
    transactionManager: PlatformTransactionManager,
    private val terminalCommitGuard: ReferenceResolutionTerminalCommitGuard,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    fun reserveAttempt(
        authorization: ReferenceResolutionChainAuthorization,
        providerId: String,
        lookupNumber: Int,
        limits: ProviderChainLimitsSnapshot,
    ): ReferenceResolutionAttemptResult {
        ensureNoAmbientTransaction()
        require(limits == ProviderChainLimitsSnapshot.EXACT_DOI_V1) {
            "Reference-resolution attempt limits must match the approved provider-chain budget."
        }
        require(
            (providerId == CROSSREF_PROVIDER_ID && lookupNumber == 1) ||
                (providerId == DATACITE_PROVIDER_ID && lookupNumber == 2),
        ) { "Provider and lookup ordinal do not match the fixed reference-resolution order." }

        return requireNotNull(transactionTemplate.execute {
            if (!repository.lockBibliographyEntry(authorization.analysisRunId, authorization.bibliographyEntryId)) {
                return@execute ReferenceResolutionAttemptResult.NoSend(ReferenceResolutionAttemptNoSendReason.MISSING_ENTRY)
            }
            if (repository.hasFinalResolution(authorization.analysisRunId, authorization.bibliographyEntryId)) {
                return@execute ReferenceResolutionAttemptResult.NoSend(ReferenceResolutionAttemptNoSendReason.FINAL_RESOLUTION)
            }

            val attempts = repository.attempts(authorization.analysisRunId, authorization.bibliographyEntryId)
            val logicalLookups = repository.lookupResults(authorization.analysisRunId, authorization.bibliographyEntryId)
            if (logicalLookups.any { it.lookupOrdinal == lookupNumber }) {
                return@execute ReferenceResolutionAttemptResult.NoSend(ReferenceResolutionAttemptNoSendReason.LOOKUP_ALREADY_RECORDED)
            }
            if (attempts.any { it.state == ReferenceResolutionAttemptState.UNKNOWN }) {
                return@execute ReferenceResolutionAttemptResult.NoSend(ReferenceResolutionAttemptNoSendReason.UNKNOWN_OUTCOME)
            }
            repository.authorizationNoSendReason(authorization)?.let { reason ->
                return@execute ReferenceResolutionAttemptResult.NoSend(reason)
            }
            if (attempts.any { it.state == ReferenceResolutionAttemptState.IN_FLIGHT }) {
                return@execute ReferenceResolutionAttemptResult.NoSend(ReferenceResolutionAttemptNoSendReason.EXISTING_ATTEMPT)
            }

            val deadline = attempts.firstOrNull()?.deadlineAt
            if (deadline != null && !repository.databaseNow().isBefore(deadline)) {
                val expiredReservation = attempts.lastOrNull { it.state == ReferenceResolutionAttemptState.RESERVED }
                    ?.toReservation(authorization)
                return@execute ReferenceResolutionAttemptResult.NoSend(
                    ReferenceResolutionAttemptNoSendReason.DEADLINE_EXHAUSTED,
                    expiredReservation,
                )
            }
            if (attempts.any { it.state == ReferenceResolutionAttemptState.RESERVED }) {
                return@execute ReferenceResolutionAttemptResult.NoSend(ReferenceResolutionAttemptNoSendReason.EXISTING_RESERVATION)
            }
            if (attempts.size >= limits.maxWireAttemptsPerReference) {
                return@execute ReferenceResolutionAttemptResult.NoSend(ReferenceResolutionAttemptNoSendReason.BUDGET_EXHAUSTED)
            }

            val providerAttempts = attempts.filter { it.providerId == providerId }
            if (providerAttempts.size >= MAX_ATTEMPTS_PER_PROVIDER) {
                return@execute ReferenceResolutionAttemptResult.NoSend(ReferenceResolutionAttemptNoSendReason.BUDGET_EXHAUSTED)
            }
            if (providerAttempts.isEmpty()) {
                val orderReady = if (providerId == CROSSREF_PROVIDER_ID) {
                    attempts.isEmpty()
                } else {
                    isDataCiteFallbackReady(logicalLookups, attempts)
                }
                if (!orderReady) {
                    return@execute ReferenceResolutionAttemptResult.NoSend(ReferenceResolutionAttemptNoSendReason.LOOKUP_ORDER_NOT_READY)
                }
            } else {
                val previousAttempt = providerAttempts.last()
                if (previousAttempt.state != ReferenceResolutionAttemptState.COMPLETED ||
                    previousAttempt.outcomeCode != ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE
                ) {
                    return@execute ReferenceResolutionAttemptResult.NoSend(ReferenceResolutionAttemptNoSendReason.EXISTING_ATTEMPT)
                }
                if (previousAttempt.nextAttemptNotBefore?.isAfter(repository.databaseNow()) == true) {
                    return@execute ReferenceResolutionAttemptResult.NoSend(ReferenceResolutionAttemptNoSendReason.RETRY_NOT_DUE)
                }
                if (previousAttempt.providerAttemptOrdinal != 1 ||
                    attempts.count { it.providerAttemptOrdinal > 1 } >= limits.maxRetriesPerReference
                ) {
                    return@execute ReferenceResolutionAttemptResult.NoSend(ReferenceResolutionAttemptNoSendReason.BUDGET_EXHAUSTED)
                }
                if (providerId == DATACITE_PROVIDER_ID && !isDataCiteFallbackReady(logicalLookups, attempts)) {
                    return@execute ReferenceResolutionAttemptResult.NoSend(ReferenceResolutionAttemptNoSendReason.LOOKUP_ORDER_NOT_READY)
                }
            }

            val usedLookups = (attempts.map { it.lookupOrdinal } + logicalLookups.map { it.lookupOrdinal }).distinct().size
            if (lookupNumber !in attempts.map { it.lookupOrdinal } && usedLookups >= limits.maxLogicalLookupsPerReference) {
                return@execute ReferenceResolutionAttemptResult.NoSend(ReferenceResolutionAttemptNoSendReason.BUDGET_EXHAUSTED)
            }

            val attempt = repository.insertReservation(
                authorization = authorization,
                attemptId = UUID.randomUUID(),
                reservationOrdinal = attempts.size + 1,
                lookupOrdinal = lookupNumber,
                providerId = providerId,
                providerAttemptOrdinal = providerAttempts.size + 1,
                limits = limits,
            ) ?: return@execute ReferenceResolutionAttemptResult.NoSend(
                repository.authorizationNoSendReason(authorization)
                    ?: ReferenceResolutionAttemptNoSendReason.DEADLINE_EXHAUSTED,
            )

            ReferenceResolutionAttemptResult.Reserved(attempt.toReservation(authorization))
        })
    }

    fun startAttempt(
        reservation: ReferenceResolutionAttemptReservation,
        requiredRequestWindowMillis: Long = 0,
    ): ReferenceResolutionAttemptStartResult {
        ensureNoAmbientTransaction()
        require(requiredRequestWindowMillis >= 0)
        return requireNotNull(transactionTemplate.execute {
            terminalCommitGuard.lockActiveRun(reservation.analysisRunId)
            repository.startAttempt(reservation, requiredRequestWindowMillis)
        })
    }

    fun completeAttempt(
        reservation: ReferenceResolutionAttemptReservation,
        outcomeCode: ReferenceResolutionAttemptOutcomeCode,
        metrics: ReferenceResolutionAttemptMetrics = ReferenceResolutionAttemptMetrics(),
        nextAttemptNotBefore: Instant? = null,
    ): Boolean {
        ensureNoAmbientTransaction()
        require(outcomeCode == ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE) {
            "Only an intermediate retryable response may complete without a logical lookup result."
        }
        return requireNotNull(transactionTemplate.execute {
            repository.completeAttempt(reservation, outcomeCode, metrics, nextAttemptNotBefore)
        })
    }

    /**
     * Checks the live database owner fence before a caller reads shared Crossref cache data.
     * The caller must separately verify matching provider consent before the cache read.
     */
    fun mayReadCachedCrossrefLookup(authorization: ReferenceResolutionChainAuthorization): Boolean {
        ensureNoAmbientTransaction()
        return requireNotNull(transactionTemplate.execute {
            repository.mayReadCachedCrossrefLookup(authorization)
        })
    }

    fun recordCachedLookup(
        authorization: ReferenceResolutionChainAuthorization,
        providerId: String,
        lookupOrdinal: Int,
        outcomeCode: ReferenceResolutionProviderLookupOutcomeCode,
    ): Boolean {
        ensureNoAmbientTransaction()
        require(providerId == CROSSREF_PROVIDER_ID && lookupOrdinal == 1) {
            "Only the approved Crossref lookup may use the shared chain cache."
        }
        require(outcomeCode in CACHED_CROSSREF_OUTCOMES) {
            "Only authoritative Crossref success or not-found results may be cached."
        }
        return requireNotNull(transactionTemplate.execute {
            repository.recordCachedLookup(authorization, providerId, lookupOrdinal, outcomeCode)
        })
    }

    fun completeAttemptAndRecordLookup(
        reservation: ReferenceResolutionAttemptReservation,
        outcomeCode: ReferenceResolutionProviderLookupOutcomeCode,
        metrics: ReferenceResolutionAttemptMetrics = ReferenceResolutionAttemptMetrics(),
    ): Boolean {
        ensureNoAmbientTransaction()
        if (outcomeCode == ReferenceResolutionProviderLookupOutcomeCode.RETRYABLE_FAILURE &&
            reservation.providerAttemptOrdinal < MAX_ATTEMPTS_PER_PROVIDER
        ) return false
        return requireNotNull(transactionTemplate.execute {
            repository.completeAttemptAndRecordLookup(reservation, outcomeCode, metrics)
        })
    }

    fun lookupResults(runId: UUID, entryId: UUID): List<ReferenceResolutionProviderLookupSummary> =
        repository.lookupResults(runId, entryId)

    fun completeAttemptAndFinalize(
        reservation: ReferenceResolutionAttemptReservation,
        attemptOutcomeCode: ReferenceResolutionAttemptOutcomeCode,
        chainOutcome: ReferenceResolutionChainOutcome,
        metadataOriginProviderId: String?,
        metrics: ReferenceResolutionAttemptMetrics = ReferenceResolutionAttemptMetrics(),
        nextActionWindow: ReferenceResolutionNextActionWindow? = null,
    ): Boolean {
        ensureNoAmbientTransaction()
        require(
            attemptOutcomeCode != ReferenceResolutionAttemptOutcomeCode.UNKNOWN &&
                attemptOutcomeCode != ReferenceResolutionAttemptOutcomeCode.NOT_SENT,
        ) { "Terminal wire completion must represent a completed provider response." }
        if (attemptOutcomeCode == ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE) {
            if (chainOutcome !in setOf(
                    ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED,
                    ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE,
                )
            ) return false
            if (chainOutcome == ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE &&
                (reservation.providerId != DATACITE_PROVIDER_ID ||
                    reservation.providerAttemptOrdinal != MAX_ATTEMPTS_PER_PROVIDER)
            ) return false
        }
        return requireNotNull(transactionTemplate.execute {
            repository.completeAttemptAndFinalize(
                reservation,
                attemptOutcomeCode,
                chainOutcome,
                metadataOriginProviderId,
                metrics,
                nextActionWindow,
            )
        })
    }

    fun completeAttemptAndFinalizeWithResult(
        reservation: ReferenceResolutionAttemptReservation,
        attemptOutcomeCode: ReferenceResolutionAttemptOutcomeCode,
        chainOutcome: ReferenceResolutionChainOutcome,
        metadataOriginProviderId: String?,
        persistTerminalResult: () -> Unit,
        metrics: ReferenceResolutionAttemptMetrics = ReferenceResolutionAttemptMetrics(),
        nextActionWindow: ReferenceResolutionNextActionWindow? = null,
    ): Boolean {
        ensureNoAmbientTransaction()
        require(attemptOutcomeCode != ReferenceResolutionAttemptOutcomeCode.UNKNOWN) {
            "Unknown provider outcomes must use their dedicated terminal persistence operation."
        }
        if (attemptOutcomeCode == ReferenceResolutionAttemptOutcomeCode.NOT_SENT) {
            require(metadataOriginProviderId == null) { "A provider attempt that was not sent cannot have a metadata origin." }
        }
        return requireNotNull(transactionTemplate.execute {
            terminalCommitGuard.lockActiveRun(reservation.analysisRunId)
            if (!isAllowedTerminalRetry(reservation, attemptOutcomeCode, chainOutcome, nextActionWindow)) return@execute false
            val completed = repository.completeAttemptAndFinalize(
                reservation,
                attemptOutcomeCode,
                chainOutcome,
                metadataOriginProviderId,
                metrics,
                nextActionWindow,
            )
            if (!completed) return@execute false
            persistTerminalResult()
            true
        })
    }

    fun finalizeChainWithResult(
        authorization: ReferenceResolutionChainAuthorization,
        chainOutcome: ReferenceResolutionChainOutcome,
        metadataOriginProviderId: String?,
        persistTerminalResult: () -> Unit,
    ): Boolean {
        ensureNoAmbientTransaction()
        return requireNotNull(transactionTemplate.execute {
            terminalCommitGuard.lockActiveRun(authorization.analysisRunId)
            if (!repository.finalizeChain(authorization, chainOutcome, metadataOriginProviderId)) return@execute false
            persistTerminalResult()
            true
        })
    }

    fun markUnknownAndFinalizeWithResult(
        reservation: ReferenceResolutionAttemptReservation,
        persistTerminalResult: () -> Unit,
        metrics: ReferenceResolutionAttemptMetrics = ReferenceResolutionAttemptMetrics(),
    ): Boolean {
        ensureNoAmbientTransaction()
        return requireNotNull(transactionTemplate.execute {
            terminalCommitGuard.lockActiveRun(reservation.analysisRunId)
            if (!repository.markUnknown(reservation, metrics)) return@execute false
            persistTerminalResult()
            true
        })
    }

    fun finalizeAbandonedAttemptWithResult(
        authorization: ReferenceResolutionChainAuthorization,
        reservation: ReferenceResolutionAttemptReservation,
        persistTerminalResult: () -> Unit,
    ): Boolean {
        ensureNoAmbientTransaction()
        return requireNotNull(transactionTemplate.execute {
            terminalCommitGuard.lockActiveRun(authorization.analysisRunId)
            if (!repository.finalizeAbandonedAttempt(authorization, reservation)) return@execute false
            persistTerminalResult()
            true
        })
    }

    fun databaseNow() = repository.databaseNow()

    private fun isAllowedTerminalRetry(
        reservation: ReferenceResolutionAttemptReservation,
        attemptOutcomeCode: ReferenceResolutionAttemptOutcomeCode,
        chainOutcome: ReferenceResolutionChainOutcome,
        nextActionWindow: ReferenceResolutionNextActionWindow?,
    ): Boolean {
        if (attemptOutcomeCode != ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE) return true
        if (chainOutcome !in setOf(
                ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED,
                ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE,
            )
        ) return false
        return chainOutcome != ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE ||
            (reservation.providerId == DATACITE_PROVIDER_ID && reservation.providerAttemptOrdinal == MAX_ATTEMPTS_PER_PROVIDER)
    }

    fun markUnknown(
        reservation: ReferenceResolutionAttemptReservation,
        metrics: ReferenceResolutionAttemptMetrics = ReferenceResolutionAttemptMetrics(),
    ): Boolean {
        ensureNoAmbientTransaction()
        return requireNotNull(transactionTemplate.execute {
            repository.markUnknown(reservation, metrics)
        })
    }

    fun summary(runId: UUID, entryId: UUID): List<ReferenceResolutionAttemptSummary> =
        repository.attempts(runId, entryId)

    private fun ensureNoAmbientTransaction() {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Reference-resolution attempt writes require their own short transaction, outside provider coordination and HTTP."
        }
    }

    private fun ReferenceResolutionAttemptSummary.toReservation(
        authorization: ReferenceResolutionChainAuthorization,
    ): ReferenceResolutionAttemptReservation = ReferenceResolutionAttemptReservation(
        attemptId = attemptId,
        analysisRunId = authorization.analysisRunId,
        bibliographyEntryId = authorization.bibliographyEntryId,
        providerId = providerId,
        lookupOrdinal = lookupOrdinal,
        reservationOrdinal = reservationOrdinal,
        providerAttemptOrdinal = providerAttemptOrdinal,
        ownerToken = authorization.ownerToken,
        ownerFence = authorization.ownerFence,
        leaseExpiresAt = authorization.leaseExpiresAt,
        reservedAt = reservedAt,
        deadlineAt = deadlineAt,
    )

    private fun isDataCiteFallbackReady(
        logicalLookups: List<ReferenceResolutionProviderLookupSummary>,
        attempts: List<ReferenceResolutionAttemptSummary>,
    ): Boolean {
        val crossrefLookup = logicalLookups.singleOrNull {
            it.providerId == CROSSREF_PROVIDER_ID && it.lookupOrdinal == 1
        } ?: return false
        return when (crossrefLookup.outcomeCode) {
            ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND,
            ReferenceResolutionProviderLookupOutcomeCode.UNUSABLE -> true
            ReferenceResolutionProviderLookupOutcomeCode.PERMANENT_FAILURE -> {
                val crossrefAttempts = attempts.filter { it.providerId == CROSSREF_PROVIDER_ID }
                val finalAttempt = crossrefAttempts.lastOrNull() ?: return false
                val finalAttemptIsPermanent = finalAttempt.state == ReferenceResolutionAttemptState.COMPLETED &&
                    finalAttempt.outcomeCode == ReferenceResolutionAttemptOutcomeCode.PERMANENT_FAILURE
                when (crossrefAttempts.size) {
                    1 -> finalAttempt.providerAttemptOrdinal == 1 && finalAttemptIsPermanent
                    MAX_ATTEMPTS_PER_PROVIDER -> {
                        val firstAttempt = crossrefAttempts.first()
                        crossrefAttempts.map { it.providerAttemptOrdinal } == listOf(1, MAX_ATTEMPTS_PER_PROVIDER) &&
                            firstAttempt.state == ReferenceResolutionAttemptState.COMPLETED &&
                            firstAttempt.outcomeCode == ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE &&
                            finalAttemptIsPermanent
                    }
                    else -> false
                }
            }
            ReferenceResolutionProviderLookupOutcomeCode.RETRYABLE_FAILURE -> {
                val crossrefAttempts = attempts.filter { it.providerId == CROSSREF_PROVIDER_ID }
                val finalAttempt = crossrefAttempts.lastOrNull() ?: return false
                crossrefAttempts.size == MAX_ATTEMPTS_PER_PROVIDER &&
                    crossrefAttempts.map { it.providerAttemptOrdinal } == listOf(1, MAX_ATTEMPTS_PER_PROVIDER) &&
                    crossrefAttempts.first().state == ReferenceResolutionAttemptState.COMPLETED &&
                    crossrefAttempts.first().outcomeCode == ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE &&
                    finalAttempt.state == ReferenceResolutionAttemptState.COMPLETED &&
                    finalAttempt.outcomeCode == ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE
            }
            else -> false
        }
    }

    companion object {
        private const val CROSSREF_PROVIDER_ID = "crossref"
        private const val DATACITE_PROVIDER_ID = "datacite"
        private const val MAX_ATTEMPTS_PER_PROVIDER = 2
        private val CACHED_CROSSREF_OUTCOMES = setOf(
            ReferenceResolutionProviderLookupOutcomeCode.FOUND,
            ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND,
        )
    }
}
