package com.papertrail.api.scholarly.references.attempts.repository

import com.papertrail.api.analysis.configuration.ProviderChainLimitsSnapshot
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptMetrics
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptNoSendReason
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptOutcomeCode
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptReservation
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptStartResult
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptState
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionProviderLookupOutcomeCode
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionProviderLookupSource
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionProviderLookupSummary
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionNextActionWindow
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptSummary
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainAuthorization
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainClaimResult
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainOutcome
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class ReferenceResolutionAttemptRepository(
    private val jdbc: JdbcTemplate,
) {
    fun lockBibliographyEntry(analysisRunId: UUID, bibliographyEntryId: UUID): Boolean = jdbc.query(
        "SELECT id FROM bibliography_entries WHERE analysis_run_id = ? AND id = ? FOR UPDATE",
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        analysisRunId,
        bibliographyEntryId,
    ).isNotEmpty()

    fun liveOwnerLeaseExpiry(analysisRunId: UUID, bibliographyEntryId: UUID): Instant? = jdbc.query(
        """
        SELECT lease_expires_at
          FROM reference_resolution_entry_chain_states
         WHERE analysis_run_id = ? AND bibliography_entry_id = ?
           AND terminal_outcome IS NULL AND lease_expires_at > clock_timestamp()
        """.trimIndent(),
        { rs, _ -> rs.getTimestamp("lease_expires_at").toInstant() },
        analysisRunId,
        bibliographyEntryId,
    ).firstOrNull()

    fun queueFailureDeferUntil(analysisRunId: UUID, bibliographyEntryId: UUID): Instant? {
        val state = chainState(analysisRunId, bibliographyEntryId, forUpdate = true) ?: return null
        if (state.terminalOutcome != null) return null
        if (state.leaseLive) return state.leaseExpiresAt

        if (!hasRecoverableAttempt(analysisRunId, bibliographyEntryId)) return null
        return jdbc.queryForObject(
            "SELECT clock_timestamp() + INTERVAL '100 milliseconds'",
            Timestamp::class.java,
        )?.toInstant()
    }

    fun terminalizeExpiredQueueFailure(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        outcome: ReferenceResolutionChainOutcome,
    ): Boolean {
        val state = chainState(analysisRunId, bibliographyEntryId, forUpdate = true)
            ?: return terminalizeUnclaimedChainQueueFailure(analysisRunId, bibliographyEntryId, outcome)
        if (state.terminalOutcome != null) return true
        if (state.leaseLive || hasRecoverableAttempt(analysisRunId, bibliographyEntryId)) return false

        return jdbc.update(
            """
            UPDATE reference_resolution_entry_chain_states
               SET terminal_outcome = ?, metadata_origin_provider_id = NULL
             WHERE analysis_run_id = ? AND bibliography_entry_id = ?
               AND owner_token = ? AND owner_fence = ? AND terminal_outcome IS NULL
               AND lease_expires_at <= clock_timestamp()
               AND NOT EXISTS (
                   SELECT 1 FROM reference_resolution_provider_attempts attempt
                    WHERE attempt.analysis_run_id = ? AND attempt.bibliography_entry_id = ?
                      AND attempt.state IN ('RESERVED', 'IN_FLIGHT')
               )
            """.trimIndent(),
            outcome.name,
            analysisRunId,
            bibliographyEntryId,
            state.ownerToken,
            state.ownerFence,
            analysisRunId,
            bibliographyEntryId,
        ) == 1
    }

    private fun terminalizeUnclaimedChainQueueFailure(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        outcome: ReferenceResolutionChainOutcome,
    ): Boolean {
        val isChainConfigured = jdbc.queryForObject(
            "SELECT configuration_snapshot #>> '{referenceResolution,providerChain,chainId}' IS NOT NULL FROM analysis_runs WHERE id = ?",
            Boolean::class.java,
            analysisRunId,
        ) == true
        if (!isChainConfigured) return true

        return jdbc.update(
            """
            INSERT INTO reference_resolution_entry_chain_states (
                analysis_run_id, bibliography_entry_id, owner_token, owner_fence, lease_expires_at,
                terminal_outcome, metadata_origin_provider_id
            ) VALUES (?, ?, ?, 1, clock_timestamp(), ?, NULL)
            ON CONFLICT (analysis_run_id, bibliography_entry_id) DO NOTHING
            """.trimIndent(),
            analysisRunId,
            bibliographyEntryId,
            UUID.randomUUID(),
            outcome.name,
        ) == 1
    }

    private fun hasRecoverableAttempt(analysisRunId: UUID, bibliographyEntryId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM reference_resolution_provider_attempts WHERE analysis_run_id = ? AND bibliography_entry_id = ? AND state IN ('RESERVED', 'IN_FLIGHT'))",
        Boolean::class.java,
        analysisRunId,
        bibliographyEntryId,
    ) == true

    fun hasFinalResolution(analysisRunId: UUID, bibliographyEntryId: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM bibliography_entry_resolutions WHERE analysis_run_id = ? AND bibliography_entry_id = ?)",
        Boolean::class.java,
        analysisRunId,
        bibliographyEntryId,
    ) == true

    fun claim(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        ownerToken: UUID,
        leaseMillis: Long,
    ): ReferenceResolutionChainClaimResult {
        if (!lockBibliographyEntry(analysisRunId, bibliographyEntryId)) {
            return ReferenceResolutionChainClaimResult.MissingEntry
        }

        var state = chainState(analysisRunId, bibliographyEntryId, forUpdate = true)
        if (state?.terminalOutcome != null) return state.alreadyFinalized()
        if (hasFinalResolution(analysisRunId, bibliographyEntryId)) {
            return ReferenceResolutionChainClaimResult.AlreadyFinalized(
                state?.terminalOutcome,
                state?.metadataOriginProviderId,
            )
        }
        if (state == null) {
            state = insertChainState(analysisRunId, bibliographyEntryId, ownerToken, leaseMillis)
            return state.claimed()
        }

        if (state.leaseLive) {
            if (state.ownerToken != ownerToken) return ReferenceResolutionChainClaimResult.Deferred(state.leaseExpiresAt)
            return state.claimed(recoveredReservation(state.authorization()))
        }

        val oldAuthorization = state.authorization()
        val uncertainAttempt = attemptForOwner(oldAuthorization, ReferenceResolutionAttemptState.IN_FLIGHT)
            ?: attemptForOwner(oldAuthorization, ReferenceResolutionAttemptState.UNKNOWN)
        val reserved = if (uncertainAttempt == null) {
            attemptForOwner(oldAuthorization, ReferenceResolutionAttemptState.RESERVED)
        } else {
            null
        }
        val newState = transferOwnership(state, ownerToken, leaseMillis)
        if (uncertainAttempt != null) {
            val staleReservation = uncertainAttempt.toReservation(
                analysisRunId,
                bibliographyEntryId,
                state.leaseExpiresAt,
            )
            return newState.claimed(uncertainAttempt = staleReservation)
        }
        if (reserved == null) return newState.claimed()

        val transferred = jdbc.update(
            """
            UPDATE reference_resolution_provider_attempts
               SET owner_token = ?, owner_fence = ?
             WHERE attempt_id = ? AND analysis_run_id = ? AND bibliography_entry_id = ?
               AND owner_token = ? AND owner_fence = ? AND state = 'RESERVED'
               AND EXISTS (
                   SELECT 1 FROM reference_resolution_entry_chain_states current_owner
                    WHERE current_owner.analysis_run_id = ? AND current_owner.bibliography_entry_id = ?
                      AND current_owner.owner_token = ? AND current_owner.owner_fence = ?
                      AND current_owner.lease_expires_at > clock_timestamp()
                      AND current_owner.terminal_outcome IS NULL
               )
            """.trimIndent(),
            ownerToken,
            newState.ownerFence,
            reserved.attemptId,
            analysisRunId,
            bibliographyEntryId,
            state.ownerToken,
            state.ownerFence,
            analysisRunId,
            bibliographyEntryId,
            ownerToken,
            newState.ownerFence,
        )
        check(transferred == 1) { "Expired reservation could not be transferred to its new owner fence." }
        val transferredReservation = attemptById(reserved.attemptId)?.toReservation(
            analysisRunId,
            bibliographyEntryId,
            newState.leaseExpiresAt,
        )
            ?: error("Transferred reservation disappeared while its entry lock was held.")
        return newState.claimed(transferredReservation)
    }

    fun renew(authorization: ReferenceResolutionChainAuthorization, leaseMillis: Long): ReferenceResolutionChainAuthorization? {
        if (!lockBibliographyEntry(authorization.analysisRunId, authorization.bibliographyEntryId)) return null
        if (hasFinalResolution(authorization.analysisRunId, authorization.bibliographyEntryId)) return null
        return jdbc.query(
            """
            UPDATE reference_resolution_entry_chain_states
               SET lease_expires_at = clock_timestamp() + (? * INTERVAL '1 millisecond')
             WHERE analysis_run_id = ? AND bibliography_entry_id = ?
               AND owner_token = ? AND owner_fence = ? AND terminal_outcome IS NULL
               AND lease_expires_at > clock_timestamp()
            RETURNING owner_token, owner_fence, lease_expires_at, terminal_outcome,
                      metadata_origin_provider_id, lease_expires_at > clock_timestamp() AS lease_live
            """.trimIndent(),
            { rs, _ -> rs.toChainState(authorization.analysisRunId, authorization.bibliographyEntryId).authorization() },
            leaseMillis,
            authorization.analysisRunId,
            authorization.bibliographyEntryId,
            authorization.ownerToken,
            authorization.ownerFence,
        ).firstOrNull()
    }

    fun finalizeChain(
        authorization: ReferenceResolutionChainAuthorization,
        outcome: ReferenceResolutionChainOutcome,
        metadataOriginProviderId: String?,
    ): Boolean {
        require(metadataOriginProviderId == null || metadataOriginProviderId in ALLOWED_PROVIDER_IDS) {
            "Metadata origin must be a fixed provider identifier."
        }
        if (!lockBibliographyEntry(authorization.analysisRunId, authorization.bibliographyEntryId)) return false
        if (hasFinalResolution(authorization.analysisRunId, authorization.bibliographyEntryId)) return false
        return jdbc.update(
            """
            UPDATE reference_resolution_entry_chain_states
               SET terminal_outcome = ?, metadata_origin_provider_id = ?
             WHERE analysis_run_id = ? AND bibliography_entry_id = ?
               AND owner_token = ? AND owner_fence = ? AND terminal_outcome IS NULL
               AND lease_expires_at > clock_timestamp()
               AND NOT EXISTS (
                   SELECT 1 FROM reference_resolution_provider_attempts pending_attempt
                    WHERE pending_attempt.analysis_run_id = reference_resolution_entry_chain_states.analysis_run_id
                      AND pending_attempt.bibliography_entry_id = reference_resolution_entry_chain_states.bibliography_entry_id
                      AND pending_attempt.state IN ('RESERVED', 'IN_FLIGHT')
               )
            """.trimIndent(),
            outcome.name,
            metadataOriginProviderId,
            authorization.analysisRunId,
            authorization.bibliographyEntryId,
            authorization.ownerToken,
            authorization.ownerFence,
        ) == 1
    }

    fun authorizationNoSendReason(authorization: ReferenceResolutionChainAuthorization): ReferenceResolutionAttemptNoSendReason? =
        when (authorizationFailure(authorization)) {
            null -> null
            AuthorizationFailure.OWNERSHIP_LOST -> ReferenceResolutionAttemptNoSendReason.OWNERSHIP_LOST
            AuthorizationFailure.LEASE_EXPIRED -> ReferenceResolutionAttemptNoSendReason.LEASE_EXPIRED
            AuthorizationFailure.FINALIZED -> ReferenceResolutionAttemptNoSendReason.FINAL_RESOLUTION
        }

    fun databaseNow(): Instant = requireNotNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp::class.java))
        .toInstant()

    fun attempts(analysisRunId: UUID, bibliographyEntryId: UUID): List<ReferenceResolutionAttemptSummary> = jdbc.query(
        """
        SELECT attempt_id, provider_id, lookup_ordinal, reservation_ordinal, provider_attempt_ordinal,
               owner_token, owner_fence, state, outcome_code, http_status, duration_millis, response_bytes,
               reserved_at, started_at, completed_at, unknown_at, next_attempt_not_before, deadline_at
          FROM reference_resolution_provider_attempts
         WHERE analysis_run_id = ? AND bibliography_entry_id = ?
         ORDER BY reservation_ordinal
        """.trimIndent(),
        { rs, _ -> rs.toAttemptSummary() },
        analysisRunId,
        bibliographyEntryId,
    )

    fun lookupResults(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
    ): List<ReferenceResolutionProviderLookupSummary> = jdbc.query(
        """
        SELECT analysis_run_id, bibliography_entry_id, lookup_ordinal, provider_id, source, outcome_code, recorded_at
          FROM reference_resolution_provider_lookup_results
         WHERE analysis_run_id = ? AND bibliography_entry_id = ?
         ORDER BY lookup_ordinal
        """.trimIndent(),
        { rs, _ -> rs.toProviderLookupSummary() },
        analysisRunId,
        bibliographyEntryId,
    )

    fun mayReadCachedCrossrefLookup(
        authorization: ReferenceResolutionChainAuthorization,
    ): Boolean {
        if (!lockBibliographyEntry(authorization.analysisRunId, authorization.bibliographyEntryId)) return false
        if (hasFinalResolution(authorization.analysisRunId, authorization.bibliographyEntryId)) return false
        if (authorizationFailure(authorization) != null) return false
        val crossrefLookup = lookupResults(authorization.analysisRunId, authorization.bibliographyEntryId)
            .singleOrNull { it.lookupOrdinal == 1 }
        if (crossrefLookup != null &&
            (crossrefLookup.providerId != "crossref" || crossrefLookup.source != ReferenceResolutionProviderLookupSource.CACHE)
        ) return false
        return attempts(authorization.analysisRunId, authorization.bibliographyEntryId).isEmpty()
    }

    fun recordCachedLookup(
        authorization: ReferenceResolutionChainAuthorization,
        providerId: String,
        lookupOrdinal: Int,
        outcomeCode: ReferenceResolutionProviderLookupOutcomeCode,
    ): Boolean {
        require(providerId == "crossref" && lookupOrdinal == 1) {
            "Only Crossref lookup 1 may use the chain cache."
        }
        require(outcomeCode in CACHED_LOOKUP_OUTCOMES) {
            "Only authoritative Crossref FOUND or NOT_FOUND results may be cached."
        }
        if (!lockBibliographyEntry(authorization.analysisRunId, authorization.bibliographyEntryId)) return false
        if (hasFinalResolution(authorization.analysisRunId, authorization.bibliographyEntryId)) return false
        if (authorizationFailure(authorization) != null) return false

        val existing = lookupResults(authorization.analysisRunId, authorization.bibliographyEntryId)
            .singleOrNull { it.lookupOrdinal == lookupOrdinal }
        if (existing != null) {
            return existing.providerId == providerId &&
                existing.source == ReferenceResolutionProviderLookupSource.CACHE &&
                existing.outcomeCode == outcomeCode
        }
        if (attempts(authorization.analysisRunId, authorization.bibliographyEntryId).any { it.lookupOrdinal == lookupOrdinal }) {
            return false
        }

        return jdbc.update(
            """
            INSERT INTO reference_resolution_provider_lookup_results (
                analysis_run_id, bibliography_entry_id, lookup_ordinal, provider_id, source, outcome_code
            )
            SELECT ?, ?, ?, ?, 'CACHE', ?
             WHERE EXISTS (
                 SELECT 1 FROM reference_resolution_entry_chain_states current_owner
                  WHERE current_owner.analysis_run_id = ? AND current_owner.bibliography_entry_id = ?
                    AND current_owner.owner_token = ? AND current_owner.owner_fence = ?
                    AND current_owner.lease_expires_at > clock_timestamp()
                    AND current_owner.terminal_outcome IS NULL
             )
               AND NOT EXISTS (
                   SELECT 1 FROM reference_resolution_provider_attempts existing_attempt
                    WHERE existing_attempt.analysis_run_id = ? AND existing_attempt.bibliography_entry_id = ?
                      AND existing_attempt.lookup_ordinal = ?
               )
            ON CONFLICT (analysis_run_id, bibliography_entry_id, lookup_ordinal) DO NOTHING
            """.trimIndent(),
            authorization.analysisRunId,
            authorization.bibliographyEntryId,
            lookupOrdinal,
            providerId,
            outcomeCode.name,
            authorization.analysisRunId,
            authorization.bibliographyEntryId,
            authorization.ownerToken,
            authorization.ownerFence,
            authorization.analysisRunId,
            authorization.bibliographyEntryId,
            lookupOrdinal,
        ) == 1
    }

    fun completeAttemptAndRecordLookup(
        reservation: ReferenceResolutionAttemptReservation,
        outcomeCode: ReferenceResolutionProviderLookupOutcomeCode,
        metrics: ReferenceResolutionAttemptMetrics,
    ): Boolean {
        if (outcomeCode == ReferenceResolutionProviderLookupOutcomeCode.RETRYABLE_FAILURE &&
            reservation.providerAttemptOrdinal < 2
        ) return false
        if (!lockBibliographyEntry(reservation.analysisRunId, reservation.bibliographyEntryId)) return false
        if (hasFinalResolution(reservation.analysisRunId, reservation.bibliographyEntryId)) return false
        if (authorizationFailure(reservation.authorization()) != null) return false
        if (lookupResults(reservation.analysisRunId, reservation.bibliographyEntryId)
                .any { it.lookupOrdinal == reservation.lookupOrdinal }
        ) return false

        val completed = updateAttemptWithAuthorization(
            reservation,
            """
            UPDATE reference_resolution_provider_attempts
               SET state = 'COMPLETED', outcome_code = ?, http_status = ?, duration_millis = ?,
                   response_bytes = ?, completed_at = clock_timestamp()
             WHERE attempt_id = ? AND analysis_run_id = ? AND bibliography_entry_id = ?
               AND provider_id = ? AND lookup_ordinal = ? AND reservation_ordinal = ?
               AND provider_attempt_ordinal = ? AND state = 'IN_FLIGHT'
            """.trimIndent(),
            outcomeCode.name,
            metrics.httpStatus,
            metrics.durationMillis,
            metrics.responseBytes,
        )
        if (completed != 1) return false
        check(insertWireLookupResult(reservation, outcomeCode)) {
            "Completed provider attempt could not commit with its logical lookup outcome."
        }
        return true
    }

    fun insertReservation(
        authorization: ReferenceResolutionChainAuthorization,
        attemptId: UUID,
        reservationOrdinal: Int,
        lookupOrdinal: Int,
        providerId: String,
        providerAttemptOrdinal: Int,
        limits: ProviderChainLimitsSnapshot,
    ): ReferenceResolutionAttemptSummary? = jdbc.query(
        """
        WITH reservation_clock AS (
            SELECT clock_timestamp() AS reserved_at
        ), reservation_deadline AS (
            SELECT reserved_at,
                   COALESCE(
                       (
                           SELECT MIN(deadline_at)
                             FROM reference_resolution_provider_attempts
                            WHERE analysis_run_id = ? AND bibliography_entry_id = ?
                       ),
                       reserved_at + (? * INTERVAL '1 millisecond')
                   ) AS deadline_at
              FROM reservation_clock
        )
        INSERT INTO reference_resolution_provider_attempts (
            attempt_id, analysis_run_id, bibliography_entry_id, reservation_ordinal,
            lookup_ordinal, provider_id, provider_attempt_ordinal, owner_token, owner_fence,
            state, reserved_at, deadline_at
        )
        SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RESERVED', reserved_at, deadline_at
          FROM reservation_deadline
         WHERE clock_timestamp() < deadline_at
           AND EXISTS (
               SELECT 1 FROM reference_resolution_entry_chain_states current_owner
                WHERE current_owner.analysis_run_id = ? AND current_owner.bibliography_entry_id = ?
                  AND current_owner.owner_token = ? AND current_owner.owner_fence = ?
                  AND current_owner.lease_expires_at > clock_timestamp()
                  AND current_owner.terminal_outcome IS NULL
           )
        RETURNING attempt_id, provider_id, lookup_ordinal, reservation_ordinal, provider_attempt_ordinal,
                  owner_token, owner_fence, state, outcome_code, http_status, duration_millis, response_bytes,
                  reserved_at, started_at, completed_at, unknown_at, next_attempt_not_before, deadline_at
        """.trimIndent(),
        { rs, _ -> rs.toAttemptSummary() },
        authorization.analysisRunId,
        authorization.bibliographyEntryId,
        limits.deadlineMillis,
        attemptId,
        authorization.analysisRunId,
        authorization.bibliographyEntryId,
        reservationOrdinal,
        lookupOrdinal,
        providerId,
        providerAttemptOrdinal,
        authorization.ownerToken,
        authorization.ownerFence,
        authorization.analysisRunId,
        authorization.bibliographyEntryId,
        authorization.ownerToken,
        authorization.ownerFence,
    ).firstOrNull()

    fun startAttempt(
        reservation: ReferenceResolutionAttemptReservation,
        requiredRequestWindowMillis: Long = 0,
    ): ReferenceResolutionAttemptStartResult {
        require(requiredRequestWindowMillis >= 0) { "Required provider request window cannot be negative." }
        val authorization = reservation.authorization()
        if (!lockBibliographyEntry(reservation.analysisRunId, reservation.bibliographyEntryId)) {
            return ReferenceResolutionAttemptStartResult.NoSend(ReferenceResolutionAttemptNoSendReason.MISSING_ENTRY)
        }
        when (authorizationFailure(authorization)) {
            AuthorizationFailure.OWNERSHIP_LOST ->
                return ReferenceResolutionAttemptStartResult.NoSend(ReferenceResolutionAttemptNoSendReason.OWNERSHIP_LOST)
            AuthorizationFailure.LEASE_EXPIRED ->
                return ReferenceResolutionAttemptStartResult.NoSend(ReferenceResolutionAttemptNoSendReason.LEASE_EXPIRED)
            AuthorizationFailure.FINALIZED ->
                return ReferenceResolutionAttemptStartResult.NoSend(ReferenceResolutionAttemptNoSendReason.FINAL_RESOLUTION)
            null -> Unit
        }

        val started = updateAttemptWithAuthorization(
            reservation,
            """
            UPDATE reference_resolution_provider_attempts
               SET state = 'IN_FLIGHT', started_at = clock_timestamp()
             WHERE deadline_at > clock_timestamp() + (? * INTERVAL '1 millisecond')
               AND attempt_id = ? AND analysis_run_id = ? AND bibliography_entry_id = ?
               AND provider_id = ? AND lookup_ordinal = ? AND reservation_ordinal = ?
               AND provider_attempt_ordinal = ? AND state = 'RESERVED'
            """.trimIndent(),
            requiredRequestWindowMillis,
        ) == 1
        if (started) return ReferenceResolutionAttemptStartResult.Started

        val persisted = jdbc.query(
            """
            SELECT state,
                   deadline_at <= clock_timestamp() AS deadline_expired,
                   deadline_at <= clock_timestamp() + (? * INTERVAL '1 millisecond') AS request_window_exhausted
              FROM reference_resolution_provider_attempts
             WHERE attempt_id = ? AND analysis_run_id = ? AND bibliography_entry_id = ?
            """.trimIndent(),
            { rs, _ -> Triple(rs.getString("state"), rs.getBoolean("deadline_expired"), rs.getBoolean("request_window_exhausted")) },
            requiredRequestWindowMillis,
            reservation.attemptId,
            reservation.analysisRunId,
            reservation.bibliographyEntryId,
        ).firstOrNull() ?: return ReferenceResolutionAttemptStartResult.NoSend(ReferenceResolutionAttemptNoSendReason.MISSING_ENTRY)

        if (persisted.first == ReferenceResolutionAttemptState.RESERVED.name && persisted.third) {
            return ReferenceResolutionAttemptStartResult.NoSend(ReferenceResolutionAttemptNoSendReason.DEADLINE_EXHAUSTED)
        }
        return ReferenceResolutionAttemptStartResult.NoSend(ReferenceResolutionAttemptNoSendReason.EXISTING_ATTEMPT)
    }

    fun completeAttempt(
        reservation: ReferenceResolutionAttemptReservation,
        outcomeCode: ReferenceResolutionAttemptOutcomeCode,
        metrics: ReferenceResolutionAttemptMetrics,
        nextAttemptNotBefore: Instant?,
    ): Boolean {
        require(outcomeCode == ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE) {
            "Only an intermediate retryable response may complete without a logical lookup result."
        }
        if (!lockBibliographyEntry(reservation.analysisRunId, reservation.bibliographyEntryId)) return false
        if (authorizationFailure(reservation.authorization()) != null) return false
        val completed = updateAttemptWithAuthorization(
            reservation,
            """
            UPDATE reference_resolution_provider_attempts
               SET state = 'COMPLETED', outcome_code = ?, http_status = ?, duration_millis = ?,
                   response_bytes = ?, completed_at = clock_timestamp(), next_attempt_not_before = ?
             WHERE attempt_id = ? AND analysis_run_id = ? AND bibliography_entry_id = ?
               AND provider_id = ? AND lookup_ordinal = ? AND reservation_ordinal = ?
               AND provider_attempt_ordinal = ? AND state = 'IN_FLIGHT'
            """.trimIndent(),
            outcomeCode.name,
            metrics.httpStatus,
            metrics.durationMillis,
            metrics.responseBytes,
            nextAttemptNotBefore?.let(Timestamp::from),
        ) == 1
        if (!completed) return false
        if (nextAttemptNotBefore == null) return true

        val releasedForRetry = jdbc.update(
            """
            UPDATE reference_resolution_entry_chain_states
               SET lease_expires_at = LEAST(lease_expires_at, ?)
             WHERE analysis_run_id = ? AND bibliography_entry_id = ?
               AND owner_token = ? AND owner_fence = ? AND terminal_outcome IS NULL
               AND lease_expires_at > clock_timestamp()
            """.trimIndent(),
            Timestamp.from(nextAttemptNotBefore),
            reservation.analysisRunId,
            reservation.bibliographyEntryId,
            reservation.ownerToken,
            reservation.ownerFence,
        )
        check(releasedForRetry == 1) { "Persisted retry schedule could not release the current owner through its due time." }
        return true
    }

    fun completeAttemptAndFinalize(
        reservation: ReferenceResolutionAttemptReservation,
        attemptOutcomeCode: ReferenceResolutionAttemptOutcomeCode,
        chainOutcome: ReferenceResolutionChainOutcome,
        metadataOriginProviderId: String?,
        metrics: ReferenceResolutionAttemptMetrics,
        nextActionWindow: ReferenceResolutionNextActionWindow?,
    ): Boolean {
        require(metadataOriginProviderId == null || metadataOriginProviderId in ALLOWED_PROVIDER_IDS) {
            "Metadata origin must be a fixed provider identifier."
        }
        if (!lockBibliographyEntry(reservation.analysisRunId, reservation.bibliographyEntryId)) return false
        if (hasFinalResolution(reservation.analysisRunId, reservation.bibliographyEntryId)) return false
        if (authorizationFailure(reservation.authorization()) != null) return false
        if (attemptOutcomeCode == ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE &&
            !retryableTerminalOutcomeIsAllowed(reservation, chainOutcome, nextActionWindow)
        ) return false

        val completed = if (attemptOutcomeCode == ReferenceResolutionAttemptOutcomeCode.NOT_SENT) {
            require(metadataOriginProviderId == null && nextActionWindow == null) {
                "A provider attempt that was not sent cannot have provider output or a retry window."
            }
            updateAttemptWithAuthorization(
                reservation,
                """
                UPDATE reference_resolution_provider_attempts
                   SET state = 'COMPLETED', outcome_code = 'NOT_SENT', http_status = NULL,
                       duration_millis = ?, response_bytes = ?, completed_at = COALESCE(completed_at, clock_timestamp())
                 WHERE attempt_id = ? AND analysis_run_id = ? AND bibliography_entry_id = ?
                   AND provider_id = ? AND lookup_ordinal = ? AND reservation_ordinal = ?
                   AND provider_attempt_ordinal = ?
                   AND (state = 'RESERVED' OR (state = 'COMPLETED' AND outcome_code = 'NOT_SENT'))
                """.trimIndent(),
                metrics.durationMillis,
                metrics.responseBytes,
            )
        } else {
            updateAttemptWithAuthorization(
                reservation,
                """
                UPDATE reference_resolution_provider_attempts
                   SET state = 'COMPLETED', outcome_code = ?, http_status = ?, duration_millis = ?,
                       response_bytes = ?, completed_at = clock_timestamp()
                 WHERE attempt_id = ? AND analysis_run_id = ? AND bibliography_entry_id = ?
                   AND provider_id = ? AND lookup_ordinal = ? AND reservation_ordinal = ?
                   AND provider_attempt_ordinal = ? AND state = 'IN_FLIGHT'
                """.trimIndent(),
                attemptOutcomeCode.name,
                metrics.httpStatus,
                metrics.durationMillis,
                metrics.responseBytes,
            )
        }
        if (completed != 1) return false
        if (attemptOutcomeCode != ReferenceResolutionAttemptOutcomeCode.NOT_SENT) {
            val lookupOutcome = ReferenceResolutionProviderLookupOutcomeCode.valueOf(attemptOutcomeCode.name)
            check(insertWireLookupResult(reservation, lookupOutcome)) {
                "Terminal provider attempt could not commit with its logical lookup outcome."
            }
        }

        val finalized = jdbc.update(
            """
            UPDATE reference_resolution_entry_chain_states
               SET terminal_outcome = ?, metadata_origin_provider_id = ?
             WHERE analysis_run_id = ? AND bibliography_entry_id = ?
               AND owner_token = ? AND owner_fence = ? AND terminal_outcome IS NULL
               AND lease_expires_at > clock_timestamp()
               AND NOT EXISTS (
                   SELECT 1 FROM reference_resolution_provider_attempts pending_attempt
                    WHERE pending_attempt.analysis_run_id = reference_resolution_entry_chain_states.analysis_run_id
                      AND pending_attempt.bibliography_entry_id = reference_resolution_entry_chain_states.bibliography_entry_id
                      AND pending_attempt.state IN ('RESERVED', 'IN_FLIGHT')
               )
            """.trimIndent(),
            chainOutcome.name,
            metadataOriginProviderId,
            reservation.analysisRunId,
            reservation.bibliographyEntryId,
            reservation.ownerToken,
            reservation.ownerFence,
        )
        check(finalized == 1) { "Current terminal wire result could not commit with its logical chain outcome." }
        return true
    }

    fun markUnknown(
        reservation: ReferenceResolutionAttemptReservation,
        metrics: ReferenceResolutionAttemptMetrics,
    ): Boolean {
        if (!lockBibliographyEntry(reservation.analysisRunId, reservation.bibliographyEntryId)) return false
        if (authorizationFailure(reservation.authorization()) != null) return false
        if (updateAttemptWithAuthorization(
            reservation,
            """
            UPDATE reference_resolution_provider_attempts
               SET state = 'UNKNOWN', outcome_code = 'UNKNOWN', http_status = ?, duration_millis = ?,
                   response_bytes = ?, unknown_at = clock_timestamp()
             WHERE attempt_id = ? AND analysis_run_id = ? AND bibliography_entry_id = ?
               AND provider_id = ? AND lookup_ordinal = ? AND reservation_ordinal = ?
               AND provider_attempt_ordinal = ? AND state = 'IN_FLIGHT'
            """.trimIndent(),
            metrics.httpStatus,
            metrics.durationMillis,
            metrics.responseBytes,
        ) == 0) return false

        val terminalized = jdbc.update(
            """
            UPDATE reference_resolution_entry_chain_states
               SET terminal_outcome = 'UNKNOWN', metadata_origin_provider_id = NULL
             WHERE analysis_run_id = ? AND bibliography_entry_id = ?
               AND owner_token = ? AND owner_fence = ? AND terminal_outcome IS NULL
               AND lease_expires_at > clock_timestamp()
            """.trimIndent(),
            reservation.analysisRunId,
            reservation.bibliographyEntryId,
            reservation.ownerToken,
            reservation.ownerFence,
        )
        check(terminalized == 1) { "Current owner could not terminalize its unknown attempt." }
        return true
    }

    fun finalizeAbandonedAttempt(
        authorization: ReferenceResolutionChainAuthorization,
        reservation: ReferenceResolutionAttemptReservation,
    ): Boolean {
        require(authorization.analysisRunId == reservation.analysisRunId &&
            authorization.bibliographyEntryId == reservation.bibliographyEntryId &&
            authorization.ownerFence > reservation.ownerFence
        ) { "An abandoned attempt can only be finalized by a newer owner fence for the same bibliography entry." }
        if (!lockBibliographyEntry(authorization.analysisRunId, authorization.bibliographyEntryId)) return false
        if (hasFinalResolution(authorization.analysisRunId, authorization.bibliographyEntryId)) return false
        if (authorizationFailure(authorization) != null) return false

        val markedUnknown = jdbc.update(
            """
            UPDATE reference_resolution_provider_attempts
               SET state = 'UNKNOWN', outcome_code = 'UNKNOWN', unknown_at = COALESCE(unknown_at, clock_timestamp())
             WHERE attempt_id = ? AND analysis_run_id = ? AND bibliography_entry_id = ?
               AND owner_token = ? AND owner_fence = ? AND state IN ('IN_FLIGHT', 'UNKNOWN')
            """.trimIndent(),
            reservation.attemptId,
            reservation.analysisRunId,
            reservation.bibliographyEntryId,
            reservation.ownerToken,
            reservation.ownerFence,
        )
        if (markedUnknown != 1) return false

        val finalized = jdbc.update(
            """
            UPDATE reference_resolution_entry_chain_states
               SET terminal_outcome = 'UNKNOWN', metadata_origin_provider_id = NULL
             WHERE analysis_run_id = ? AND bibliography_entry_id = ?
               AND owner_token = ? AND owner_fence = ? AND terminal_outcome IS NULL
               AND lease_expires_at > clock_timestamp()
            """.trimIndent(),
            authorization.analysisRunId,
            authorization.bibliographyEntryId,
            authorization.ownerToken,
            authorization.ownerFence,
        )
        check(finalized == 1) { "Current owner could not terminalize an abandoned provider attempt." }
        return true
    }

    private fun updateAttemptWithAuthorization(
        reservation: ReferenceResolutionAttemptReservation,
        sql: String,
        vararg leadingParameters: Any?,
    ): Int = jdbc.update(
        """
        $sql
           AND owner_token = ? AND owner_fence = ?
           AND EXISTS (
               SELECT 1 FROM reference_resolution_entry_chain_states current_owner
                WHERE current_owner.analysis_run_id = ? AND current_owner.bibliography_entry_id = ?
                  AND current_owner.owner_token = ? AND current_owner.owner_fence = ?
                  AND current_owner.lease_expires_at > clock_timestamp()
                  AND current_owner.terminal_outcome IS NULL
           )
        """.trimIndent(),
        *leadingParameters,
        reservation.attemptId,
        reservation.analysisRunId,
        reservation.bibliographyEntryId,
        reservation.providerId,
        reservation.lookupOrdinal,
        reservation.reservationOrdinal,
        reservation.providerAttemptOrdinal,
        reservation.ownerToken,
        reservation.ownerFence,
        reservation.analysisRunId,
        reservation.bibliographyEntryId,
        reservation.ownerToken,
        reservation.ownerFence,
    )

    private fun authorizationFailure(authorization: ReferenceResolutionChainAuthorization): AuthorizationFailure? {
        val state = chainState(authorization.analysisRunId, authorization.bibliographyEntryId, forUpdate = true)
            ?: return AuthorizationFailure.OWNERSHIP_LOST
        if (state.ownerToken != authorization.ownerToken || state.ownerFence != authorization.ownerFence) {
            return AuthorizationFailure.OWNERSHIP_LOST
        }
        if (state.terminalOutcome != null) return AuthorizationFailure.FINALIZED
        if (!state.leaseLive) return AuthorizationFailure.LEASE_EXPIRED
        return null
    }

    private fun chainState(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        forUpdate: Boolean,
    ): ChainState? = jdbc.query(
        """
        SELECT owner_token, owner_fence, lease_expires_at, terminal_outcome,
               metadata_origin_provider_id, lease_expires_at > clock_timestamp() AS lease_live
          FROM reference_resolution_entry_chain_states
         WHERE analysis_run_id = ? AND bibliography_entry_id = ?
         ${if (forUpdate) "FOR UPDATE" else ""}
        """.trimIndent(),
        { rs, _ -> rs.toChainState(analysisRunId, bibliographyEntryId) },
        analysisRunId,
        bibliographyEntryId,
    ).firstOrNull()

    private fun insertChainState(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        ownerToken: UUID,
        leaseMillis: Long,
    ): ChainState = jdbc.query(
        """
        INSERT INTO reference_resolution_entry_chain_states (
            analysis_run_id, bibliography_entry_id, owner_token, owner_fence, lease_expires_at
        ) VALUES (?, ?, ?, 1, clock_timestamp() + (? * INTERVAL '1 millisecond'))
        RETURNING owner_token, owner_fence, lease_expires_at, terminal_outcome,
                  metadata_origin_provider_id, lease_expires_at > clock_timestamp() AS lease_live
        """.trimIndent(),
        { rs, _ -> rs.toChainState(analysisRunId, bibliographyEntryId) },
        analysisRunId,
        bibliographyEntryId,
        ownerToken,
        leaseMillis,
    ).single()

    private fun transferOwnership(state: ChainState, ownerToken: UUID, leaseMillis: Long): ChainState = jdbc.query(
        """
        UPDATE reference_resolution_entry_chain_states
           SET owner_token = ?, owner_fence = owner_fence + 1,
               lease_expires_at = clock_timestamp() + (? * INTERVAL '1 millisecond')
         WHERE analysis_run_id = ? AND bibliography_entry_id = ?
           AND owner_token = ? AND owner_fence = ? AND terminal_outcome IS NULL
           AND lease_expires_at <= clock_timestamp()
        RETURNING owner_token, owner_fence, lease_expires_at, terminal_outcome,
                  metadata_origin_provider_id, lease_expires_at > clock_timestamp() AS lease_live
        """.trimIndent(),
        { rs, _ -> rs.toChainState(state.analysisRunId, state.bibliographyEntryId) },
        ownerToken,
        leaseMillis,
        state.analysisRunId,
        state.bibliographyEntryId,
        state.ownerToken,
        state.ownerFence,
    ).single()

    private fun attemptForOwner(
        authorization: ReferenceResolutionChainAuthorization,
        state: ReferenceResolutionAttemptState,
    ): ReferenceResolutionAttemptSummary? = jdbc.query(
        """
        SELECT attempt_id, provider_id, lookup_ordinal, reservation_ordinal, provider_attempt_ordinal,
               owner_token, owner_fence, state, outcome_code, http_status, duration_millis, response_bytes,
               reserved_at, started_at, completed_at, unknown_at, next_attempt_not_before, deadline_at
          FROM reference_resolution_provider_attempts
         WHERE analysis_run_id = ? AND bibliography_entry_id = ?
           AND owner_token = ? AND owner_fence = ? AND state = ?
         ORDER BY reservation_ordinal DESC
         LIMIT 1
        """.trimIndent(),
        { rs, _ -> rs.toAttemptSummary() },
        authorization.analysisRunId,
        authorization.bibliographyEntryId,
        authorization.ownerToken,
        authorization.ownerFence,
        state.name,
    ).firstOrNull()

    private fun recoveredReservation(
        authorization: ReferenceResolutionChainAuthorization,
    ): ReferenceResolutionAttemptReservation? = attemptForOwner(authorization, ReferenceResolutionAttemptState.RESERVED)
        ?.toReservation(authorization.analysisRunId, authorization.bibliographyEntryId, authorization.leaseExpiresAt)

    private fun retryableTerminalOutcomeIsAllowed(
        reservation: ReferenceResolutionAttemptReservation,
        chainOutcome: ReferenceResolutionChainOutcome,
        nextActionWindow: ReferenceResolutionNextActionWindow?,
    ): Boolean {
        val persistedAttempts = attempts(reservation.analysisRunId, reservation.bibliographyEntryId)
        val currentAttempt = persistedAttempts.singleOrNull { it.attemptId == reservation.attemptId }
        if (currentAttempt?.state != ReferenceResolutionAttemptState.IN_FLIGHT) return false

        if (chainOutcome == ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE) {
            return reservation.providerId == "datacite" &&
                reservation.providerAttemptOrdinal == 2 &&
                persistedAttempts.any {
                    it.providerId == "datacite" &&
                        it.providerAttemptOrdinal == 1 &&
                        it.state == ReferenceResolutionAttemptState.COMPLETED &&
                        it.outcomeCode == ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE
                }
        }
        if (chainOutcome != ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED) return false
        if (reservation.providerId == "datacite" && reservation.providerAttemptOrdinal == 2) return false

        val limits = ProviderChainLimitsSnapshot.EXACT_DOI_V1
        if (persistedAttempts.size >= limits.maxWireAttemptsPerReference) return true
        val retriesUsed = persistedAttempts.count { it.providerAttemptOrdinal > 1 }
        val lookups = lookupResults(reservation.analysisRunId, reservation.bibliographyEntryId)
        val usedLookups = (persistedAttempts.map { it.lookupOrdinal } + lookups.map { it.lookupOrdinal }).distinct().size
        val nextActionBlockedByBudget = when {
            reservation.providerId == "crossref" && reservation.providerAttemptOrdinal == 1 ->
                retriesUsed >= limits.maxRetriesPerReference
            reservation.providerId == "crossref" && reservation.providerAttemptOrdinal == 2 ->
                usedLookups >= limits.maxLogicalLookupsPerReference ||
                    persistedAttempts.any { it.lookupOrdinal == 2 }
            reservation.providerId == "datacite" && reservation.providerAttemptOrdinal == 1 ->
                retriesUsed >= limits.maxRetriesPerReference
            else -> false
        }
        if (nextActionBlockedByBudget) return true

        val deadline = persistedAttempts.minOfOrNull { it.deadlineAt } ?: return false
        if (jdbc.queryForObject(
                "SELECT ?::timestamptz <= clock_timestamp()",
                Boolean::class.java,
                Timestamp.from(deadline),
            ) == true
        ) return true
        val window = nextActionWindow ?: return false
        return jdbc.queryForObject(
            """
            SELECT EXTRACT(EPOCH FROM (?::timestamptz - clock_timestamp())) * 1000
                   < (?::numeric + ?::numeric)
            """.trimIndent(),
            Boolean::class.java,
            Timestamp.from(deadline),
            window.waitMillis,
            window.requestBudgetMillis,
        ) == true
    }

    private fun insertWireLookupResult(
        reservation: ReferenceResolutionAttemptReservation,
        outcomeCode: ReferenceResolutionProviderLookupOutcomeCode,
    ): Boolean = jdbc.update(
        """
        INSERT INTO reference_resolution_provider_lookup_results (
            analysis_run_id, bibliography_entry_id, lookup_ordinal, provider_id, source, outcome_code
        )
        SELECT ?, ?, ?, ?, 'WIRE', ?
         WHERE EXISTS (
             SELECT 1 FROM reference_resolution_provider_attempts completed_attempt
              WHERE completed_attempt.attempt_id = ?
                AND completed_attempt.analysis_run_id = ?
                AND completed_attempt.bibliography_entry_id = ?
                AND completed_attempt.lookup_ordinal = ?
                AND completed_attempt.provider_id = ?
                AND completed_attempt.owner_token = ? AND completed_attempt.owner_fence = ?
                AND completed_attempt.state = 'COMPLETED'
                AND completed_attempt.outcome_code = ?
         )
           AND EXISTS (
               SELECT 1 FROM reference_resolution_entry_chain_states current_owner
                WHERE current_owner.analysis_run_id = ? AND current_owner.bibliography_entry_id = ?
                  AND current_owner.owner_token = ? AND current_owner.owner_fence = ?
                  AND current_owner.lease_expires_at > clock_timestamp()
                  AND current_owner.terminal_outcome IS NULL
           )
        ON CONFLICT (analysis_run_id, bibliography_entry_id, lookup_ordinal) DO NOTHING
        """.trimIndent(),
        reservation.analysisRunId,
        reservation.bibliographyEntryId,
        reservation.lookupOrdinal,
        reservation.providerId,
        outcomeCode.name,
        reservation.attemptId,
        reservation.analysisRunId,
        reservation.bibliographyEntryId,
        reservation.lookupOrdinal,
        reservation.providerId,
        reservation.ownerToken,
        reservation.ownerFence,
        outcomeCode.name,
        reservation.analysisRunId,
        reservation.bibliographyEntryId,
        reservation.ownerToken,
        reservation.ownerFence,
    ) == 1

    private fun attemptById(attemptId: UUID): ReferenceResolutionAttemptSummary? = jdbc.query(
        """
        SELECT attempt_id, provider_id, lookup_ordinal, reservation_ordinal, provider_attempt_ordinal,
               owner_token, owner_fence, state, outcome_code, http_status, duration_millis, response_bytes,
               reserved_at, started_at, completed_at, unknown_at, next_attempt_not_before, deadline_at
          FROM reference_resolution_provider_attempts
         WHERE attempt_id = ?
        """.trimIndent(),
        { rs, _ -> rs.toAttemptSummary() },
        attemptId,
    ).firstOrNull()

    private fun ResultSet.toChainState(analysisRunId: UUID, bibliographyEntryId: UUID): ChainState = ChainState(
        analysisRunId = analysisRunId,
        bibliographyEntryId = bibliographyEntryId,
        ownerToken = getObject("owner_token", UUID::class.java),
        ownerFence = getLong("owner_fence"),
        leaseExpiresAt = requireNotNull(getTimestamp("lease_expires_at")).toInstant(),
        terminalOutcome = getString("terminal_outcome")?.let(ReferenceResolutionChainOutcome::valueOf),
        metadataOriginProviderId = getString("metadata_origin_provider_id"),
        leaseLive = getBoolean("lease_live"),
    )

    private fun ResultSet.toProviderLookupSummary(): ReferenceResolutionProviderLookupSummary = ReferenceResolutionProviderLookupSummary(
        analysisRunId = getObject("analysis_run_id", UUID::class.java),
        bibliographyEntryId = getObject("bibliography_entry_id", UUID::class.java),
        lookupOrdinal = getInt("lookup_ordinal"),
        providerId = getString("provider_id"),
        source = ReferenceResolutionProviderLookupSource.valueOf(getString("source")),
        outcomeCode = ReferenceResolutionProviderLookupOutcomeCode.valueOf(getString("outcome_code")),
        recordedAt = requireNotNull(getTimestamp("recorded_at")).toInstant(),
    )

    private fun ResultSet.toAttemptSummary(): ReferenceResolutionAttemptSummary = ReferenceResolutionAttemptSummary(
        attemptId = getObject("attempt_id", UUID::class.java),
        providerId = getString("provider_id"),
        lookupOrdinal = getInt("lookup_ordinal"),
        reservationOrdinal = getInt("reservation_ordinal"),
        providerAttemptOrdinal = getInt("provider_attempt_ordinal"),
        ownerToken = getObject("owner_token", UUID::class.java),
        ownerFence = getLong("owner_fence"),
        state = ReferenceResolutionAttemptState.valueOf(getString("state")),
        outcomeCode = getString("outcome_code")?.let(ReferenceResolutionAttemptOutcomeCode::valueOf),
        httpStatus = nullableInt("http_status"),
        durationMillis = nullableLong("duration_millis"),
        responseBytes = nullableLong("response_bytes"),
        reservedAt = requireNotNull(getTimestamp("reserved_at")).toInstant(),
        startedAt = getTimestamp("started_at")?.toInstant(),
        completedAt = getTimestamp("completed_at")?.toInstant(),
        unknownAt = getTimestamp("unknown_at")?.toInstant(),
        deadlineAt = requireNotNull(getTimestamp("deadline_at")).toInstant(),
        nextAttemptNotBefore = getTimestamp("next_attempt_not_before")?.toInstant(),
    )

    private fun ResultSet.nullableInt(column: String): Int? = getInt(column).let { value ->
        if (wasNull()) null else value
    }

    private fun ResultSet.nullableLong(column: String): Long? = getLong(column).let { value ->
        if (wasNull()) null else value
    }

    private fun ReferenceResolutionAttemptSummary.toReservation(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        leaseExpiresAt: Instant,
    ): ReferenceResolutionAttemptReservation = ReferenceResolutionAttemptReservation(
        attemptId = attemptId,
        analysisRunId = analysisRunId,
        bibliographyEntryId = bibliographyEntryId,
        providerId = providerId,
        lookupOrdinal = lookupOrdinal,
        reservationOrdinal = reservationOrdinal,
        providerAttemptOrdinal = providerAttemptOrdinal,
        ownerToken = ownerToken,
        ownerFence = ownerFence,
        leaseExpiresAt = leaseExpiresAt,
        reservedAt = reservedAt,
        deadlineAt = deadlineAt,
    )

    private fun ReferenceResolutionAttemptReservation.authorization(): ReferenceResolutionChainAuthorization =
        ReferenceResolutionChainAuthorization(
            analysisRunId = analysisRunId,
            bibliographyEntryId = bibliographyEntryId,
            ownerToken = ownerToken,
            ownerFence = ownerFence,
            leaseExpiresAt = leaseExpiresAt,
        )

    private fun ChainState.claimed(
        reservation: ReferenceResolutionAttemptReservation? = null,
        uncertainAttempt: ReferenceResolutionAttemptReservation? = null,
    ): ReferenceResolutionChainClaimResult.Claimed = ReferenceResolutionChainClaimResult.Claimed(
        authorization = authorization(),
        recoveredReservation = reservation ?: recoveredReservation(authorization()),
        uncertainAttempt = uncertainAttempt,
    )

    private data class ChainState(
        val analysisRunId: UUID,
        val bibliographyEntryId: UUID,
        val ownerToken: UUID,
        val ownerFence: Long,
        val leaseExpiresAt: Instant,
        val terminalOutcome: ReferenceResolutionChainOutcome?,
        val metadataOriginProviderId: String?,
        val leaseLive: Boolean,
    ) {
        fun authorization(): ReferenceResolutionChainAuthorization = ReferenceResolutionChainAuthorization(
            analysisRunId = analysisRunId,
            bibliographyEntryId = bibliographyEntryId,
            ownerToken = ownerToken,
            ownerFence = ownerFence,
            leaseExpiresAt = leaseExpiresAt,
        )

        fun alreadyFinalized(): ReferenceResolutionChainClaimResult.AlreadyFinalized =
            ReferenceResolutionChainClaimResult.AlreadyFinalized(terminalOutcome, metadataOriginProviderId)
    }

    private enum class AuthorizationFailure {
        OWNERSHIP_LOST,
        LEASE_EXPIRED,
        FINALIZED,
    }

    companion object {
        private val ALLOWED_PROVIDER_IDS = setOf("crossref", "datacite")
        private val CACHED_LOOKUP_OUTCOMES = setOf(
            ReferenceResolutionProviderLookupOutcomeCode.FOUND,
            ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND,
        )
    }
}
