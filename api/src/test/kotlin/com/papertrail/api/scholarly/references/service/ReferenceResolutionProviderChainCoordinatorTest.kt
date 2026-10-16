package com.papertrail.api.scholarly.references.service

import com.papertrail.api.analysis.configuration.ProviderChainLimitsSnapshot
import com.papertrail.api.analysis.configuration.ProviderChainSnapshot
import com.papertrail.api.analysis.configuration.RunConfigurationFactory
import com.papertrail.api.analysis.configuration.ValidationLimitsSnapshot
import com.papertrail.api.analysis.http.ExternalProviderConsentRequest
import com.papertrail.api.analysis.http.RunConfigurationRequest
import com.papertrail.api.document.service.SourceDocumentEgressHandoffLock
import com.papertrail.api.external.crossref.CrossrefChainLookup
import com.papertrail.api.external.crossref.CrossrefChainLookupCache
import com.papertrail.api.external.crossref.CrossrefChainLookupQuery
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCatalog
import com.papertrail.api.infrastructure.providers.ProviderEgressPermitGate
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptMetrics
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptNoSendReason
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptOutcomeCode
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptReservation
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptResult
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionAttemptStartResult
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainAuthorization
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionChainOutcome
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionNextActionWindow
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionProviderLookupOutcomeCode
import com.papertrail.api.scholarly.references.attempts.service.ReferenceResolutionAttemptService
import com.papertrail.api.scholarly.references.attempts.service.ReferenceResolutionChainService
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.ProviderChainMetadataLookup
import com.papertrail.api.scholarly.references.client.ProviderChainMetadataResponse
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookupFactory
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataProviderFailure
import com.papertrail.api.scholarly.references.client.ScholarlyWork
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

@Suppress("UNCHECKED_CAST")
private fun anyCallback(): () -> Unit = Mockito.any<() -> Unit>() ?: { Unit }

class ReferenceResolutionProviderChainCoordinatorTest {
    @Test
    fun `Crossref found result is matched and committed before DataCite is consulted`() {
        val fixture = fixture()
        val events = mutableListOf<String>()
        fixture.recordLeaseRenewal(events)
        val crossref = RecordingLookup("crossref", events).apply {
            doiResponse = ScholarlyWork("10.1234/paper", "A matching scholarly paper", listOf("Ada Researcher"), 2024)
        }
        val dataCite = RecordingLookup("datacite", events)
        val crossrefReservation = fixture.reservation("crossref", 1)
        fixture.stubReserved(crossrefReservation, events)
        fixture.stubFinalAttemptCommit(events)

        val disposition = fixture.coordinator(
            cache = RecordingCache(events),
            egress = RecordingEgressGate(events),
            factories = listOf(crossref.factory(), dataCite.factory()),
            sourceDocumentEgressHandoffLock = SourceDocumentEgressHandoffLock {
                events += "document-lock-acquired"
                AutoCloseable { events += "document-lock-released" }
            },
        ).resolveAndFinalize(
            authorization = fixture.authorization,
            configuration = fixture.configuration,
            reference = fixture.reference,
            persistTerminalResult = { events += "commit:${it.outcome}"; fixture.terminal = it },
        )

        assertEquals(ProviderChainResolutionDisposition.Finalized, disposition)
        assertEquals(ReferenceResolutionChainOutcome.RESOLVED, fixture.terminal?.outcome)
        assertEquals("crossref", fixture.terminal?.metadataOriginProviderId)
        assertEquals("10.1234/paper", crossref.doiQueries.single())
        assertTrue(dataCite.doiQueries.isEmpty())
        assertTrue(events.indexOf("permit:crossref") < events.indexOf("document-lock-acquired"))
        assertTrue(events.indexOf("document-lock-acquired") < events.indexOf("start:crossref"))
        assertTrue(events.indexOf("permit:crossref") < events.indexOf("renew"))
        assertTrue(events.indexOf("renew") < events.indexOf("start:crossref"))
        assertTrue(events.indexOf("start:crossref") < events.indexOf("http:crossref"))
        assertEquals(fixture.now.plusSeconds(60), fixture.authorization.leaseExpiresAt)
        assertEquals(200, fixture.finalAttemptMetrics?.httpStatus)
        assertEquals(42L, fixture.finalAttemptMetrics?.responseBytes)
        assertTrue(events.indexOf("http:crossref") < events.indexOf("document-lock-released"))
        assertTrue(events.indexOf("document-lock-released") < events.indexOf("commit:RESOLVED"))
        assertTrue(events.indexOf("commit:RESOLVED") < events.indexOf("release:crossref"))
    }

    @Test
    fun `Crossref DOI miss falls back to the same exact DataCite DOI`() {
        val fixture = fixture()
        val events = mutableListOf<String>()
        fixture.recordLeaseRenewal(events)
        val crossref = RecordingLookup("crossref", events)
        val dataCite = RecordingLookup("datacite", events).apply {
            doiResponse = ScholarlyWork("10.1234/paper", "A matching scholarly paper", listOf("Ada Researcher"), 2024)
        }
        val crossrefReservation = fixture.reservation("crossref", 1)
        val dataCiteReservation = fixture.reservation("datacite", 2)
        fixture.stubReserved(crossrefReservation, events)
        fixture.stubReserved(dataCiteReservation, events)
        fixture.stubIntermediateCrossrefNotFound(crossrefReservation, events)
        fixture.stubFinalAttemptCommit(events)

        val disposition = fixture.coordinator(
            cache = RecordingCache(events),
            egress = RecordingEgressGate(events),
            factories = listOf(crossref.factory(), dataCite.factory()),
        ).resolveAndFinalize(
            authorization = fixture.authorization,
            configuration = fixture.configuration,
            reference = fixture.reference,
            persistTerminalResult = { events += "commit:${it.outcome}"; fixture.terminal = it },
        )

        assertEquals(ProviderChainResolutionDisposition.Finalized, disposition, "events: $events")
        assertEquals(listOf("10.1234/paper"), crossref.doiQueries)
        assertEquals(listOf("10.1234/paper"), dataCite.doiQueries)
        assertTrue(dataCite.titleQueries.isEmpty())
        assertEquals(ReferenceResolutionChainOutcome.RESOLVED, fixture.terminal?.outcome)
        assertEquals("datacite", fixture.terminal?.metadataOriginProviderId)
        assertTrue(events.indexOf("complete:crossref") < events.indexOf("permit:datacite"))
        assertEquals(2, events.count { it == "renew" })
    }

    @Test
    fun `permanent Crossref DOI failure persists and permits the exact DataCite fallback without retrying Crossref`() {
        val fixture = fixture()
        val events = mutableListOf<String>()
        fixture.recordLeaseRenewal(events)
        val crossref = RecordingLookup("crossref", events).apply {
            doiFailure = ScholarlyMetadataProviderFailure(
                ReferenceResolutionAttemptOutcomeCode.PERMANENT_FAILURE,
                httpStatus = 501,
                message = "Safe permanent provider failure.",
            )
        }
        val dataCite = RecordingLookup("datacite", events).apply {
            doiResponse = ScholarlyWork("10.1234/paper", "A matching scholarly paper", listOf("Ada Researcher"), 2024)
        }
        val crossrefReservation = fixture.reservation("crossref", 1)
        val dataCiteReservation = fixture.reservation("datacite", 2)
        fixture.stubReserved(crossrefReservation, events)
        fixture.stubReserved(dataCiteReservation, events)
        fixture.stubIntermediateCrossrefOutcome(
            crossrefReservation,
            ReferenceResolutionProviderLookupOutcomeCode.PERMANENT_FAILURE,
            events,
        )
        fixture.stubFinalAttemptCommit(events)

        val disposition = fixture.coordinator(
            cache = RecordingCache(events),
            egress = RecordingEgressGate(events),
            factories = listOf(crossref.factory(), dataCite.factory()),
        ).resolveAndFinalize(
            authorization = fixture.authorization,
            configuration = fixture.configuration,
            reference = fixture.reference,
            persistTerminalResult = { fixture.terminal = it },
        )

        assertEquals(ProviderChainResolutionDisposition.Finalized, disposition, "events: $events")
        assertEquals(listOf("10.1234/paper"), crossref.doiQueries)
        assertEquals(listOf("10.1234/paper"), dataCite.doiQueries)
        assertEquals(ReferenceResolutionChainOutcome.RESOLVED, fixture.terminal?.outcome)
        assertEquals("datacite", fixture.terminal?.metadataOriginProviderId)
        assertEquals(501, fixture.crossrefAttemptMetrics?.httpStatus)
        assertTrue(events.indexOf("complete:crossref") < events.indexOf("permit:datacite"))
    }

    @Test
    fun `unusable Crossref DOI followed by a DataCite miss reports provider unavailable`() {
        val fixture = fixture()
        val events = mutableListOf<String>()
        fixture.recordLeaseRenewal(events)
        val crossref = RecordingLookup("crossref", events).apply {
            doiFailure = ScholarlyMetadataProviderFailure(
                ReferenceResolutionAttemptOutcomeCode.UNUSABLE,
                httpStatus = 200,
                message = "Synthetic unusable provider response.",
            )
        }
        val dataCite = RecordingLookup("datacite", events)
        val crossrefReservation = fixture.reservation("crossref", 1)
        val dataCiteReservation = fixture.reservation("datacite", 2)
        fixture.stubReserved(crossrefReservation, events)
        fixture.stubReserved(dataCiteReservation, events)
        fixture.stubIntermediateCrossrefOutcome(
            crossrefReservation,
            ReferenceResolutionProviderLookupOutcomeCode.UNUSABLE,
            events,
        )
        fixture.stubFinalAttemptCommit(events)

        val disposition = fixture.coordinator(
            cache = RecordingCache(events),
            egress = RecordingEgressGate(events),
            factories = listOf(crossref.factory(), dataCite.factory()),
        ).resolveAndFinalize(
            authorization = fixture.authorization,
            configuration = fixture.configuration,
            reference = fixture.reference,
            persistTerminalResult = { fixture.terminal = it },
        )

        assertEquals(ProviderChainResolutionDisposition.Finalized, disposition, "events: $events")
        assertEquals(listOf("10.1234/paper"), crossref.doiQueries)
        assertEquals(listOf("10.1234/paper"), dataCite.doiQueries)
        assertEquals(ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE, fixture.terminal?.outcome)
        assertEquals("PROVIDER_UNAVAILABLE", fixture.terminal?.decision?.reasonCode)
    }

    @Test
    fun `second retryable title lookup persists its metrics before provider unavailable terminal state`() {
        val fixture = fixture()
        val events = mutableListOf<String>()
        val crossref = RecordingLookup("crossref", events).apply {
            titleFailure = ScholarlyMetadataProviderFailure(
                ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE,
                httpStatus = 503,
                responseBytes = 29,
                message = "Safe transient provider failure.",
            )
        }
        val dataCite = RecordingLookup("datacite", events)
        val reservation = fixture.reservation("crossref", 1, providerAttemptOrdinal = 2)
        fixture.stubReserved(reservation, events)
        fixture.stubIntermediateCrossrefOutcome(
            reservation,
            ReferenceResolutionProviderLookupOutcomeCode.RETRYABLE_FAILURE,
            events,
        )
        fixture.stubNoAttemptFinalization(events)

        val disposition = fixture.coordinator(
            cache = RecordingCache(events),
            egress = RecordingEgressGate(events),
            factories = listOf(crossref.factory(), dataCite.factory()),
        ).resolveAndFinalize(
            authorization = fixture.authorization,
            configuration = fixture.configuration,
            reference = fixture.reference.copy(doi = null),
            persistTerminalResult = { fixture.terminal = it },
        )

        assertEquals(ProviderChainResolutionDisposition.Finalized, disposition, "events: $events")
        assertEquals(ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE, fixture.terminal?.outcome)
        assertEquals("PROVIDER_UNAVAILABLE", fixture.terminal?.decision?.reasonCode)
        assertEquals(503, fixture.crossrefAttemptMetrics?.httpStatus)
        assertEquals(29L, fixture.crossrefAttemptMetrics?.responseBytes)
        assertEquals(listOf("A matching scholarly paper"), crossref.titleQueries)
        assertTrue(dataCite.doiQueries.isEmpty())
        assertTrue(events.indexOf("complete:crossref") < events.indexOf("ledger-commit"))
    }

    @Test
    fun `title-only lookup searches Crossref with the pinned candidate cap and never consults DataCite`() {
        val fixture = fixture()
        val events = mutableListOf<String>()
        val crossref = RecordingLookup("crossref", events).apply {
            titleResponse = listOf(ScholarlyWork(null, "A matching scholarly paper", listOf("Ada Researcher"), 2024))
        }
        val dataCite = RecordingLookup("datacite", events)
        val reservation = fixture.reservation("crossref", 1)
        fixture.stubReserved(reservation, events)
        fixture.stubFinalAttemptCommit(events)

        val disposition = fixture.coordinator(
            cache = RecordingCache(events),
            egress = RecordingEgressGate(events),
            factories = listOf(crossref.factory(), dataCite.factory()),
        ).resolveAndFinalize(
            authorization = fixture.authorization,
            configuration = fixture.configuration,
            reference = fixture.reference.copy(doi = null),
            persistTerminalResult = { fixture.terminal = it },
        )

        assertEquals(ProviderChainResolutionDisposition.Finalized, disposition)
        assertEquals(listOf("A matching scholarly paper"), crossref.titleQueries)
        assertEquals(listOf(10), crossref.titleCandidateLimits)
        assertTrue(crossref.doiQueries.isEmpty())
        assertTrue(dataCite.doiQueries.isEmpty())
        assertEquals(ReferenceResolutionChainOutcome.RESOLVED, fixture.terminal?.outcome)
        assertEquals("crossref", fixture.terminal?.metadataOriginProviderId)
    }

    @Test
    fun `a conflicting Crossref identity stops exact DOI fallback`() {
        val fixture = fixture()
        val events = mutableListOf<String>()
        val crossref = RecordingLookup("crossref", events).apply {
            doiResponse = ScholarlyWork("10.1234/paper", "A different scholarly work", listOf("Ada Researcher"), 2024)
        }
        val dataCite = RecordingLookup("datacite", events)
        val reservation = fixture.reservation("crossref", 1)
        fixture.stubReserved(reservation, events)
        fixture.stubFinalAttemptCommit(events)

        val disposition = fixture.coordinator(
            cache = RecordingCache(events),
            egress = RecordingEgressGate(events),
            factories = listOf(crossref.factory(), dataCite.factory()),
        ).resolveAndFinalize(
            authorization = fixture.authorization,
            configuration = fixture.configuration,
            reference = fixture.reference,
            persistTerminalResult = { fixture.terminal = it },
        )

        assertEquals(ProviderChainResolutionDisposition.Finalized, disposition)
        assertEquals(ReferenceResolutionChainOutcome.CONFLICT_OR_AMBIGUITY, fixture.terminal?.outcome)
        assertEquals("DOI_TITLE_CONFLICT", fixture.terminal?.decision?.reasonCode)
        assertNull(fixture.terminal?.metadataOriginProviderId)
        assertTrue(dataCite.doiQueries.isEmpty())
    }

    @Test
    fun `authorized cached Crossref not-found result proceeds to exact DOI DataCite without Crossref wire attempt`() {
        val fixture = fixture()
        val events = mutableListOf<String>()
        val crossref = RecordingLookup("crossref", events)
        val dataCite = RecordingLookup("datacite", events).apply {
            doiResponse = ScholarlyWork("10.1234/paper", "A matching scholarly paper", listOf("Ada Researcher"), 2024)
        }
        val dataCiteReservation = fixture.reservation("datacite", 2)
        Mockito.`when`(fixture.attemptService.recordCachedLookup(
            fixture.authorization,
            ProviderChainSnapshot.CROSSREF_PROVIDER_ID,
            1,
            ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND,
        )).thenReturn(true)
        fixture.stubReserved(dataCiteReservation, events)
        fixture.stubFinalAttemptCommit(events)

        val disposition = fixture.coordinator(
            cache = RecordingCache(
                events,
                cachedLookup = CrossrefChainLookup(
                    ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND,
                    emptyList(),
                    fixture.authorization.analysisRunId,
                ),
            ),
            egress = RecordingEgressGate(events),
            factories = listOf(crossref.factory(), dataCite.factory()),
        ).resolveAndFinalize(
            authorization = fixture.authorization,
            configuration = fixture.configuration,
            reference = fixture.reference,
            persistTerminalResult = { fixture.terminal = it },
        )

        assertEquals(ProviderChainResolutionDisposition.Finalized, disposition)
        assertTrue(crossref.doiQueries.isEmpty())
        assertEquals(listOf("10.1234/paper"), dataCite.doiQueries)
        assertEquals("datacite", fixture.terminal?.metadataOriginProviderId)
        Mockito.verify(fixture.attemptService, Mockito.never()).reserveAttempt(
            fixture.authorization,
            ProviderChainSnapshot.CROSSREF_PROVIDER_ID,
            1,
            ProviderChainLimitsSnapshot.EXACT_DOI_V1,
        )
    }

    @Test
    fun `egress coordination failure is provider-unavailable rather than budget exhaustion`() {
        val fixture = fixture()
        val events = mutableListOf<String>()
        val crossref = RecordingLookup("crossref", events)
        val reservation = fixture.reservation("crossref", 1)
        fixture.stubReserved(reservation, events)
        fixture.stubFinalAttemptCommit(events)

        val disposition = fixture.coordinator(
            cache = RecordingCache(events),
            egress = RecordingEgressGate(events, unavailable = true),
            factories = listOf(crossref.factory()),
        ).resolveAndFinalize(
            authorization = fixture.authorization,
            configuration = fixture.configuration,
            reference = fixture.reference,
            persistTerminalResult = { fixture.terminal = it },
        )

        assertEquals(ProviderChainResolutionDisposition.Finalized, disposition)
        assertEquals(ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE, fixture.terminal?.outcome)
        assertEquals("PROVIDER_UNAVAILABLE", fixture.terminal?.decision?.reasonCode)
        assertTrue(crossref.doiQueries.isEmpty())
    }

    @Test
    fun `attempt budget rejection is distinct from provider unavailability`() {
        val fixture = fixture()
        val events = mutableListOf<String>()
        val crossref = RecordingLookup("crossref", events)
        Mockito.`when`(fixture.attemptService.reserveAttempt(
            fixture.authorization,
            ProviderChainSnapshot.CROSSREF_PROVIDER_ID,
            1,
            ProviderChainLimitsSnapshot.EXACT_DOI_V1,
        )).thenReturn(ReferenceResolutionAttemptResult.NoSend(ReferenceResolutionAttemptNoSendReason.BUDGET_EXHAUSTED))
        fixture.stubNoAttemptFinalization(events)

        val disposition = fixture.coordinator(
            cache = RecordingCache(events),
            egress = RecordingEgressGate(events),
            factories = listOf(crossref.factory()),
        ).resolveAndFinalize(
            authorization = fixture.authorization,
            configuration = fixture.configuration,
            reference = fixture.reference,
            persistTerminalResult = { fixture.terminal = it },
        )

        assertEquals(ProviderChainResolutionDisposition.Finalized, disposition)
        assertEquals(ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED, fixture.terminal?.outcome)
        assertEquals("PROVIDER_CHAIN_BUDGET_EXHAUSTED", fixture.terminal?.decision?.reasonCode)
        assertTrue(crossref.doiQueries.isEmpty())
    }

    @Test
    fun `missing Crossref consent is rejected before reading a reusable cache hit or reserving an attempt`() {
        val fixture = fixture()
        val events = mutableListOf<String>()
        val crossref = RecordingLookup("crossref", events)
        val noCrossrefConsent = fixture.configuration.copy(
            externalProviderConsents = fixture.configuration.externalProviderConsents
                .filterNot { it.providerId == ProviderChainSnapshot.CROSSREF_PROVIDER_ID },
        )
        val cache = RecordingCache(
            events,
            cachedLookup = CrossrefChainLookup(
                ReferenceResolutionProviderLookupOutcomeCode.FOUND,
                listOf(ScholarlyWork("10.1234/paper", "A matching scholarly paper", listOf("Ada Researcher"), 2024)),
                fixture.authorization.analysisRunId,
            ),
        )
        fixture.stubNoAttemptFinalization(events)

        val disposition = fixture.coordinator(
            cache = cache,
            egress = RecordingEgressGate(events),
            factories = listOf(crossref.factory()),
        ).resolveAndFinalize(
            authorization = fixture.authorization,
            configuration = noCrossrefConsent,
            reference = fixture.reference,
            persistTerminalResult = { events += "commit:${it.outcome}"; fixture.terminal = it },
        )

        assertEquals(ProviderChainResolutionDisposition.Finalized, disposition)
        assertEquals(ReferenceResolutionChainOutcome.CONSENT_OR_CONFIG_REJECTED, fixture.terminal?.outcome)
        assertTrue(events.contains("commit:CONSENT_OR_CONFIG_REJECTED"))
        assertEquals(0, cache.reads)
        assertTrue(crossref.doiQueries.isEmpty())
        Mockito.verify(fixture.attemptService, Mockito.never()).reserveAttempt(
            fixture.authorization,
            ProviderChainSnapshot.CROSSREF_PROVIDER_ID,
            1,
            ProviderChainLimitsSnapshot.EXACT_DOI_V1,
        )
    }

    private fun fixture(): Fixture {
        val catalog = ProviderCatalog.safeDefaults(
            providerChainEnabled = true,
            crossrefEnabled = true,
            crossrefRetentionDisclosure = CROSSREF_DISCLOSURE,
            dataCiteEnabled = true,
            dataCiteRetentionDisclosure = DATACITE_DISCLOSURE,
        )
        val runConfigurationFactory = RunConfigurationFactory(
            providerCatalog = catalog,
            parserId = "grobid",
            parserVersion = "0.9.1-crf",
            languageDetectorVersion = "0.6",
            limits = ValidationLimitsSnapshot(1_000_000, 20, 100_000, 100_000, 100, 0.65),
        )
        val configuration = runConfigurationFactory.from(
            RunConfigurationRequest(
                providerChainId = ProviderChainSnapshot.CHAIN_ID,
                externalProviderConsents = ProviderChainSnapshot.PROVIDER_IDS.map { providerId ->
                    val registration = catalog.requireProviderChain(ProviderChainSnapshot.CHAIN_ID)
                        .single { it.providerId == providerId }
                    ExternalProviderConsentRequest(
                        providerId = providerId,
                        dataCategories = registration.dataCategories.map(DataCategory::id).sorted(),
                        retentionDisclosureFingerprint = requireNotNull(registration.consentDisclosureFingerprint()),
                    )
                },
            ),
        )
        val now = Instant.parse("2026-10-14T12:00:00Z")
        val runId = UUID.randomUUID()
        val entryId = UUID.randomUUID()
        val authorization = ReferenceResolutionChainAuthorization(runId, entryId, UUID.randomUUID(), 1, now.plusSeconds(30))
        val attemptService = Mockito.mock(ReferenceResolutionAttemptService::class.java)
        val chainService = Mockito.mock(ReferenceResolutionChainService::class.java)
        Mockito.`when`(chainService.renew(authorization)).thenAnswer { invocation ->
            val current = invocation.getArgument<ReferenceResolutionChainAuthorization>(0)
            ReferenceResolutionChainAuthorization(
                current.analysisRunId,
                current.bibliographyEntryId,
                current.ownerToken,
                current.ownerFence,
                current.leaseExpiresAt.plusSeconds(30),
            )
        }
        Mockito.`when`(attemptService.databaseNow()).thenReturn(now)
        Mockito.`when`(attemptService.mayReadCachedCrossrefLookup(authorization)).thenReturn(true)
        Mockito.`when`(attemptService.lookupResults(runId, entryId)).thenReturn(emptyList())
        Mockito.`when`(attemptService.summary(runId, entryId)).thenReturn(emptyList())
        val reference = BibliographyReference(
            title = "A matching scholarly paper",
            authors = listOf("Ada Researcher"),
            year = 2024,
            doi = "https://doi.org/10.1234/PAPER",
            referenceType = "JOURNAL_ARTICLE",
        )
        return Fixture(
            configuration = configuration,
            callGate = ProviderCallGate(catalog),
            attemptService = attemptService,
            chainService = chainService,
            authorization = authorization,
            reference = reference,
            now = now,
        )
    }

    private data class Fixture(
        val configuration: com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot,
        val callGate: ProviderCallGate,
        val attemptService: ReferenceResolutionAttemptService,
        val chainService: ReferenceResolutionChainService,
        val authorization: ReferenceResolutionChainAuthorization,
        val reference: BibliographyReference,
        val now: Instant,
        var terminal: ProviderChainTerminalResult? = null,
        var finalAttemptMetrics: ReferenceResolutionAttemptMetrics? = null,
        var crossrefAttemptMetrics: ReferenceResolutionAttemptMetrics? = null,
    ) {
        fun coordinator(
            cache: CrossrefChainLookupCache,
            egress: ProviderEgressPermitGate,
            factories: List<ScholarlyMetadataLookupFactory>,
            sourceDocumentEgressHandoffLock: SourceDocumentEgressHandoffLock = SourceDocumentEgressHandoffLock { AutoCloseable {} },
        ) = ReferenceResolutionProviderChainCoordinator(
            callGate,
            egress,
            cache,
            attemptService,
            chainService,
            factories,
            ProviderChainRetryDelaySource { it.retryDelayMinMillis.toLong() },
            sourceDocumentEgressHandoffLock,
        )

        fun recordLeaseRenewal(events: MutableList<String>) {
            Mockito.doAnswer { invocation ->
                events += "renew"
                val current = invocation.getArgument<ReferenceResolutionChainAuthorization>(0)
                ReferenceResolutionChainAuthorization(
                    current.analysisRunId,
                    current.bibliographyEntryId,
                    current.ownerToken,
                    current.ownerFence,
                    current.leaseExpiresAt.plusSeconds(30),
                )
            }.`when`(chainService).renew(authorization)
        }

        fun reservation(
            providerId: String,
            lookupOrdinal: Int,
            providerAttemptOrdinal: Int = 1,
        ): ReferenceResolutionAttemptReservation =
            ReferenceResolutionAttemptReservation(
                attemptId = UUID.randomUUID(),
                analysisRunId = authorization.analysisRunId,
                bibliographyEntryId = authorization.bibliographyEntryId,
                providerId = providerId,
                lookupOrdinal = lookupOrdinal,
                reservationOrdinal = if (providerId == ProviderChainSnapshot.CROSSREF_PROVIDER_ID) providerAttemptOrdinal else 2,
                providerAttemptOrdinal = providerAttemptOrdinal,
                ownerToken = authorization.ownerToken,
                ownerFence = authorization.ownerFence,
                leaseExpiresAt = authorization.leaseExpiresAt,
                reservedAt = now,
                deadlineAt = now.plusSeconds(80),
            )

        fun stubReserved(reservation: ReferenceResolutionAttemptReservation, events: MutableList<String>) {
            Mockito.doAnswer {
                events += "reserve:${reservation.providerId}"
                ReferenceResolutionAttemptResult.Reserved(reservation)
            }.`when`(attemptService).reserveAttempt(
                authorization,
                reservation.providerId,
                reservation.lookupOrdinal,
                ProviderChainLimitsSnapshot.EXACT_DOI_V1,
            )
            Mockito.doAnswer {
                events += "start:${reservation.providerId}"
                ReferenceResolutionAttemptStartResult.Started
            }.`when`(attemptService).startAttempt(reservation, 25_000)
        }

        fun stubIntermediateCrossrefNotFound(
            reservation: ReferenceResolutionAttemptReservation,
            events: MutableList<String>,
        ) = stubIntermediateCrossrefOutcome(
            reservation,
            ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND,
            events,
        )

        fun stubIntermediateCrossrefOutcome(
            reservation: ReferenceResolutionAttemptReservation,
            outcome: ReferenceResolutionProviderLookupOutcomeCode,
            events: MutableList<String>,
        ) {
            Mockito.doAnswer { invocation ->
                crossrefAttemptMetrics = invocation.getArgument(2)
                events += "complete:crossref"
                true
            }.`when`(attemptService).completeAttemptAndRecordLookup(
                Mockito.eq(reservation) ?: reservation,
                Mockito.eq(outcome) ?: outcome,
                Mockito.any(ReferenceResolutionAttemptMetrics::class.java) ?: ReferenceResolutionAttemptMetrics(),
            )
        }

        fun stubFinalAttemptCommit(events: MutableList<String>) {
            Mockito.doAnswer { invocation ->
                finalAttemptMetrics = invocation.getArgument(5)
                invocation.getArgument<() -> Unit>(4).invoke()
                events += "ledger-commit"
                true
            }.`when`(attemptService).completeAttemptAndFinalizeWithResult(
                Mockito.any(ReferenceResolutionAttemptReservation::class.java) ?: reservation("crossref", 1),
                Mockito.any(ReferenceResolutionAttemptOutcomeCode::class.java) ?: ReferenceResolutionAttemptOutcomeCode.FOUND,
                Mockito.any(ReferenceResolutionChainOutcome::class.java) ?: ReferenceResolutionChainOutcome.RESOLVED,
                Mockito.nullable(String::class.java),
                anyCallback(),
                Mockito.any(ReferenceResolutionAttemptMetrics::class.java) ?: ReferenceResolutionAttemptMetrics(),
                Mockito.isNull(ReferenceResolutionNextActionWindow::class.java),
            )
        }

        fun stubNoAttemptFinalization(events: MutableList<String>) {
            Mockito.doAnswer { invocation ->
                invocation.getArgument<() -> Unit>(3).invoke()
                events += "ledger-commit"
                true
            }.`when`(attemptService).finalizeChainWithResult(
                Mockito.any(ReferenceResolutionChainAuthorization::class.java) ?: authorization,
                Mockito.any(ReferenceResolutionChainOutcome::class.java) ?: ReferenceResolutionChainOutcome.UNKNOWN,
                Mockito.nullable(String::class.java),
                anyCallback(),
            )
        }
    }

    private class RecordingLookup(
        private val providerId: String,
        private val events: MutableList<String>,
    ) {
        val doiQueries = mutableListOf<String>()
        val titleQueries = mutableListOf<String>()
        val titleCandidateLimits = mutableListOf<Int>()
        var doiResponse: ScholarlyWork? = null
        var doiFailure: ScholarlyMetadataProviderFailure? = null
        var titleResponse: List<ScholarlyWork> = emptyList()
        var titleFailure: ScholarlyMetadataProviderFailure? = null

        fun factory(): ScholarlyMetadataLookupFactory = object : ScholarlyMetadataLookupFactory {
            override val providerId: String = this@RecordingLookup.providerId
            override fun forRun(configuration: com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot): ScholarlyMetadataLookup =
                this@RecordingLookup.asLookup()
        }

        private fun asLookup(): ScholarlyMetadataLookup = object : ScholarlyMetadataLookup, ProviderChainMetadataLookup {
            override fun byDoi(doi: String): ScholarlyWork? = doiResponse

            override fun search(reference: BibliographyReference): List<ScholarlyWork> = emptyList()

            override fun byDoiForChain(
                doi: String,
                beforeWire: () -> Unit,
            ): ProviderChainMetadataResponse<ScholarlyWork?> {
                doiQueries += doi
                beforeWire()
                events += "http:$providerId"
                doiFailure?.let { throw it }
                return ProviderChainMetadataResponse(doiResponse, 200, 42)
            }

            override fun searchByTitleForChain(
                title: String,
                candidateLimit: Int,
                beforeWire: () -> Unit,
            ): ProviderChainMetadataResponse<List<ScholarlyWork>> {
                titleQueries += title
                titleCandidateLimits += candidateLimit
                beforeWire()
                events += "http:$providerId"
                titleFailure?.let { throw it }
                return ProviderChainMetadataResponse(titleResponse, 200, 42)
            }
        }
    }

    private class RecordingCache(
        private val events: MutableList<String>,
        private val cachedLookup: CrossrefChainLookup? = null,
    ) : CrossrefChainLookupCache {
        var reads = 0
        override fun find(
            configuration: com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot,
            query: CrossrefChainLookupQuery,
        ): CrossrefChainLookup? {
            reads++
            events += "cache-read"
            return cachedLookup
        }

        override fun store(
            configuration: com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot,
            query: CrossrefChainLookupQuery,
            lookup: CrossrefChainLookup,
        ) {
            events += "cache-store:${lookup.outcomeCode}"
        }
    }

    private class RecordingEgressGate(
        private val events: MutableList<String>,
        private val unavailable: Boolean = false,
    ) : ProviderEgressPermitGate {
        override fun acquire(providerId: String, deadlineAt: Instant): ProviderEgressPermitGate.AcquireResult {
            events += "permit:$providerId"
            if (unavailable) return ProviderEgressPermitGate.AcquireResult.Unavailable
            return ProviderEgressPermitGate.AcquireResult.Granted(object : ProviderEgressPermitGate.Permit {
                override fun release(): ProviderEgressPermitGate.ReleaseResult {
                    events += "release:$providerId"
                    return ProviderEgressPermitGate.ReleaseResult.RELEASED
                }
            })
        }
    }

    companion object {
        private const val CROSSREF_DISCLOSURE = "Crossref public scholarly metadata test disclosure."
        private const val DATACITE_DISCLOSURE = "DataCite public scholarly metadata test disclosure."
    }
}
