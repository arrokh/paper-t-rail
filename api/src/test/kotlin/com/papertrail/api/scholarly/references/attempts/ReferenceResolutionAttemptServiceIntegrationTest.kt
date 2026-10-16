package com.papertrail.api.scholarly.references.attempts

import com.papertrail.api.analysis.configuration.ProviderChainLimitsSnapshot
import com.papertrail.api.document.domain.SourceDocumentDeletedException
import com.papertrail.api.document.repository.PostgresSourceDocumentEgressHandoffLock
import com.papertrail.api.document.repository.SourceDocumentDeletionRepository
import com.papertrail.api.document.repository.SourceDocumentRepository
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptNoSendReason
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptOutcomeCode
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptResult
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptStartResult
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptState
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptMetrics
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionProviderLookupOutcomeCode
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionProviderLookupSource
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionNextActionWindow
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainAuthorization
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainClaimResult
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainOutcome
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionQueueFailureArbitration
import com.papertrail.api.scholarly.references.attempts.repository.ReferenceResolutionAttemptRepository
import com.papertrail.api.scholarly.references.attempts.service.ActiveSourceDocumentReferenceResolutionTerminalCommitGuard
import com.papertrail.api.scholarly.references.attempts.service.ReferenceResolutionAttemptService
import com.papertrail.api.scholarly.references.attempts.service.ReferenceResolutionTerminalCommitGuard
import com.papertrail.api.scholarly.references.attempts.service.ReferenceResolutionChainService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import kotlin.test.assertIs
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
class ReferenceResolutionAttemptServiceIntegrationTest {
    @BeforeEach
    fun resetDatabase() {
        jdbc.update("TRUNCATE analysis_runs CASCADE")
        runId = UUID.randomUUID()
        entryId = UUID.randomUUID()
        jdbc.update("INSERT INTO analysis_runs (id) VALUES (?)", runId)
        jdbc.update("INSERT INTO parsed_document_parses (analysis_run_id) VALUES (?)", runId)
        insertEntry(runId, entryId)
        service = createService()
        chainService = createChainService()
        authorizations = mutableMapOf()
    }

    @Test
    fun `queue failure terminalizes an expired chain with no active provider attempt`() {
        val expiredOwner = authorizationFor(runId, entryId)
        expireLease(expiredOwner)
        var failureRecorded = false

        val arbitration = chainService.arbitrateQueueFailure(
            analysisRunId = runId,
            bibliographyEntryId = entryId,
            persistFailure = { failureRecorded = true },
            persistResolved = { error("No final resolution exists in this fixture.") },
        )

        assertEquals(ReferenceResolutionQueueFailureArbitration.FailureRecorded, arbitration)
        assertTrue(failureRecorded)
        val terminal = assertIs<ReferenceResolutionChainClaimResult.AlreadyFinalized>(
            chainService.claim(runId, entryId, UUID.randomUUID()),
        )
        assertEquals(ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE, terminal.outcome)
        assertNull(terminal.metadataOriginProviderId)
    }

    @Test
    fun `queue failure before first chain claim creates terminal chain state`() {
        jdbc.update(
            "UPDATE analysis_runs SET configuration_snapshot = ?::jsonb WHERE id = ?",
            """{"referenceResolution":{"providerChain":{"chainId":"crossref-datacite-exact-doi-v1"}}}""",
            runId,
        )
        var failureRecorded = false

        val arbitration = chainService.arbitrateQueueFailure(
            analysisRunId = runId,
            bibliographyEntryId = entryId,
            persistFailure = { failureRecorded = true },
            persistResolved = { error("No final resolution exists in this fixture.") },
        )

        assertEquals(ReferenceResolutionQueueFailureArbitration.FailureRecorded, arbitration)
        assertTrue(failureRecorded)
        val terminal = assertIs<ReferenceResolutionChainClaimResult.AlreadyFinalized>(
            chainService.claim(runId, entryId, UUID.randomUUID()),
        )
        assertEquals(ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE, terminal.outcome)
        assertEquals(0, attemptCount())
    }

    @Test
    fun `legacy queue failure before first chain claim leaves chain state absent`() {
        val arbitration = chainService.arbitrateQueueFailure(
            analysisRunId = runId,
            bibliographyEntryId = entryId,
            persistFailure = {},
            persistResolved = { error("No final resolution exists in this fixture.") },
        )

        assertEquals(ReferenceResolutionQueueFailureArbitration.FailureRecorded, arbitration)
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM reference_resolution_entry_chain_states WHERE analysis_run_id = ? AND bibliography_entry_id = ?",
                Int::class.java,
                runId,
                entryId,
            ),
        )
    }

    @Test
    fun `reserves once and exposes only safe attempt fields`() {
        val authorization = authorizationFor(runId, entryId)
        val reservation = reserve(runId, entryId, "crossref", 1)

        val duplicate = reserveResult(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptNoSendReason.EXISTING_RESERVATION, noSend(duplicate))
        assertEquals(1, attemptCount())

        val summary = service.summary(runId, entryId).single()
        assertEquals("crossref", summary.providerId)
        assertEquals(1, summary.lookupOrdinal)
        assertEquals(1, summary.providerAttemptOrdinal)
        assertEquals(1, summary.reservationOrdinal)
        assertEquals(authorization.ownerToken, summary.ownerToken)
        assertEquals(authorization.ownerFence, summary.ownerFence)
        assertEquals(ReferenceResolutionAttemptState.RESERVED, summary.state)
        assertNotNull(summary.reservedAt)
        assertTrue(summary.deadlineAt.isAfter(summary.reservedAt))
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(reservation))
        assertEquals(
            ReferenceResolutionAttemptStartResult.NoSend(ReferenceResolutionAttemptNoSendReason.EXISTING_ATTEMPT),
            service.startAttempt(reservation),
        )
        assertEquals(
            ReferenceResolutionAttemptNoSendReason.EXISTING_ATTEMPT,
            noSend(reserveResult(runId, entryId, "crossref", 1)),
        )
        assertEquals(ReferenceResolutionAttemptState.IN_FLIGHT, service.summary(runId, entryId).single().state)
    }

    @Test
    fun `start refuses a send without the full bounded HTTP request window`() {
        val reservation = reserve(runId, entryId, "crossref", 1)
        jdbc.update(
            "UPDATE reference_resolution_provider_attempts SET deadline_at = clock_timestamp() + INTERVAL '10 seconds' WHERE attempt_id = ?",
            reservation.attemptId,
        )

        assertEquals(
            ReferenceResolutionAttemptStartResult.NoSend(ReferenceResolutionAttemptNoSendReason.DEADLINE_EXHAUSTED),
            service.startAttempt(reservation, requiredRequestWindowMillis = 25_000),
        )
        val summary = service.summary(runId, entryId).single()
        assertEquals(ReferenceResolutionAttemptState.RESERVED, summary.state)
        assertNull(summary.outcomeCode)
        assertNull(summary.completedAt)
    }

    @Test
    fun `completion and unknown transitions are compare and set and unknown cannot resend`() {
        val completedReservation = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(completedReservation))
        val metrics = ReferenceResolutionAttemptMetrics(httpStatus = 503, durationMillis = 17, responseBytes = 23)
        assertTrue(service.completeAttempt(completedReservation, ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE, metrics))
        assertFalse(service.completeAttempt(completedReservation, ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE, metrics))
        assertFalse(service.markUnknown(completedReservation))

        val unknownEntryId = UUID.randomUUID()
        insertEntry(runId, unknownEntryId)
        val unknownReservation = reserve(runId, unknownEntryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(unknownReservation))
        assertTrue(service.markUnknown(unknownReservation))
        assertFalse(service.markUnknown(unknownReservation))
        assertEquals(ReferenceResolutionAttemptOutcomeCode.UNKNOWN, service.summary(runId, unknownEntryId).single().outcomeCode)
        assertEquals(
            ReferenceResolutionAttemptNoSendReason.UNKNOWN_OUTCOME,
            noSend(reserveResult(runId, unknownEntryId, "crossref", 1)),
        )
    }

    @Test
    fun `retry redelivery cannot start before its persisted not-before time`() {
        val firstAttempt = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(firstAttempt))
        val retryNotBefore = service.databaseNow().plusSeconds(10)

        assertTrue(
            service.completeAttempt(
                firstAttempt,
                ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE,
                nextAttemptNotBefore = retryNotBefore,
            ),
        )

        assertEquals(retryNotBefore, service.summary(runId, entryId).single().nextAttemptNotBefore)
        val newOwnerClaim = chainService.claim(runId, entryId, UUID.randomUUID())
        val deferredUntil = assertIs<ReferenceResolutionChainClaimResult.Deferred>(newOwnerClaim).until
        assertEquals(retryNotBefore, deferredUntil)
        assertTrue(deferredUntil.isBefore(firstAttempt.leaseExpiresAt))
        assertEquals(
            ReferenceResolutionAttemptNoSendReason.RETRY_NOT_DUE,
            noSend(reserveResult(runId, entryId, "crossref", 1)),
        )
    }

    @Test
    fun `terminal wire completion and logical result finalize under one fence`() {
        val reservation = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(reservation))

        assertTrue(
            service.completeAttemptAndFinalize(
                reservation = reservation,
                attemptOutcomeCode = ReferenceResolutionAttemptOutcomeCode.FOUND,
                chainOutcome = ReferenceResolutionChainOutcome.RESOLVED,
                metadataOriginProviderId = "crossref",
            ),
        )
        val attempt = service.summary(runId, entryId).single()
        assertEquals(ReferenceResolutionAttemptState.COMPLETED, attempt.state)
        assertEquals(ReferenceResolutionAttemptOutcomeCode.FOUND, attempt.outcomeCode)
        val lookup = service.lookupResults(runId, entryId).single()
        assertEquals(ReferenceResolutionProviderLookupSource.WIRE, lookup.source)
        assertEquals(ReferenceResolutionProviderLookupOutcomeCode.FOUND, lookup.outcomeCode)
        val finalized = assertIs<ReferenceResolutionChainClaimResult.AlreadyFinalized>(
            chainService.claim(runId, entryId, UUID.randomUUID()),
        )
        assertEquals(ReferenceResolutionChainOutcome.RESOLVED, finalized.outcome)
        assertEquals("crossref", finalized.metadataOriginProviderId)
    }

    @Test
    fun `terminal resolution callback shares the attempt transaction and rolls back together`() {
        createActiveDocumentForRun()
        val guardedService = createGuardedService()
        val reservation = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(reservation))
        jdbc.execute("CREATE TABLE IF NOT EXISTS chain_commit_probe (id UUID PRIMARY KEY)")
        jdbc.update("DELETE FROM chain_commit_probe")

        try {
            assertThrows(RuntimeException::class.java) {
                guardedService.completeAttemptAndFinalizeWithResult(
                    reservation = reservation,
                    attemptOutcomeCode = ReferenceResolutionAttemptOutcomeCode.FOUND,
                    chainOutcome = ReferenceResolutionChainOutcome.RESOLVED,
                    metadataOriginProviderId = "crossref",
                    persistTerminalResult = {
                        jdbc.update("INSERT INTO chain_commit_probe (id) VALUES (?)", runId)
                        throw IllegalStateException("test-only terminal commit failure")
                    },
                )
            }

            assertEquals(ReferenceResolutionAttemptState.IN_FLIGHT, service.summary(runId, entryId).single().state)
            assertTrue(service.lookupResults(runId, entryId).isEmpty())
            assertNull(
                jdbc.queryForObject(
                    "SELECT terminal_outcome FROM reference_resolution_entry_chain_states WHERE analysis_run_id = ? AND bibliography_entry_id = ?",
                    String::class.java,
                    runId,
                    entryId,
                ),
            )
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM chain_commit_probe", Int::class.java))
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS chain_commit_probe")
        }
    }

    @Test
    fun `NOT_SENT attempt and chain outcome commit atomically with terminal result`() {
        createActiveDocumentForRun()
        val guardedService = createGuardedService()
        val reservation = reserve(runId, entryId, "crossref", 1)

        assertThrows(IllegalStateException::class.java) {
            guardedService.completeAttemptAndFinalizeWithResult(
                reservation = reservation,
                attemptOutcomeCode = ReferenceResolutionAttemptOutcomeCode.NOT_SENT,
                chainOutcome = ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE,
                metadataOriginProviderId = null,
                persistTerminalResult = { error("test-only terminal commit failure") },
            )
        }
        assertEquals(ReferenceResolutionAttemptState.RESERVED, service.summary(runId, entryId).single().state)
        assertNull(
            jdbc.queryForObject(
                "SELECT terminal_outcome FROM reference_resolution_entry_chain_states WHERE analysis_run_id = ? AND bibliography_entry_id = ?",
                String::class.java,
                runId,
                entryId,
            ),
        )

        assertTrue(
            guardedService.completeAttemptAndFinalizeWithResult(
                reservation = reservation,
                attemptOutcomeCode = ReferenceResolutionAttemptOutcomeCode.NOT_SENT,
                chainOutcome = ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE,
                metadataOriginProviderId = null,
                persistTerminalResult = {},
            ),
        )
        val attempt = service.summary(runId, entryId).single()
        assertEquals(ReferenceResolutionAttemptState.COMPLETED, attempt.state)
        assertEquals(ReferenceResolutionAttemptOutcomeCode.NOT_SENT, attempt.outcomeCode)
        assertTrue(service.lookupResults(runId, entryId).isEmpty())
        assertEquals(
            ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE.name,
            jdbc.queryForObject(
                "SELECT terminal_outcome FROM reference_resolution_entry_chain_states WHERE analysis_run_id = ? AND bibliography_entry_id = ?",
                String::class.java,
                runId,
                entryId,
            ),
        )
    }

    @Test
    fun `deleted Source Document rejects terminal result persistence`() {
        val documentId = createActiveDocumentForRun()
        val guardedService = createGuardedService()
        val reservation = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(reservation))
        val deletionRepository = SourceDocumentDeletionRepository(jdbc)
        TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
            deletionRepository.markDeleted(documentId)
        }

        assertThrows(SourceDocumentDeletedException::class.java) {
            guardedService.completeAttemptAndFinalizeWithResult(
                reservation = reservation,
                attemptOutcomeCode = ReferenceResolutionAttemptOutcomeCode.FOUND,
                chainOutcome = ReferenceResolutionChainOutcome.RESOLVED,
                metadataOriginProviderId = "crossref",
                persistTerminalResult = { error("A deleted Source Document must not persist a terminal result.") },
            )
        }
        assertEquals(ReferenceResolutionAttemptState.IN_FLIGHT, service.summary(runId, entryId).single().state)
        assertTrue(service.lookupResults(runId, entryId).isEmpty())
        assertNull(
            jdbc.queryForObject(
                "SELECT terminal_outcome FROM reference_resolution_entry_chain_states WHERE analysis_run_id = ? AND bibliography_entry_id = ?",
                String::class.java,
                runId,
                entryId,
            ),
        )
    }

    @Test
    fun `deleted Source Document rejects provider attempt start after egress permit acquisition`() {
        val documentId = createActiveDocumentForRun()
        val guardedService = createGuardedService()
        val reservation = reserve(runId, entryId, "crossref", 1)
        TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
            SourceDocumentDeletionRepository(jdbc).markDeleted(documentId)
        }

        val handoffLock = PostgresSourceDocumentEgressHandoffLock(dataSource).acquireForRun(runId)
        try {
            assertThrows(SourceDocumentDeletedException::class.java) {
                guardedService.startAttempt(reservation, requiredRequestWindowMillis = 25_000L)
            }
        } finally {
            handoffLock.close()
        }
        assertEquals(ReferenceResolutionAttemptState.RESERVED, service.summary(runId, entryId).single().state)
    }

    @Test
    fun `deletion waits through the gap between provider attempt start and wire dispatch`() {
        val documentId = createActiveDocumentForRun()
        val guardedService = createGuardedService()
        val reservation = reserve(runId, entryId, "crossref", 1)
        val handoffLock = PostgresSourceDocumentEgressHandoffLock(dataSource).acquireForRun(runId)
        val deletionStarted = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertEquals(
                ReferenceResolutionAttemptStartResult.Started,
                guardedService.startAttempt(reservation, requiredRequestWindowMillis = 25_000L),
            )
            val deletion = executor.submit<SourceDocumentDeletionRepository.TombstoneResult> {
                deletionStarted.countDown()
                requireNotNull(
                    TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
                        SourceDocumentDeletionRepository(jdbc).markDeleted(documentId)
                    },
                )
            }
            assertTrue(deletionStarted.await(5, TimeUnit.SECONDS))
            assertTrue(awaitSourceDocumentAdvisoryLockWait())
            assertFalse(SourceDocumentRepository(jdbc).isDeleted(documentId))

            assertFalse(SourceDocumentRepository(jdbc).isDeleted(documentId))
            handoffLock.close()

            assertEquals(SourceDocumentDeletionRepository.TombstoneResult.MARKED, deletion.get(10, TimeUnit.SECONDS))
            assertTrue(SourceDocumentRepository(jdbc).isDeleted(documentId))
        } finally {
            handoffLock.close()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `terminal result holds Source Document lock until commit before deletion tombstone`() {
        val documentId = createActiveDocumentForRun()
        val guardedService = createGuardedService()
        val reservation = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(reservation))
        jdbc.execute("CREATE TABLE IF NOT EXISTS chain_commit_probe (id UUID PRIMARY KEY)")
        jdbc.update("DELETE FROM chain_commit_probe")

        val callbackEntered = CountDownLatch(1)
        val allowCommit = CountDownLatch(1)
        val deletionStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val terminal = executor.submit<Boolean> {
                guardedService.completeAttemptAndFinalizeWithResult(
                    reservation = reservation,
                    attemptOutcomeCode = ReferenceResolutionAttemptOutcomeCode.FOUND,
                    chainOutcome = ReferenceResolutionChainOutcome.RESOLVED,
                    metadataOriginProviderId = "crossref",
                    persistTerminalResult = {
                        callbackEntered.countDown()
                        check(allowCommit.await(5, TimeUnit.SECONDS))
                        jdbc.update("INSERT INTO chain_commit_probe (id) VALUES (?)", runId)
                    },
                )
            }
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))

            val deletion = executor.submit<SourceDocumentDeletionRepository.TombstoneResult> {
                deletionStarted.countDown()
                requireNotNull(
                    TransactionTemplate(DataSourceTransactionManager(dataSource)).execute {
                        SourceDocumentDeletionRepository(jdbc).markDeleted(documentId)
                    },
                )
            }
            assertTrue(deletionStarted.await(5, TimeUnit.SECONDS))
            val deletionWaitedOnDocumentLock = awaitSourceDocumentDeletionLockWait()
            allowCommit.countDown()

            assertTrue(deletionWaitedOnDocumentLock, "Deletion should wait while the terminal transaction holds the active-document lock.")
            assertTrue(terminal.get(10, TimeUnit.SECONDS))
            assertEquals(SourceDocumentDeletionRepository.TombstoneResult.MARKED, deletion.get(10, TimeUnit.SECONDS))
            assertEquals(ReferenceResolutionAttemptState.COMPLETED, service.summary(runId, entryId).single().state)
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM chain_commit_probe", Int::class.java))
            assertTrue(SourceDocumentRepository(jdbc).isDeleted(documentId))
        } finally {
            allowCommit.countDown()
            executor.shutdownNow()
            jdbc.execute("DROP TABLE IF EXISTS chain_commit_probe")
        }
    }

    @Test
    fun `cached Crossref not found permits DataCite without a Crossref wire attempt`() {
        val authorization = authorizationFor(runId, entryId)
        assertTrue(service.mayReadCachedCrossrefLookup(authorization))
        assertTrue(
            service.recordCachedLookup(
                authorization,
                providerId = "crossref",
                lookupOrdinal = 1,
                outcomeCode = ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND,
            ),
        )

        val lookup = service.lookupResults(runId, entryId).single()
        assertEquals(ReferenceResolutionProviderLookupSource.CACHE, lookup.source)
        assertEquals(ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND, lookup.outcomeCode)
        assertEquals(0, attemptCount())
        assertEquals(
            ReferenceResolutionAttemptNoSendReason.LOOKUP_ALREADY_RECORDED,
            noSend(reserveResult(authorization, "crossref", 1)),
        )

        val dataCite = reserveResult(authorization, "datacite", 2)
        assertEquals(1, assertIs<ReferenceResolutionAttemptResult.Reserved>(dataCite).reservation.providerAttemptOrdinal)
        assertEquals(1, attemptCount())
    }

    @Test
    fun `cached Crossref found cannot be replaced or repeated as a wire lookup`() {
        val authorization = authorizationFor(runId, entryId)
        assertTrue(service.mayReadCachedCrossrefLookup(authorization))
        assertTrue(
            service.recordCachedLookup(
                authorization,
                providerId = "crossref",
                lookupOrdinal = 1,
                outcomeCode = ReferenceResolutionProviderLookupOutcomeCode.FOUND,
            ),
        )
        assertTrue(service.mayReadCachedCrossrefLookup(authorization), "A durable cache hit must be readable on redelivery to recover its metadata.")
        assertTrue(
            service.recordCachedLookup(
                authorization,
                providerId = "crossref",
                lookupOrdinal = 1,
                outcomeCode = ReferenceResolutionProviderLookupOutcomeCode.FOUND,
            ),
        )
        assertFalse(
            service.recordCachedLookup(
                authorization,
                providerId = "crossref",
                lookupOrdinal = 1,
                outcomeCode = ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND,
            ),
        )
        assertEquals(
            ReferenceResolutionAttemptNoSendReason.LOOKUP_ALREADY_RECORDED,
            noSend(reserveResult(authorization, "crossref", 1)),
        )
        assertEquals(
            ReferenceResolutionAttemptNoSendReason.LOOKUP_ORDER_NOT_READY,
            noSend(reserveResult(authorization, "datacite", 2)),
        )
        assertEquals(0, attemptCount())
        assertEquals(ReferenceResolutionProviderLookupOutcomeCode.FOUND, service.lookupResults(runId, entryId).single().outcomeCode)
    }

    @Test
    fun `cached lookup writes require the current owner fence`() {
        val oldAuthorization = authorizationFor(runId, entryId)
        expireLease(oldAuthorization)
        val currentAuthorization = assertIs<ReferenceResolutionChainClaimResult.Claimed>(
            chainService.claim(runId, entryId, UUID.randomUUID()),
        ).authorization

        assertFalse(service.mayReadCachedCrossrefLookup(oldAuthorization))
        assertFalse(
            service.recordCachedLookup(
                oldAuthorization,
                providerId = "crossref",
                lookupOrdinal = 1,
                outcomeCode = ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND,
            ),
        )
        assertTrue(service.lookupResults(runId, entryId).isEmpty())
        assertTrue(service.mayReadCachedCrossrefLookup(currentAuthorization))
        assertTrue(
            service.recordCachedLookup(
                currentAuthorization,
                providerId = "crossref",
                lookupOrdinal = 1,
                outcomeCode = ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND,
            ),
        )
        assertEquals(0, attemptCount())
    }

    @Test
    fun `wire attempt and logical lookup roll back together when result persistence fails`() {
        val reservation = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(reservation))
        jdbc.execute(
            """
            CREATE FUNCTION reject_provider_lookup_result_for_test() RETURNS trigger
            LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'test-only lookup persistence failure'; END; $$
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE TRIGGER reject_provider_lookup_result_for_test
            BEFORE INSERT ON reference_resolution_provider_lookup_results
            FOR EACH ROW EXECUTE FUNCTION reject_provider_lookup_result_for_test()
            """.trimIndent(),
        )

        try {
            assertThrows(RuntimeException::class.java) {
                service.completeAttemptAndRecordLookup(
                    reservation,
                    ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND,
                )
            }
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_provider_lookup_result_for_test ON reference_resolution_provider_lookup_results")
            jdbc.execute("DROP FUNCTION IF EXISTS reject_provider_lookup_result_for_test()")
        }

        assertEquals(ReferenceResolutionAttemptState.IN_FLIGHT, service.summary(runId, entryId).single().state)
        assertTrue(service.lookupResults(runId, entryId).isEmpty())
    }

    @Test
    fun `retryable response cannot terminalize while a retry remains within budget and deadline`() {
        val reservation = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(reservation))

        assertFalse(
            service.completeAttemptAndFinalize(
                reservation = reservation,
                attemptOutcomeCode = ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE,
                chainOutcome = ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED,
                metadataOriginProviderId = null,
                nextActionWindow = ReferenceResolutionNextActionWindow(waitMillis = 0, requestBudgetMillis = 25_000),
            ),
        )
        assertEquals(ReferenceResolutionAttemptState.IN_FLIGHT, service.summary(runId, entryId).single().state)
        assertTrue(service.lookupResults(runId, entryId).isEmpty())
    }

    @Test
    fun `retryable response may terminalize when its next action cannot fit the persisted deadline`() {
        val reservation = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(reservation))
        jdbc.update(
            "UPDATE reference_resolution_provider_attempts SET deadline_at = clock_timestamp() + interval '10 seconds' WHERE attempt_id = ?",
            reservation.attemptId,
        )

        assertTrue(
            service.completeAttemptAndFinalize(
                reservation = reservation,
                attemptOutcomeCode = ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE,
                chainOutcome = ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED,
                metadataOriginProviderId = null,
                nextActionWindow = ReferenceResolutionNextActionWindow(waitMillis = 10_000, requestBudgetMillis = 25_000),
            ),
        )
        assertEquals(
            ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED,
            assertIs<ReferenceResolutionChainClaimResult.AlreadyFinalized>(
                chainService.claim(runId, entryId, UUID.randomUUID()),
            ).outcome,
        )
        assertEquals(ReferenceResolutionProviderLookupOutcomeCode.RETRYABLE_FAILURE, service.lookupResults(runId, entryId).single().outcomeCode)
    }

    @Test
    fun `wire lookup completion records its logical result before DataCite fallback`() {
        val reservation = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(reservation))
        assertTrue(
            service.completeAttemptAndRecordLookup(
                reservation,
                ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND,
            ),
        )

        val lookup = service.lookupResults(runId, entryId).single()
        assertEquals(ReferenceResolutionProviderLookupSource.WIRE, lookup.source)
        assertEquals(ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND, lookup.outcomeCode)
        assertEquals(ReferenceResolutionAttemptState.COMPLETED, service.summary(runId, entryId).single().state)
        val dataCite = reserve(runId, entryId, "datacite", 2)
        assertEquals(2, dataCite.lookupOrdinal)
        assertEquals(2, attemptCount())
    }

    @Test
    fun `permanent Crossref DOI failure authorizes one DataCite fallback without retry`() {
        val crossref = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(crossref))
        assertTrue(
            service.completeAttemptAndRecordLookup(
                crossref,
                ReferenceResolutionProviderLookupOutcomeCode.PERMANENT_FAILURE,
            ),
        )
        assertEquals(1, attemptCount())

        val dataCite = reserve(runId, entryId, "datacite", 2)

        assertEquals("datacite", dataCite.providerId)
        assertEquals(2, dataCite.lookupOrdinal)
        assertEquals(1, dataCite.providerAttemptOrdinal)
        assertEquals(2, attemptCount())
        assertEquals(
            ReferenceResolutionAttemptNoSendReason.LOOKUP_ALREADY_RECORDED,
            noSend(reserveResult(runId, entryId, "crossref", 1)),
        )
    }

    @Test
    fun `permanent Crossref failure after one retry allows one DataCite fallback without retrying Crossref`() {
        val crossrefFirst = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(crossrefFirst))
        assertFalse(
            service.completeAttemptAndRecordLookup(
                crossrefFirst,
                ReferenceResolutionProviderLookupOutcomeCode.RETRYABLE_FAILURE,
            ),
        )
        assertTrue(service.completeAttempt(crossrefFirst, ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE))

        val crossrefRetry = reserve(runId, entryId, "crossref", 1)
        assertEquals(2, crossrefRetry.providerAttemptOrdinal)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(crossrefRetry))
        assertTrue(
            service.completeAttemptAndRecordLookup(
                crossrefRetry,
                ReferenceResolutionProviderLookupOutcomeCode.PERMANENT_FAILURE,
            ),
        )
        val crossrefHistory = service.summary(runId, entryId)
        assertEquals(listOf(1, 2), crossrefHistory.map { it.providerAttemptOrdinal })
        assertEquals(
            listOf(
                ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE,
                ReferenceResolutionAttemptOutcomeCode.PERMANENT_FAILURE,
            ),
            crossrefHistory.map { it.outcomeCode },
        )
        assertTrue(crossrefHistory.all { it.state == ReferenceResolutionAttemptState.COMPLETED })

        val dataCite = reserve(runId, entryId, "datacite", 2)

        assertEquals(1, dataCite.providerAttemptOrdinal)
        assertEquals(3, attemptCount())
        assertEquals(
            ReferenceResolutionAttemptNoSendReason.LOOKUP_ALREADY_RECORDED,
            noSend(reserveResult(runId, entryId, "crossref", 1)),
        )
    }

    @Test
    fun `one crossref retry is shared with the ordered datacite fallback`() {
        val crossrefFirst = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(crossrefFirst))
        assertFalse(
            service.completeAttemptAndRecordLookup(
                crossrefFirst,
                ReferenceResolutionProviderLookupOutcomeCode.RETRYABLE_FAILURE,
            ),
        )
        assertTrue(service.lookupResults(runId, entryId).isEmpty())
        assertTrue(service.completeAttempt(crossrefFirst, ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE))
        assertEquals(
            ReferenceResolutionAttemptNoSendReason.LOOKUP_ORDER_NOT_READY,
            noSend(reserveResult(runId, entryId, "datacite", 2)),
        )

        val crossrefRetry = reserve(runId, entryId, "crossref", 1)
        assertEquals(2, crossrefRetry.providerAttemptOrdinal)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(crossrefRetry))
        assertFalse(
            service.completeAttemptAndFinalize(
                reservation = crossrefRetry,
                attemptOutcomeCode = ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE,
                chainOutcome = ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED,
                metadataOriginProviderId = null,
                nextActionWindow = ReferenceResolutionNextActionWindow(waitMillis = 0, requestBudgetMillis = 25_000),
            ),
        )
        assertTrue(
            service.completeAttemptAndRecordLookup(
                crossrefRetry,
                ReferenceResolutionProviderLookupOutcomeCode.RETRYABLE_FAILURE,
            ),
        )

        val datacite = reserve(runId, entryId, "datacite", 2)
        assertEquals(3, datacite.reservationOrdinal)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(datacite))
        assertTrue(
            service.completeAttemptAndRecordLookup(
                datacite,
                ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND,
            ),
        )
        assertEquals(ReferenceResolutionAttemptNoSendReason.LOOKUP_ALREADY_RECORDED, noSend(reserveResult(runId, entryId, "datacite", 2)))
        assertEquals(3, attemptCount())
        assertEquals(listOf("crossref", "crossref", "datacite"), service.summary(runId, entryId).map { it.providerId })
    }

    @Test
    fun `datacite may consume the single retry when crossref has a definitive miss`() {
        val crossref = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(crossref))
        assertTrue(
            service.completeAttemptAndRecordLookup(
                crossref,
                ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND,
            ),
        )

        val dataciteFirst = reserve(runId, entryId, "datacite", 2)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(dataciteFirst))
        assertTrue(service.completeAttempt(dataciteFirst, ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE))
        val dataciteRetry = reserve(runId, entryId, "datacite", 2)
        assertEquals(2, dataciteRetry.providerAttemptOrdinal)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(dataciteRetry))
        assertFalse(
            service.completeAttemptAndFinalize(
                reservation = dataciteRetry,
                attemptOutcomeCode = ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE,
                chainOutcome = ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED,
                metadataOriginProviderId = null,
            ),
        )
        assertEquals(ReferenceResolutionAttemptState.IN_FLIGHT, service.summary(runId, entryId).last().state)
        assertTrue(
            service.completeAttemptAndFinalize(
                reservation = dataciteRetry,
                attemptOutcomeCode = ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE,
                chainOutcome = ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE,
                metadataOriginProviderId = null,
            ),
        )

        val terminal = assertIs<ReferenceResolutionChainClaimResult.AlreadyFinalized>(
            chainService.claim(runId, entryId, UUID.randomUUID()),
        )
        assertEquals(ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE, terminal.outcome)
        assertEquals(ReferenceResolutionProviderLookupOutcomeCode.RETRYABLE_FAILURE, service.lookupResults(runId, entryId).last().outcomeCode)
        assertEquals(3, attemptCount())
    }

    @Test
    fun `expired first reservation cannot authorize a provider send`() {
        val reservation = reserve(runId, entryId, "crossref", 1)
        jdbc.update(
            "UPDATE reference_resolution_provider_attempts SET deadline_at = clock_timestamp() - interval '1 millisecond' WHERE attempt_id = ?",
            reservation.attemptId,
        )

        assertEquals(
            ReferenceResolutionAttemptStartResult.NoSend(ReferenceResolutionAttemptNoSendReason.DEADLINE_EXHAUSTED),
            service.startAttempt(reservation),
        )
        val summary = service.summary(runId, entryId).single()
        assertEquals(ReferenceResolutionAttemptState.RESERVED, summary.state)
        assertNull(summary.outcomeCode)
        assertNull(summary.completedAt)
        assertTrue(
            service.completeAttemptAndFinalizeWithResult(
                reservation = reservation,
                attemptOutcomeCode = ReferenceResolutionAttemptOutcomeCode.NOT_SENT,
                chainOutcome = ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED,
                metadataOriginProviderId = null,
                persistTerminalResult = {},
            ),
        )
        val finalized = service.summary(runId, entryId).single()
        assertEquals(ReferenceResolutionAttemptState.COMPLETED, finalized.state)
        assertEquals(ReferenceResolutionAttemptOutcomeCode.NOT_SENT, finalized.outcomeCode)
        assertNull(finalized.startedAt)
        assertNotNull(finalized.completedAt)
    }

    @Test
    fun `no-send terminal completion records the reason and blocks another start`() {
        val reservation = reserve(runId, entryId, "crossref", 1)

        assertTrue(
            service.completeAttemptAndFinalizeWithResult(
                reservation = reservation,
                attemptOutcomeCode = ReferenceResolutionAttemptOutcomeCode.NOT_SENT,
                chainOutcome = ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE,
                metadataOriginProviderId = null,
                persistTerminalResult = {},
            ),
        )
        val summary = service.summary(runId, entryId).single()
        assertEquals(ReferenceResolutionAttemptState.COMPLETED, summary.state)
        assertEquals(ReferenceResolutionAttemptOutcomeCode.NOT_SENT, summary.outcomeCode)
        assertNull(summary.startedAt)
        assertNotNull(summary.completedAt)
        assertEquals(
            ReferenceResolutionAttemptStartResult.NoSend(ReferenceResolutionAttemptNoSendReason.FINAL_RESOLUTION),
            service.startAttempt(reservation),
        )
        assertEquals(
            ReferenceResolutionAttemptNoSendReason.FINAL_RESOLUTION,
            noSend(reserveResult(runId, entryId, "crossref", 1)),
        )
        assertEquals(1, attemptCount())
    }

    @Test
    fun `deadline is measured from the persisted first reservation`() {
        val first = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(first))
        assertTrue(service.completeAttempt(first, ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE))
        jdbc.update(
            "UPDATE reference_resolution_provider_attempts SET deadline_at = clock_timestamp() - interval '1 millisecond' WHERE analysis_run_id = ? AND bibliography_entry_id = ?",
            runId,
            entryId,
        )

        assertEquals(ReferenceResolutionAttemptNoSendReason.DEADLINE_EXHAUSTED, noSend(reserveResult(runId, entryId, "crossref", 1)))
        assertEquals(1, attemptCount())
    }

    @Test
    fun `active in-flight owner defers a second delivery until the database lease expires`() {
        val first = authorizationFor(runId, entryId)
        val reservation = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(reservation))
        val result = chainService.claim(runId, entryId, UUID.randomUUID())

        val deferred = assertIs<ReferenceResolutionChainClaimResult.Deferred>(result)
        assertEquals(first.leaseExpiresAt, deferred.until)
        assertTrue(deferred.until.isAfter(jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp::class.java)!!.toInstant()))
    }

    @Test
    fun `expired reserved slot transfers to a new fence and only the new authorization can start`() {
        val oldAuthorization = authorizationFor(runId, entryId)
        val oldReservation = reserve(runId, entryId, "crossref", 1)
        expireLease(oldAuthorization)

        val takeover = assertIs<ReferenceResolutionChainClaimResult.Claimed>(
            chainService.claim(runId, entryId, UUID.randomUUID()),
        )
        val recovered = requireNotNull(takeover.recoveredReservation)
        assertEquals(oldReservation.attemptId, recovered.attemptId)
        assertEquals(oldReservation.reservationOrdinal, recovered.reservationOrdinal)
        assertEquals(oldAuthorization.ownerFence + 1, takeover.authorization.ownerFence)
        assertEquals(takeover.authorization.ownerToken, recovered.ownerToken)
        assertEquals(takeover.authorization.ownerFence, recovered.ownerFence)

        assertEquals(
            ReferenceResolutionAttemptStartResult.NoSend(ReferenceResolutionAttemptNoSendReason.OWNERSHIP_LOST),
            service.startAttempt(oldReservation),
        )
        assertEquals(ReferenceResolutionAttemptState.RESERVED, service.summary(runId, entryId).single().state)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(recovered))
        assertEquals(1, attemptCount())
    }

    @Test
    fun `expired reserved start racing takeover cannot authorize a send`() {
        val oldAuthorization = authorizationFor(runId, entryId)
        val oldReservation = reserve(runId, entryId, "crossref", 1)
        expireLease(oldAuthorization)
        val ready = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val oldStart = executor.submit<ReferenceResolutionAttemptStartResult> {
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                service.startAttempt(oldReservation)
            }
            val takeover = executor.submit<ReferenceResolutionChainClaimResult> {
                assertTrue(ready.await(5, TimeUnit.SECONDS))
                chainService.claim(runId, entryId, UUID.randomUUID())
            }
            ready.countDown()

            val oldStartResult = oldStart.get(10, TimeUnit.SECONDS)
            val newOwner = assertIs<ReferenceResolutionChainClaimResult.Claimed>(takeover.get(10, TimeUnit.SECONDS))
            assertFalse(oldStartResult == ReferenceResolutionAttemptStartResult.Started)
            assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(requireNotNull(newOwner.recoveredReservation)))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `expired in-flight attempt is finalized as unknown with the new owner result`() {
        val authorization = authorizationFor(runId, entryId)
        val reservation = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(reservation))
        expireLease(authorization)

        val claimed = assertIs<ReferenceResolutionChainClaimResult.Claimed>(
            chainService.claim(runId, entryId, UUID.randomUUID()),
        )
        val uncertainAttempt = requireNotNull(claimed.uncertainAttempt)
        assertNotNull(uncertainAttempt)
        assertNull(claimed.recoveredReservation)
        assertEquals(ReferenceResolutionAttemptState.IN_FLIGHT, service.summary(runId, entryId).single().state)
        assertFalse(
            service.completeAttemptAndRecordLookup(
                reservation,
                ReferenceResolutionProviderLookupOutcomeCode.FOUND,
            ),
        )
        assertTrue(service.lookupResults(runId, entryId).isEmpty())

        var terminalResultPersisted = false
        assertTrue(
            service.finalizeAbandonedAttemptWithResult(claimed.authorization, uncertainAttempt) {
                terminalResultPersisted = true
            },
        )
        assertTrue(terminalResultPersisted)
        assertEquals(ReferenceResolutionAttemptState.UNKNOWN, service.summary(runId, entryId).single().state)
        assertEquals(
            ReferenceResolutionChainOutcome.UNKNOWN,
            assertIs<ReferenceResolutionChainClaimResult.AlreadyFinalized>(
                chainService.claim(runId, entryId, UUID.randomUUID()),
            ).outcome,
        )
    }

    @Test
    fun `abandoned unknown finalization rolls back attempt and chain state with terminal callback`() {
        createActiveDocumentForRun()
        val guardedService = createGuardedService()
        val previousOwner = authorizationFor(runId, entryId)
        val reservation = reserve(runId, entryId, "crossref", 1)
        assertEquals(ReferenceResolutionAttemptStartResult.Started, service.startAttempt(reservation))
        expireLease(previousOwner)

        val takeover = assertIs<ReferenceResolutionChainClaimResult.Claimed>(
            chainService.claim(runId, entryId, UUID.randomUUID()),
        )
        val uncertainAttempt = requireNotNull(takeover.uncertainAttempt)
        jdbc.execute("CREATE TABLE IF NOT EXISTS unknown_commit_probe (id UUID PRIMARY KEY)")
        jdbc.update("DELETE FROM unknown_commit_probe")

        try {
            assertThrows(RuntimeException::class.java) {
                guardedService.finalizeAbandonedAttemptWithResult(takeover.authorization, uncertainAttempt) {
                    jdbc.update("INSERT INTO unknown_commit_probe (id) VALUES (?)", runId)
                    throw IllegalStateException("test-only unknown terminal commit failure")
                }
            }

            assertEquals(ReferenceResolutionAttemptState.IN_FLIGHT, service.summary(runId, entryId).single().state)
            assertNull(
                jdbc.queryForObject(
                    "SELECT terminal_outcome FROM reference_resolution_entry_chain_states WHERE analysis_run_id = ? AND bibliography_entry_id = ?",
                    String::class.java,
                    runId,
                    entryId,
                ),
            )
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM unknown_commit_probe", Int::class.java))

            assertTrue(
                guardedService.finalizeAbandonedAttemptWithResult(takeover.authorization, uncertainAttempt) {
                    jdbc.update("INSERT INTO unknown_commit_probe (id) VALUES (?)", runId)
                },
            )
            assertEquals(ReferenceResolutionAttemptState.UNKNOWN, service.summary(runId, entryId).single().state)
            assertEquals(
                ReferenceResolutionChainOutcome.UNKNOWN,
                assertIs<ReferenceResolutionChainClaimResult.AlreadyFinalized>(
                    chainService.claim(runId, entryId, UUID.randomUUID()),
                ).outcome,
            )
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM unknown_commit_probe", Int::class.java))
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS unknown_commit_probe")
        }
    }

    @Test
    fun `old fence cannot finalize and concurrent terminal outcomes have one winner`() {
        val oldAuthorization = authorizationFor(runId, entryId)
        expireLease(oldAuthorization)
        val currentAuthorization = assertIs<ReferenceResolutionChainClaimResult.Claimed>(
            chainService.claim(runId, entryId, UUID.randomUUID()),
        ).authorization
        assertFalse(
            chainService.finalizeChain(
                oldAuthorization,
                ReferenceResolutionChainOutcome.NO_CANDIDATE_OR_DOI_UNCONFIRMED,
                null,
            ),
        )

        val ready = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val outcomes = listOf(
                ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE,
                ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED,
            )
            val finalizations = outcomes.map { outcome ->
                executor.submit<Boolean> {
                    assertTrue(ready.await(5, TimeUnit.SECONDS))
                    chainService.finalizeChain(currentAuthorization, outcome, null)
                }
            }
            ready.countDown()
            assertEquals(1, finalizations.count { it.get(10, TimeUnit.SECONDS) })
        } finally {
            executor.shutdownNow()
        }

        val terminal = assertIs<ReferenceResolutionChainClaimResult.AlreadyFinalized>(
            chainService.claim(runId, entryId, UUID.randomUUID()),
        )
        assertTrue(terminal.outcome in setOf(
            ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE,
            ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED,
        ))
    }

    @Test
    fun `renewal requires current unexpired owner fence`() {
        val owner = authorizationFor(runId, entryId)
        val renewed = requireNotNull(chainService.renew(owner))
        assertTrue(renewed.leaseExpiresAt.isAfter(owner.leaseExpiresAt))
        expireLease(renewed)
        val newOwner = assertIs<ReferenceResolutionChainClaimResult.Claimed>(
            chainService.claim(runId, entryId, UUID.randomUUID()),
        ).authorization

        assertNull(chainService.renew(renewed))
        assertNotNull(chainService.renew(newOwner))
    }

    @Test
    fun `missing and final entries are explicit no send results and order is fixed`() {
        assertEquals(
            ReferenceResolutionAttemptNoSendReason.MISSING_ENTRY,
            noSend(reserveResult(
                ReferenceResolutionChainAuthorization(UUID.randomUUID(), entryId, UUID.randomUUID(), 1, java.time.Instant.EPOCH),
                "crossref",
                1,
            )),
        )
        assertEquals(
            ReferenceResolutionAttemptNoSendReason.LOOKUP_ORDER_NOT_READY,
            noSend(reserveResult(runId, entryId, "datacite", 2)),
        )
        jdbc.update(
            "INSERT INTO bibliography_entry_resolutions (analysis_run_id, bibliography_entry_id) VALUES (?, ?)",
            runId,
            entryId,
        )
        assertEquals(
            ReferenceResolutionAttemptNoSendReason.FINAL_RESOLUTION,
            noSend(reserveResult(runId, entryId, "crossref", 1)),
        )
        assertEquals(0, attemptCount())
    }

    @Test
    fun `composite reference foreign key is run local and cascades with entry deletion`() {
        val otherRunId = UUID.randomUUID()
        val otherEntryId = UUID.randomUUID()
        jdbc.update("INSERT INTO analysis_runs (id) VALUES (?)", otherRunId)
        jdbc.update("INSERT INTO parsed_document_parses (analysis_run_id) VALUES (?)", otherRunId)
        insertEntry(otherRunId, otherEntryId)

        assertEquals(
            ReferenceResolutionAttemptNoSendReason.MISSING_ENTRY,
            noSend(reserveResult(
                ReferenceResolutionChainAuthorization(runId, otherEntryId, UUID.randomUUID(), 1, java.time.Instant.EPOCH),
                "crossref",
                1,
            )),
        )
        reserve(otherRunId, otherEntryId, "crossref", 1)
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM reference_resolution_provider_attempts", Int::class.java))

        jdbc.update("DELETE FROM bibliography_entries WHERE analysis_run_id = ? AND id = ?", otherRunId, otherEntryId)
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM reference_resolution_provider_attempts", Int::class.java))
    }

    @Test
    fun `concurrent reservations serialize on the bibliography row`() {
        authorizationFor(runId, entryId)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                executor.submit<ReferenceResolutionAttemptResult> {
                    assertTrue(start.await(5, TimeUnit.SECONDS))
                    reserveResult(runId, entryId, "crossref", 1)
                }
            }
            start.countDown()
            val decisions = results.map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(1, decisions.count { it is ReferenceResolutionAttemptResult.Reserved })
            assertEquals(1, decisions.count { it is ReferenceResolutionAttemptResult.NoSend })
            assertEquals(1, attemptCount())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `revert refuses to discard reserved attempts`() {
        reserve(runId, entryId, "crossref", 1)
        val revertFile = Path.of("db/revert/reference_resolution_attempt_ledger.sql")

        assertThrows(SQLException::class.java) { executeSqlFile(revertFile) }
        assertEquals(1, attemptCount())
    }

    @Test
    fun `revert refuses to drop persisted logical lookup results`() {
        val authorization = authorizationFor(runId, entryId)
        assertTrue(
            service.recordCachedLookup(
                authorization,
                providerId = "crossref",
                lookupOrdinal = 1,
                outcomeCode = ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND,
            ),
        )

        assertThrows(SQLException::class.java) {
            executeSqlFile(Path.of("db/revert/reference_resolution_attempt_ledger.sql"))
        }
        assertEquals(ReferenceResolutionProviderLookupSource.CACHE, service.lookupResults(runId, entryId).single().source)
    }

    @Test
    fun `revert refuses to drop a row committed while it waits for the ledger lock`() {
        val schema = "attempt_ledger_revert_race_test"
        jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        jdbc.execute("CREATE SCHEMA $schema")
        jdbc.execute("CREATE TABLE $schema.reference_resolution_entry_chain_states (analysis_run_id UUID, bibliography_entry_id UUID)")
        jdbc.execute("CREATE TABLE $schema.reference_resolution_provider_attempts (attempt_id UUID PRIMARY KEY)")
        jdbc.execute("CREATE TABLE $schema.reference_resolution_provider_lookup_results (lookup_ordinal SMALLINT PRIMARY KEY)")
        val pendingInsert = dataSource.connection.apply { autoCommit = false }
        pendingInsert.createStatement().use { it.execute("SET search_path TO $schema") }
        pendingInsert.prepareStatement("INSERT INTO reference_resolution_provider_attempts (attempt_id) VALUES (?)").use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.executeUpdate()
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val revert = executor.submit<Boolean> {
                dataSource.connection.use { connection ->
                    connection.autoCommit = false
                    try {
                        connection.createStatement().use { statement ->
                            statement.execute("SET search_path TO $schema")
                            statement.execute(Files.readString(Path.of("db/revert/reference_resolution_attempt_ledger.sql")))
                        }
                        connection.commit()
                        false
                    } catch (_: SQLException) {
                        connection.rollback()
                        true
                    }
                }
            }
            val waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var waitingForExclusiveLock = false
            while (!waitingForExclusiveLock && !revert.isDone && System.nanoTime() < waitDeadline) {
                waitingForExclusiveLock = jdbc.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1 FROM pg_locks l
                        JOIN pg_class c ON c.oid = l.relation
                        JOIN pg_namespace n ON n.oid = c.relnamespace
                        WHERE n.nspname = '$schema'
                          AND c.relname = 'reference_resolution_provider_attempts'
                          AND l.mode = 'AccessExclusiveLock' AND NOT l.granted
                    )
                    """.trimIndent(),
                    Boolean::class.java,
                ) == true
                if (!waitingForExclusiveLock) Thread.sleep(10)
            }
            assertTrue(waitingForExclusiveLock, "Revert should lock the table before checking it is empty.")

            pendingInsert.commit()
            assertTrue(revert.get(10, TimeUnit.SECONDS))
            assertEquals(
                1,
                jdbc.queryForObject("SELECT count(*) FROM $schema.reference_resolution_provider_attempts", Int::class.java),
            )
        } finally {
            pendingInsert.rollback()
            pendingInsert.close()
            executor.shutdownNow()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            jdbc.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
        }
    }

    @Test
    fun `start rejects an ambient transaction that could span provider egress`() {
        val reservation = reserve(runId, entryId, "crossref", 1)
        val transaction = TransactionTemplate(DataSourceTransactionManager(dataSource))

        assertThrows(IllegalStateException::class.java) {
            transaction.executeWithoutResult { service.startAttempt(reservation) }
        }
        assertEquals(ReferenceResolutionAttemptState.RESERVED, service.summary(runId, entryId).single().state)
    }

    @Test
    fun `reservation rejects an ambient transaction before egress coordination`() {
        val authorization = authorizationFor(runId, entryId)
        val transaction = TransactionTemplate(DataSourceTransactionManager(dataSource))

        assertThrows(IllegalStateException::class.java) {
            transaction.executeWithoutResult {
                service.reserveAttempt(
                    authorization,
                    "crossref",
                    1,
                    ProviderChainLimitsSnapshot.EXACT_DOI_V1,
                )
            }
        }
        assertEquals(0, attemptCount())
    }

    private fun reserve(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        providerId: String,
        lookupNumber: Int,
    ) = assertIs<ReferenceResolutionAttemptResult.Reserved>(
        reserveResult(analysisRunId, bibliographyEntryId, providerId, lookupNumber),
    ).reservation

    private fun reserveResult(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
        providerId: String,
        lookupNumber: Int,
    ): ReferenceResolutionAttemptResult = reserveResult(
        authorizationFor(analysisRunId, bibliographyEntryId),
        providerId,
        lookupNumber,
    )

    private fun reserveResult(
        authorization: ReferenceResolutionChainAuthorization,
        providerId: String,
        lookupNumber: Int,
    ): ReferenceResolutionAttemptResult = service.reserveAttempt(
        authorization,
        providerId,
        lookupNumber,
        ProviderChainLimitsSnapshot.EXACT_DOI_V1,
    )

    private fun authorizationFor(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
    ): ReferenceResolutionChainAuthorization = authorizations.getOrPut(analysisRunId to bibliographyEntryId) {
        claimOwner(analysisRunId, bibliographyEntryId)
    }

    private fun claimOwner(
        analysisRunId: UUID,
        bibliographyEntryId: UUID,
    ): ReferenceResolutionChainAuthorization = assertIs<ReferenceResolutionChainClaimResult.Claimed>(
        chainService.claim(analysisRunId, bibliographyEntryId, UUID.randomUUID()),
    ).authorization

    private fun expireLease(authorization: ReferenceResolutionChainAuthorization) {
        assertEquals(
            1,
            jdbc.update(
                "UPDATE reference_resolution_entry_chain_states SET lease_expires_at = clock_timestamp() - interval '1 millisecond' WHERE analysis_run_id = ? AND bibliography_entry_id = ? AND owner_token = ? AND owner_fence = ?",
                authorization.analysisRunId,
                authorization.bibliographyEntryId,
                authorization.ownerToken,
                authorization.ownerFence,
            ),
        )
    }

    private fun noSend(result: ReferenceResolutionAttemptResult): ReferenceResolutionAttemptNoSendReason =
        assertIs<ReferenceResolutionAttemptResult.NoSend>(result).reason

    private fun attemptCount(): Int = jdbc.queryForObject(
        "SELECT count(*) FROM reference_resolution_provider_attempts WHERE analysis_run_id = ? AND bibliography_entry_id = ?",
        Int::class.java,
        runId,
        entryId,
    )!!

    private fun insertEntry(analysisRunId: UUID, bibliographyEntryId: UUID) {
        jdbc.update("INSERT INTO bibliography_entries (id, analysis_run_id) VALUES (?, ?)", bibliographyEntryId, analysisRunId)
    }

    private fun createActiveDocumentForRun(): UUID {
        val documentId = UUID.randomUUID()
        jdbc.update("INSERT INTO source_documents (id) VALUES (?)", documentId)
        jdbc.update("UPDATE analysis_runs SET document_id = ? WHERE id = ?", documentId, runId)
        return documentId
    }

    private fun createGuardedService(): ReferenceResolutionAttemptService = ReferenceResolutionAttemptService(
        repository = ReferenceResolutionAttemptRepository(jdbc),
        transactionManager = DataSourceTransactionManager(dataSource),
        terminalCommitGuard = ActiveSourceDocumentReferenceResolutionTerminalCommitGuard(SourceDocumentRepository(jdbc)),
    )

    private fun awaitSourceDocumentAdvisoryLockWait(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val deletionIsWaiting = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM pg_stat_activity
                     WHERE wait_event_type = 'Lock'
                       AND state = 'active'
                       AND query LIKE '%pg_advisory_xact_lock%'
                )
                """.trimIndent(),
                Boolean::class.java,
            ) == true
            if (deletionIsWaiting) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun awaitSourceDocumentDeletionLockWait(): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val deletionIsWaiting = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM pg_stat_activity
                     WHERE wait_event_type = 'Lock'
                       AND state = 'active'
                       AND query LIKE '%source_documents%'
                )
                """.trimIndent(),
                Boolean::class.java,
            ) == true
            if (deletionIsWaiting) return true
            Thread.sleep(10)
        }
        return false
    }

    private fun createService(): ReferenceResolutionAttemptService = ReferenceResolutionAttemptService(
        repository = ReferenceResolutionAttemptRepository(jdbc),
        transactionManager = DataSourceTransactionManager(dataSource),
        terminalCommitGuard = ReferenceResolutionTerminalCommitGuard {},
    )

    private fun createChainService(): ReferenceResolutionChainService = ReferenceResolutionChainService(
        repository = ReferenceResolutionAttemptRepository(jdbc),
        transactionManager = DataSourceTransactionManager(dataSource),
    )

    companion object {
        private val postgres = PostgreSQLContainer<Nothing>(DockerImageName.parse("pgvector/pgvector:0.8.0-pg17"))

        @Container
        @JvmStatic
        val postgresContainer: PostgreSQLContainer<Nothing> = postgres

        private lateinit var dataSource: DriverManagerDataSource
        private lateinit var jdbc: JdbcTemplate
        private lateinit var runId: UUID
        private lateinit var entryId: UUID
        private lateinit var service: ReferenceResolutionAttemptService
        private lateinit var chainService: ReferenceResolutionChainService
        private lateinit var authorizations: MutableMap<Pair<UUID, UUID>, ReferenceResolutionChainAuthorization>

        @BeforeAll
        @JvmStatic
        fun setUpDatabase() {
            dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            jdbc = JdbcTemplate(dataSource)
            jdbc.execute("CREATE TABLE source_documents (id UUID PRIMARY KEY)")
            jdbc.execute("CREATE TABLE source_document_tombstones (document_id UUID PRIMARY KEY REFERENCES source_documents(id))")
            jdbc.execute("CREATE TABLE analysis_runs (id UUID PRIMARY KEY, document_id UUID, configuration_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb)")
            jdbc.execute("CREATE TABLE parsed_document_parses (analysis_run_id UUID PRIMARY KEY REFERENCES analysis_runs(id) ON DELETE CASCADE)")
            jdbc.execute("""
                CREATE TABLE bibliography_entries (
                    id UUID PRIMARY KEY,
                    analysis_run_id UUID NOT NULL REFERENCES parsed_document_parses(analysis_run_id) ON DELETE CASCADE,
                    UNIQUE (analysis_run_id, id)
                )
            """.trimIndent())
            jdbc.execute("""
                CREATE TABLE bibliography_entry_resolutions (
                    analysis_run_id UUID NOT NULL,
                    bibliography_entry_id UUID NOT NULL,
                    PRIMARY KEY (analysis_run_id, bibliography_entry_id),
                    FOREIGN KEY (analysis_run_id, bibliography_entry_id)
                        REFERENCES bibliography_entries(analysis_run_id, id) ON DELETE CASCADE
                )
            """.trimIndent())
            val deployDirectory = listOf(Path.of("db/deploy"), Path.of("api/db/deploy"))
                .firstOrNull(Files::isDirectory) ?: error("Could not locate Sqitch deployment directory")
            executeSqlFile(deployDirectory.resolve("reference_resolution_attempt_ledger.sql"))
            executeSqlFile(deployDirectory.resolveSibling("verify").resolve("reference_resolution_attempt_ledger.sql"))
        }

        private fun executeSqlFile(path: Path) {
            val sql = Files.readString(path)
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute(sql) }
            }
        }

    }
}
