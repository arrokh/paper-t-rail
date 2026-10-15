package com.papertrail.api.scholarly.references.service

import com.papertrail.api.analysis.configuration.AnalysisConfigurationSnapshot
import com.papertrail.api.analysis.configuration.ProviderChainSnapshot
import com.papertrail.api.document.domain.SourceDocumentDeletedException
import com.papertrail.api.document.service.SourceDocumentEgressHandoffLock
import com.papertrail.api.external.crossref.CrossrefChainLookup
import com.papertrail.api.external.crossref.CrossrefChainLookupCache
import com.papertrail.api.external.crossref.CrossrefChainLookupQuery
import com.papertrail.api.infrastructure.providers.DataCategory
import com.papertrail.api.infrastructure.providers.ProviderCallGate
import com.papertrail.api.infrastructure.providers.ProviderCallPayload
import com.papertrail.api.infrastructure.providers.ProviderCallRejectedException
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
import com.papertrail.api.scholarly.references.attempts.domain.ReferenceResolutionProviderLookupSource
import com.papertrail.api.scholarly.references.attempts.service.ReferenceResolutionAttemptService
import com.papertrail.api.scholarly.references.attempts.service.ReferenceResolutionChainService
import com.papertrail.api.scholarly.references.client.BibliographyReference
import com.papertrail.api.scholarly.references.client.ProviderChainMetadataLookup
import com.papertrail.api.scholarly.references.client.ProviderChainMetadataResponse
import com.papertrail.api.scholarly.references.client.ProviderRetryDelayPolicy
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookup
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataLookupFactory
import com.papertrail.api.scholarly.references.client.ScholarlyMetadataProviderFailure
import com.papertrail.api.scholarly.references.client.ScholarlyWork
import com.papertrail.api.scholarly.references.normalization.DoiNormalizer
import com.papertrail.api.scholarly.references.resolver.ConservativeReferenceResolver
import com.papertrail.api.scholarly.references.resolver.ReferenceResolutionDecision
import com.papertrail.api.scholarly.references.resolver.ReferenceResolutionStatus
import com.papertrail.api.scholarly.references.resolver.ScholarlyMetadataMatcher
import com.papertrail.api.utils.JsonUtil
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class ReferenceResolutionProviderChainCoordinator(
    private val callGate: ProviderCallGate,
    private val egressPermitGate: ProviderEgressPermitGate,
    private val crossrefCache: CrossrefChainLookupCache,
    private val attemptService: ReferenceResolutionAttemptService,
    private val chainService: ReferenceResolutionChainService,
    private val lookupFactories: List<ScholarlyMetadataLookupFactory>,
    private val retryDelaySource: ProviderChainRetryDelaySource,
    private val sourceDocumentEgressHandoffLock: SourceDocumentEgressHandoffLock,
) {
    fun resolveAndFinalize(
        authorization: ReferenceResolutionChainAuthorization,
        configuration: AnalysisConfigurationSnapshot,
        reference: BibliographyReference,
        recoveredReservation: ReferenceResolutionAttemptReservation? = null,
        uncertainAttempt: ReferenceResolutionAttemptReservation? = null,
        persistTerminalResult: (ProviderChainTerminalResult) -> Unit,
    ): ProviderChainResolutionDisposition {
        val chain = configuration.referenceResolution.providerChain
            ?: throw IllegalArgumentException("A provider-chain snapshot is required for chain resolution.")
        if (uncertainAttempt != null) {
            val terminal = failedTerminal(ReferenceResolutionChainOutcome.UNKNOWN, "PROVIDER_OUTCOME_UNKNOWN")
            val committed = attemptService.finalizeAbandonedAttemptWithResult(
                authorization = authorization,
                reservation = uncertainAttempt,
            ) { persistTerminalResult(terminal) }
            return if (committed) ProviderChainResolutionDisposition.Finalized
            else ProviderChainResolutionDisposition.Deferred(authorization.leaseExpiresAt)
        }
        val matcher = ScholarlyMetadataMatcher(
            threshold = requireNotNull(configuration.referenceResolution.confidenceThreshold),
            ambiguityMargin = ScholarlyMetadataMatcher.AMBIGUITY_MARGIN,
            policyVersion = chain.policyVersion,
        )
        val preflightResolver = ConservativeReferenceResolver(EmptyMetadataLookup, matcher)
        val preflight = preflightResolver.preflight(reference)
        if (preflight != null) {
            val terminal = ProviderChainTerminalResult(preflight, outcomeForPreflight(preflight), null)
            return if (recoveredReservation == null) {
                finalizeWithoutAttempt(authorization, terminal, persistTerminalResult)
            } else {
                finalizeReturned(
                    authorization,
                    terminal,
                    AttemptCompletion(
                        recoveredReservation,
                        ReferenceResolutionAttemptOutcomeCode.NOT_SENT,
                        ReferenceResolutionAttemptMetrics(),
                    ),
                    persistTerminalResult,
                )
            }
        }

        val doi = normalizedDoi(reference, chain.policyVersion)
        val crossrefQuery = doi?.let(CrossrefChainLookupQuery::doi)
            ?: CrossrefChainLookupQuery.title(requireNotNull(reference.title))
        val crossrefLookup = chainLookup(ProviderChainSnapshot.CROSSREF_PROVIDER_ID, configuration)
            ?: return finalizeWithoutAttempt(
                authorization,
                failedTerminal(ReferenceResolutionChainOutcome.CONSENT_OR_CONFIG_REJECTED, "PROVIDER_CONFIGURATION_UNAVAILABLE"),
                persistTerminalResult,
            )

        return when (val crossref = lookup(
            authorization = authorization,
            configuration = configuration,
            providerId = ProviderChainSnapshot.CROSSREF_PROVIDER_ID,
            lookupOrdinal = 1,
            query = if (doi == null) ChainQuery.Title(requireNotNull(reference.title)) else ChainQuery.Doi(doi),
            cacheQuery = crossrefQuery,
            provider = crossrefLookup,
            recoveredReservation = recoveredReservation,
        )) {
            is LookupExecution.Deferred -> ProviderChainResolutionDisposition.Deferred(crossref.until)
            is LookupExecution.Failed -> finalizeFailure(authorization, crossref, persistTerminalResult)
            is LookupExecution.Returned -> resolveCrossrefResult(
                authorization,
                configuration,
                reference,
                matcher,
                doi,
                crossref,
                persistTerminalResult,
                recoveredReservation,
            )
        }
    }

    private fun resolveCrossrefResult(
        authorization: ReferenceResolutionChainAuthorization,
        configuration: AnalysisConfigurationSnapshot,
        reference: BibliographyReference,
        matcher: ScholarlyMetadataMatcher,
        doi: String?,
        crossref: LookupExecution.Returned,
        persistTerminalResult: (ProviderChainTerminalResult) -> Unit,
        recoveredReservation: ReferenceResolutionAttemptReservation?,
    ): ProviderChainResolutionDisposition {
        if (crossref.outcomeCode == ReferenceResolutionProviderLookupOutcomeCode.FOUND) {
            val decision = resolver(matcher, FixedMetadataLookup(crossref.works.firstOrNull(), crossref.works))
                .resolve(reference)
            val terminal = ProviderChainTerminalResult(
                decision = decision,
                outcome = outcomeForDecision(decision),
                metadataOriginProviderId = if (decision.work != null) ProviderChainSnapshot.CROSSREF_PROVIDER_ID else null,
            )
            return finalizeReturned(authorization, terminal, crossref.terminalAttempt, persistTerminalResult)
        }

        if (doi == null) {
            val decision = resolver(matcher, FixedMetadataLookup(null, emptyList())).resolve(reference)
            val outcome = when (crossref.outcomeCode) {
                ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND -> outcomeForDecision(decision)
                ReferenceResolutionProviderLookupOutcomeCode.UNUSABLE,
                ReferenceResolutionProviderLookupOutcomeCode.RETRYABLE_FAILURE,
                ReferenceResolutionProviderLookupOutcomeCode.PERMANENT_FAILURE -> ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE
                ReferenceResolutionProviderLookupOutcomeCode.FOUND -> error("Found Crossref result was already handled.")
            }
            val finalDecision = if (outcome == ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE) {
                operationalDecision("PROVIDER_UNAVAILABLE")
            } else {
                decision
            }
            return finalizeReturned(
                authorization,
                ProviderChainTerminalResult(finalDecision, outcome, null),
                crossref.terminalAttempt,
                persistTerminalResult,
            )
        }

        val dataCiteLookup = chainLookup(ProviderChainSnapshot.DATACITE_PROVIDER_ID, configuration)
            ?: return finalizeWithoutAttempt(
                authorization,
                failedTerminal(ReferenceResolutionChainOutcome.CONSENT_OR_CONFIG_REJECTED, "PROVIDER_CONFIGURATION_UNAVAILABLE"),
                persistTerminalResult,
            )
        val dataCite = when (val result = lookup(
            authorization = authorization,
            configuration = configuration,
            providerId = ProviderChainSnapshot.DATACITE_PROVIDER_ID,
            lookupOrdinal = 2,
            query = ChainQuery.Doi(doi),
            cacheQuery = null,
            provider = dataCiteLookup,
            recoveredReservation = recoveredReservation,
        )) {
            is LookupExecution.Deferred -> return ProviderChainResolutionDisposition.Deferred(result.until)
            is LookupExecution.Failed -> return finalizeFailure(authorization, result, persistTerminalResult)
            is LookupExecution.Returned -> result
        }

        if (dataCite.outcomeCode == ReferenceResolutionProviderLookupOutcomeCode.FOUND) {
            val decision = resolver(matcher, FixedMetadataLookup(dataCite.works.firstOrNull(), emptyList()))
                .resolve(reference)
            val terminal = ProviderChainTerminalResult(
                decision = decision,
                outcome = outcomeForDecision(decision),
                metadataOriginProviderId = if (decision.work != null) ProviderChainSnapshot.DATACITE_PROVIDER_ID else null,
            )
            return finalizeReturned(authorization, terminal, dataCite.terminalAttempt, persistTerminalResult)
        }

        if (dataCite.outcomeCode != ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND) {
            return finalizeReturned(
                authorization,
                failedTerminal(ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE, "PROVIDER_UNAVAILABLE"),
                dataCite.terminalAttempt,
                persistTerminalResult,
            )
        }

        val noCandidate = resolver(matcher, FixedMetadataLookup(null, emptyList())).resolve(reference)
        val crossrefWasUnavailable = crossref.outcomeCode in setOf(
            ReferenceResolutionProviderLookupOutcomeCode.UNUSABLE,
            ReferenceResolutionProviderLookupOutcomeCode.RETRYABLE_FAILURE,
            ReferenceResolutionProviderLookupOutcomeCode.PERMANENT_FAILURE,
        )
        val outcome = if (crossrefWasUnavailable) {
            ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE
        } else {
            outcomeForDecision(noCandidate)
        }
        val decision = if (crossrefWasUnavailable) operationalDecision("PROVIDER_UNAVAILABLE") else noCandidate
        return finalizeReturned(
            authorization,
            ProviderChainTerminalResult(decision, outcome, null),
            dataCite.terminalAttempt,
            persistTerminalResult,
        )
    }

    private fun lookup(
        authorization: ReferenceResolutionChainAuthorization,
        configuration: AnalysisConfigurationSnapshot,
        providerId: String,
        lookupOrdinal: Int,
        query: ChainQuery,
        cacheQuery: CrossrefChainLookupQuery?,
        provider: ProviderChainMetadataLookup,
        recoveredReservation: ReferenceResolutionAttemptReservation?,
    ): LookupExecution {
        val previousLookup = attemptService.lookupResults(authorization.analysisRunId, authorization.bibliographyEntryId)
            .singleOrNull { it.lookupOrdinal == lookupOrdinal }
        if (previousLookup != null) {
            if (previousLookup.providerId != providerId) {
                return failedLookup(ReferenceResolutionChainOutcome.UNKNOWN, "PROVIDER_OUTCOME_UNKNOWN", unknown = true)
            }
            if (previousLookup.source == ReferenceResolutionProviderLookupSource.WIRE) {
                if (previousLookup.outcomeCode == ReferenceResolutionProviderLookupOutcomeCode.FOUND) {
                    return failedLookup(ReferenceResolutionChainOutcome.UNKNOWN, "PROVIDER_OUTCOME_UNKNOWN", unknown = true)
                }
                return LookupExecution.Returned(
                    outcomeCode = previousLookup.outcomeCode,
                    works = emptyList(),
                    source = previousLookup.source,
                    terminalAttempt = null,
                )
            }
            if (cacheQuery == null) {
                return failedLookup(ReferenceResolutionChainOutcome.UNKNOWN, "PROVIDER_OUTCOME_UNKNOWN", unknown = true)
            }
            if (previousLookup.outcomeCode == ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND) {
                if (!authorize(configuration, providerId, query)) {
                    return failedLookup(ReferenceResolutionChainOutcome.CONSENT_OR_CONFIG_REJECTED, "PROVIDER_CONSENT_OR_CONFIG_REJECTED")
                }
                return LookupExecution.Returned(previousLookup.outcomeCode, emptyList(), previousLookup.source, null)
            }
            if (previousLookup.outcomeCode != ReferenceResolutionProviderLookupOutcomeCode.FOUND) {
                return failedLookup(ReferenceResolutionChainOutcome.UNKNOWN, "PROVIDER_OUTCOME_UNKNOWN", unknown = true)
            }
            if (!authorize(configuration, providerId, query)) {
                return failedLookup(ReferenceResolutionChainOutcome.CONSENT_OR_CONFIG_REJECTED, "PROVIDER_CONSENT_OR_CONFIG_REJECTED")
            }
            if (!attemptService.mayReadCachedCrossrefLookup(authorization)) {
                return LookupExecution.Deferred(authorization.leaseExpiresAt)
            }
            val cached = safeCacheFind(configuration, cacheQuery)
            if (cached == null || cached.outcomeCode != previousLookup.outcomeCode) {
                return failedLookup(ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE, "PROVIDER_CACHE_RESULT_UNAVAILABLE")
            }
            return LookupExecution.Returned(cached.outcomeCode, cached.works, previousLookup.source, null)
        }

        if (providerId == ProviderChainSnapshot.CROSSREF_PROVIDER_ID) {
            if (!authorize(configuration, providerId, query)) {
                return failedLookup(ReferenceResolutionChainOutcome.CONSENT_OR_CONFIG_REJECTED, "PROVIDER_CONSENT_OR_CONFIG_REJECTED")
            }
            if (attemptService.mayReadCachedCrossrefLookup(authorization)) {
                val cached = cacheQuery?.let { safeCacheFind(configuration, it) }
                if (cached != null) {
                    if (!attemptService.recordCachedLookup(authorization, providerId, lookupOrdinal, cached.outcomeCode)) {
                        return LookupExecution.Deferred(authorization.leaseExpiresAt)
                    }
                    return LookupExecution.Returned(
                        cached.outcomeCode,
                        cached.works,
                        ReferenceResolutionProviderLookupSource.CACHE,
                        null,
                    )
                }
            }
        } else if (!authorize(configuration, providerId, query)) {
            return failedLookup(ReferenceResolutionChainOutcome.CONSENT_OR_CONFIG_REJECTED, "PROVIDER_CONSENT_OR_CONFIG_REJECTED")
        }

        val chain = requireNotNull(configuration.referenceResolution.providerChain)
        val reservation = recoveredReservation?.takeIf {
            it.providerId == providerId && it.lookupOrdinal == lookupOrdinal
        } ?: when (val reserved = attemptService.reserveAttempt(authorization, providerId, lookupOrdinal, chain.limits)) {
            is ReferenceResolutionAttemptResult.Reserved -> reserved.reservation
            is ReferenceResolutionAttemptResult.NoSend -> return when (reserved.reason) {
                ReferenceResolutionAttemptNoSendReason.BUDGET_EXHAUSTED,
                ReferenceResolutionAttemptNoSendReason.DEADLINE_EXHAUSTED -> failedLookup(
                    ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED,
                    "PROVIDER_CHAIN_BUDGET_EXHAUSTED",
                    reserved.reservation?.let { reservation ->
                        AttemptCompletion(
                            reservation,
                            ReferenceResolutionAttemptOutcomeCode.NOT_SENT,
                            ReferenceResolutionAttemptMetrics(),
                        )
                    },
                )
                ReferenceResolutionAttemptNoSendReason.RETRY_NOT_DUE -> LookupExecution.Deferred(
                    attemptService.summary(authorization.analysisRunId, authorization.bibliographyEntryId)
                        .lastOrNull()?.nextAttemptNotBefore ?: authorization.leaseExpiresAt,
                )
                ReferenceResolutionAttemptNoSendReason.UNKNOWN_OUTCOME -> failedLookup(
                    ReferenceResolutionChainOutcome.UNKNOWN,
                    "PROVIDER_OUTCOME_UNKNOWN",
                    unknown = true,
                )
                ReferenceResolutionAttemptNoSendReason.OWNERSHIP_LOST,
                ReferenceResolutionAttemptNoSendReason.LEASE_EXPIRED,
                ReferenceResolutionAttemptNoSendReason.EXISTING_ATTEMPT,
                ReferenceResolutionAttemptNoSendReason.EXISTING_RESERVATION,
                ReferenceResolutionAttemptNoSendReason.FINAL_RESOLUTION -> LookupExecution.Deferred(authorization.leaseExpiresAt)
                ReferenceResolutionAttemptNoSendReason.MISSING_ENTRY -> throw IllegalArgumentException(
                    "Bibliography Entry is not part of this Analysis Run.",
                )
                ReferenceResolutionAttemptNoSendReason.LOOKUP_ORDER_NOT_READY,
                ReferenceResolutionAttemptNoSendReason.LOOKUP_ALREADY_RECORDED -> failedLookup(
                    ReferenceResolutionChainOutcome.CONSENT_OR_CONFIG_REJECTED,
                    "PROVIDER_CHAIN_CONFIGURATION_REJECTED",
                )
            }
        }

        if (remainingDeadlineMillis(reservation) < REQUEST_BUDGET_MILLIS) {
            return failedLookup(
                ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED,
                "PROVIDER_CHAIN_BUDGET_EXHAUSTED",
                AttemptCompletion(reservation, ReferenceResolutionAttemptOutcomeCode.NOT_SENT, ReferenceResolutionAttemptMetrics()),
            )
        }
        val permit = when (val acquired = runCatching {
            egressPermitGate.acquire(providerId, reservation.deadlineAt)
        }.getOrElse { ProviderEgressPermitGate.AcquireResult.Unavailable }) {
            is ProviderEgressPermitGate.AcquireResult.Granted -> acquired.permit
            ProviderEgressPermitGate.AcquireResult.DeadlineExceeded -> {
                return failedLookup(
                    ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED,
                    "PROVIDER_CHAIN_BUDGET_EXHAUSTED",
                    AttemptCompletion(reservation, ReferenceResolutionAttemptOutcomeCode.NOT_SENT, ReferenceResolutionAttemptMetrics()),
                )
            }
            ProviderEgressPermitGate.AcquireResult.Unavailable -> {
                return failedLookup(
                    ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE,
                    "PROVIDER_UNAVAILABLE",
                    AttemptCompletion(reservation, ReferenceResolutionAttemptOutcomeCode.NOT_SENT, ReferenceResolutionAttemptMetrics()),
                )
            }
        }

        var startedAtNanos: Long? = null
        var retainPermitUntilCommit = false
        var documentLock: AutoCloseable? = null
        try {
            documentLock = sourceDocumentEgressHandoffLock.acquireForRun(authorization.analysisRunId)
            val response: ProviderChainMetadataResponse<List<ScholarlyWork>> = when (query) {
                is ChainQuery.Doi -> provider.byDoiForChain(query.value) {
                    startAttemptOrThrow(authorization, reservation, startedAtNanos = { startedAtNanos = System.nanoTime() })
                }.let { result ->
                    ProviderChainMetadataResponse(
                        value = listOfNotNull(result.value),
                        httpStatus = result.httpStatus,
                        responseBytes = result.responseBytes,
                    )
                }
                is ChainQuery.Title -> provider.searchByTitleForChain(
                    title = query.value,
                    candidateLimit = chain.limits.crossrefCandidateLimit,
                    beforeWire = {
                        startAttemptOrThrow(authorization, reservation, startedAtNanos = { startedAtNanos = System.nanoTime() })
                    },
                ).let { result -> result.copy(value = result.value.take(chain.limits.crossrefCandidateLimit)) }
            }
            val works = response.value
            val metrics = metrics(startedAtNanos, null, response)
            if (providerId == ProviderChainSnapshot.CROSSREF_PROVIDER_ID && cacheQuery != null) {
                val cacheableOutcome = if (works.isEmpty()) {
                    ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND
                } else {
                    ReferenceResolutionProviderLookupOutcomeCode.FOUND
                }
                runCatching {
                    crossrefCache.store(
                        configuration,
                        cacheQuery,
                        CrossrefChainLookup(cacheableOutcome, works, authorization.analysisRunId),
                    )
                }
            }
            val outcome = if (works.isEmpty()) {
                ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND
            } else {
                ReferenceResolutionProviderLookupOutcomeCode.FOUND
            }
            if (providerId == ProviderChainSnapshot.CROSSREF_PROVIDER_ID && lookupOrdinal == 1 &&
                query is ChainQuery.Doi && outcome == ReferenceResolutionProviderLookupOutcomeCode.NOT_FOUND
            ) {
                if (!attemptService.completeAttemptAndRecordLookup(reservation, outcome, metrics)) {
                    return LookupExecution.Deferred(authorization.leaseExpiresAt)
                }
                return LookupExecution.Returned(outcome, emptyList(), ReferenceResolutionProviderLookupSource.WIRE, null)
            }
            val execution = LookupExecution.Returned(
                outcome,
                works,
                ReferenceResolutionProviderLookupSource.WIRE,
                AttemptCompletion(reservation, ReferenceResolutionAttemptOutcomeCode.valueOf(outcome.name), metrics),
            )
            val committedExecution = execution.withPendingPermit(permit)
            if (committedExecution != null) {
                retainPermitUntilCommit = true
                return committedExecution
            }
            return execution
        } catch (deleted: SourceDocumentDeletedException) {
            throw deleted
        } catch (rejected: ProviderCallRejectedException) {
            if (startedAtNanos != null) {
                val execution = failedLookup(
                    ReferenceResolutionChainOutcome.UNKNOWN,
                    "PROVIDER_OUTCOME_UNKNOWN",
                    AttemptCompletion(reservation, ReferenceResolutionAttemptOutcomeCode.UNKNOWN, metrics(startedAtNanos, null)),
                    unknown = true,
                )
                val committedExecution = execution.withPendingPermit(permit)
                if (committedExecution != null) {
                    retainPermitUntilCommit = true
                    return committedExecution
                }
                return execution
            }
            return failedLookup(
                ReferenceResolutionChainOutcome.CONSENT_OR_CONFIG_REJECTED,
                "PROVIDER_CONSENT_OR_CONFIG_REJECTED",
                AttemptCompletion(reservation, ReferenceResolutionAttemptOutcomeCode.NOT_SENT, ReferenceResolutionAttemptMetrics()),
            )
        } catch (rejected: AttemptStartRejectedException) {
            return when (rejected.reason) {
                ReferenceResolutionAttemptNoSendReason.DEADLINE_EXHAUSTED,
                ReferenceResolutionAttemptNoSendReason.BUDGET_EXHAUSTED -> failedLookup(
                    ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED,
                    "PROVIDER_CHAIN_BUDGET_EXHAUSTED",
                    AttemptCompletion(reservation, ReferenceResolutionAttemptOutcomeCode.NOT_SENT, ReferenceResolutionAttemptMetrics()),
                )
                ReferenceResolutionAttemptNoSendReason.OWNERSHIP_LOST,
                ReferenceResolutionAttemptNoSendReason.LEASE_EXPIRED,
                ReferenceResolutionAttemptNoSendReason.EXISTING_ATTEMPT,
                ReferenceResolutionAttemptNoSendReason.EXISTING_RESERVATION,
                ReferenceResolutionAttemptNoSendReason.FINAL_RESOLUTION -> LookupExecution.Deferred(authorization.leaseExpiresAt)
                else -> failedLookup(ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE, "PROVIDER_UNAVAILABLE")
            }
        } catch (failure: ScholarlyMetadataProviderFailure) {
            val metrics = metrics(startedAtNanos, failure)
            if (startedAtNanos == null) {
                return failedLookup(
                    ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE,
                    "PROVIDER_UNAVAILABLE",
                    AttemptCompletion(reservation, ReferenceResolutionAttemptOutcomeCode.NOT_SENT, metrics),
                )
            }
            val execution = handleProviderFailure(
                authorization,
                configuration,
                reservation,
                query,
                providerId,
                failure,
                metrics,
            )
            val committedExecution = execution.withPendingPermit(permit)
            if (committedExecution != null) {
                retainPermitUntilCommit = true
                return committedExecution
            }
            return execution
        } catch (_: Exception) {
            if (startedAtNanos == null) {
                return failedLookup(
                    ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE,
                    "PROVIDER_UNAVAILABLE",
                    AttemptCompletion(reservation, ReferenceResolutionAttemptOutcomeCode.NOT_SENT, ReferenceResolutionAttemptMetrics()),
                )
            }
            val execution = failedLookup(
                ReferenceResolutionChainOutcome.UNKNOWN,
                "PROVIDER_OUTCOME_UNKNOWN",
                AttemptCompletion(reservation, ReferenceResolutionAttemptOutcomeCode.UNKNOWN, metrics(startedAtNanos, null)),
                unknown = true,
            )
            val committedExecution = execution.withPendingPermit(permit)
            if (committedExecution != null) {
                retainPermitUntilCommit = true
                return committedExecution
            }
            return execution
        } finally {
            documentLock?.let { runCatching { it.close() } }
            if (!retainPermitUntilCommit) releasePermit(permit)
        }
    }

    private fun handleProviderFailure(
        authorization: ReferenceResolutionChainAuthorization,
        configuration: AnalysisConfigurationSnapshot,
        reservation: ReferenceResolutionAttemptReservation,
        query: ChainQuery,
        providerId: String,
        failure: ScholarlyMetadataProviderFailure,
        metrics: ReferenceResolutionAttemptMetrics,
    ): LookupExecution {
        if (failure.outcomeCode == ReferenceResolutionAttemptOutcomeCode.UNKNOWN) {
            return failedLookup(
                ReferenceResolutionChainOutcome.UNKNOWN,
                "PROVIDER_OUTCOME_UNKNOWN",
                AttemptCompletion(reservation, ReferenceResolutionAttemptOutcomeCode.UNKNOWN, metrics),
                unknown = true,
            )
        }

        if (failure.outcomeCode == ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE) {
            val chain = requireNotNull(configuration.referenceResolution.providerChain)
            val retryWindowMillis = failure.retryAfterMillis ?: retryDelaySource.nextRetryDelayMillis(chain.limits)
            val window = ReferenceResolutionNextActionWindow(retryWindowMillis, REQUEST_BUDGET_MILLIS)
            val retriesUsed = attemptService.summary(authorization.analysisRunId, authorization.bibliographyEntryId)
                .count { it.providerAttemptOrdinal > 1 }
            val delay = if (retriesUsed < chain.limits.maxRetriesPerReference) {
                ProviderRetryDelayPolicy.chooseRetryDelayMillis(
                    retryAfterMillis = failure.retryAfterMillis,
                    jitteredFallbackMillis = retryWindowMillis,
                    remainingDeadlineMillis = remainingDeadlineMillis(reservation),
                    nextAttemptBudgetMillis = REQUEST_BUDGET_MILLIS,
                )
            } else {
                null
            }
            if (reservation.providerAttemptOrdinal == 1 && delay != null) {
                val retryAt = attemptService.databaseNow().plusMillis(delay)
                if (!attemptService.completeAttempt(
                        reservation,
                        ReferenceResolutionAttemptOutcomeCode.RETRYABLE_FAILURE,
                        metrics,
                        nextAttemptNotBefore = retryAt,
                    )
                ) return LookupExecution.Deferred(authorization.leaseExpiresAt)
                return LookupExecution.Deferred(retryAt)
            }

            if (reservation.providerId == ProviderChainSnapshot.CROSSREF_PROVIDER_ID && reservation.providerAttemptOrdinal == 2) {
                if (!attemptService.completeAttemptAndRecordLookup(
                        reservation,
                        ReferenceResolutionProviderLookupOutcomeCode.RETRYABLE_FAILURE,
                        metrics,
                    )
                ) return LookupExecution.Deferred(authorization.leaseExpiresAt)
                return LookupExecution.Returned(
                    ReferenceResolutionProviderLookupOutcomeCode.RETRYABLE_FAILURE,
                    emptyList(),
                    ReferenceResolutionProviderLookupSource.WIRE,
                    null,
                )
            }

            val outcome = if (reservation.providerAttemptOrdinal == 2) {
                ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE
            } else {
                ReferenceResolutionChainOutcome.BUDGET_OR_DEADLINE_EXHAUSTED
            }
            val reason = if (outcome == ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE) {
                "PROVIDER_UNAVAILABLE"
            } else {
                "PROVIDER_CHAIN_BUDGET_EXHAUSTED"
            }
            return failedLookup(
                outcome,
                reason,
                AttemptCompletion(reservation, failure.outcomeCode, metrics, window),
            )
        }

        if ((failure.outcomeCode == ReferenceResolutionAttemptOutcomeCode.UNUSABLE ||
                failure.outcomeCode == ReferenceResolutionAttemptOutcomeCode.PERMANENT_FAILURE) &&
            providerId == ProviderChainSnapshot.CROSSREF_PROVIDER_ID && query is ChainQuery.Doi
        ) {
            val lookupOutcome = when (failure.outcomeCode) {
                ReferenceResolutionAttemptOutcomeCode.UNUSABLE -> ReferenceResolutionProviderLookupOutcomeCode.UNUSABLE
                ReferenceResolutionAttemptOutcomeCode.PERMANENT_FAILURE -> ReferenceResolutionProviderLookupOutcomeCode.PERMANENT_FAILURE
                else -> error("Only non-retryable Crossref outcomes are persisted before DOI fallback.")
            }
            if (!attemptService.completeAttemptAndRecordLookup(reservation, lookupOutcome, metrics)) {
                return LookupExecution.Deferred(authorization.leaseExpiresAt)
            }
            return LookupExecution.Returned(
                lookupOutcome,
                emptyList(),
                ReferenceResolutionProviderLookupSource.WIRE,
                null,
            )
        }

        return failedLookup(
            ReferenceResolutionChainOutcome.PROVIDER_UNAVAILABLE,
            "PROVIDER_UNAVAILABLE",
            AttemptCompletion(reservation, failure.outcomeCode, metrics),
        )
    }

    private fun startAttemptOrThrow(
        authorization: ReferenceResolutionChainAuthorization,
        reservation: ReferenceResolutionAttemptReservation,
        startedAtNanos: () -> Unit,
    ) {
        if (remainingDeadlineMillis(reservation) < REQUEST_BUDGET_MILLIS) {
            throw AttemptStartRejectedException(ReferenceResolutionAttemptNoSendReason.DEADLINE_EXHAUSTED)
        }
        val renewedAuthorization = chainService.renew(authorization)
            ?: throw AttemptStartRejectedException(ReferenceResolutionAttemptNoSendReason.OWNERSHIP_LOST)
        authorization.refreshLeaseExpiry(renewedAuthorization.leaseExpiresAt)
        when (val started = attemptService.startAttempt(reservation, REQUEST_BUDGET_MILLIS)) {
            ReferenceResolutionAttemptStartResult.Started -> startedAtNanos()
            is ReferenceResolutionAttemptStartResult.NoSend -> throw AttemptStartRejectedException(started.reason)
        }
    }

    private fun finalizeFailure(
        authorization: ReferenceResolutionChainAuthorization,
        failure: LookupExecution.Failed,
        persistTerminalResult: (ProviderChainTerminalResult) -> Unit,
    ): ProviderChainResolutionDisposition {
        val terminal = failedTerminal(failure.outcome, failure.reasonCode)
        return if (failure.unknown) {
            finalizeUnknown(authorization, terminal, failure.completion, persistTerminalResult)
        } else {
            finalizeReturned(authorization, terminal, failure.completion, persistTerminalResult)
        }
    }

    private fun finalizeReturned(
        authorization: ReferenceResolutionChainAuthorization,
        terminal: ProviderChainTerminalResult,
        completion: AttemptCompletion?,
        persistTerminalResult: (ProviderChainTerminalResult) -> Unit,
    ): ProviderChainResolutionDisposition {
        val committed = try {
            if (completion == null) {
                attemptService.finalizeChainWithResult(authorization, terminal.outcome, terminal.metadataOriginProviderId) {
                    persistTerminalResult(terminal)
                }
            } else if (completion.outcomeCode == ReferenceResolutionAttemptOutcomeCode.UNKNOWN) {
                attemptService.markUnknownAndFinalizeWithResult(
                    reservation = completion.reservation,
                    persistTerminalResult = {
                        persistTerminalResult(terminal.copy(outcome = ReferenceResolutionChainOutcome.UNKNOWN, metadataOriginProviderId = null))
                    },
                    metrics = completion.metrics,
                )
            } else {
                attemptService.completeAttemptAndFinalizeWithResult(
                    reservation = completion.reservation,
                    attemptOutcomeCode = completion.outcomeCode,
                    chainOutcome = terminal.outcome,
                    metadataOriginProviderId = terminal.metadataOriginProviderId,
                    persistTerminalResult = { persistTerminalResult(terminal) },
                    metrics = completion.metrics,
                    nextActionWindow = completion.nextActionWindow,
                )
            }
        } finally {
            releasePermit(completion?.permit)
        }
        return if (committed) ProviderChainResolutionDisposition.Finalized
        else ProviderChainResolutionDisposition.Deferred(authorization.leaseExpiresAt)
    }

    private fun finalizeUnknown(
        authorization: ReferenceResolutionChainAuthorization,
        terminal: ProviderChainTerminalResult,
        completion: AttemptCompletion?,
        persistTerminalResult: (ProviderChainTerminalResult) -> Unit,
    ): ProviderChainResolutionDisposition {
        val committed = try {
            if (completion == null) {
                attemptService.finalizeChainWithResult(
                    authorization,
                    ReferenceResolutionChainOutcome.UNKNOWN,
                    null,
                ) { persistTerminalResult(terminal.copy(outcome = ReferenceResolutionChainOutcome.UNKNOWN, metadataOriginProviderId = null)) }
            } else {
                attemptService.markUnknownAndFinalizeWithResult(
                    reservation = completion.reservation,
                    persistTerminalResult = {
                        persistTerminalResult(terminal.copy(outcome = ReferenceResolutionChainOutcome.UNKNOWN, metadataOriginProviderId = null))
                    },
                    metrics = completion.metrics,
                )
            }
        } finally {
            releasePermit(completion?.permit)
        }
        return if (committed) ProviderChainResolutionDisposition.Finalized
        else ProviderChainResolutionDisposition.Deferred(authorization.leaseExpiresAt)
    }

    private fun finalizeWithoutAttempt(
        authorization: ReferenceResolutionChainAuthorization,
        terminal: ProviderChainTerminalResult,
        persistTerminalResult: (ProviderChainTerminalResult) -> Unit,
    ): ProviderChainResolutionDisposition = finalizeReturned(authorization, terminal, null, persistTerminalResult)

    private fun authorize(
        configuration: AnalysisConfigurationSnapshot,
        providerId: String,
        query: ChainQuery,
    ): Boolean = try {
        callGate.authorizeProviderChain(
            chainId = ProviderChainSnapshot.CHAIN_ID,
            providerId = providerId,
            payload = ProviderCallPayload(
                mapOf(DataCategory.BIBLIOGRAPHIC_METADATA to JsonUtil.toTree(query.payloadValue())),
            ),
            configuration = configuration,
        )
        true
    } catch (_: ProviderCallRejectedException) {
        false
    }

    private fun safeCacheFind(
        configuration: AnalysisConfigurationSnapshot,
        query: CrossrefChainLookupQuery,
    ): CrossrefChainLookup? = runCatching { crossrefCache.find(configuration, query) }.getOrNull()

    private fun chainLookup(
        providerId: String,
        configuration: AnalysisConfigurationSnapshot,
    ): ProviderChainMetadataLookup? = lookupFactories.singleOrNull { it.providerId == providerId }
        ?.forRun(configuration) as? ProviderChainMetadataLookup

    private fun resolver(
        matcher: ScholarlyMetadataMatcher,
        lookup: ScholarlyMetadataLookup,
    ) = ConservativeReferenceResolver(lookup, matcher)

    private fun normalizedDoi(reference: BibliographyReference, policyVersion: String): String? {
        val isLegacy = policyVersion == ScholarlyMetadataMatcher.LEGACY_POLICY_VERSION
        val supplied = if (isLegacy) {
            listOfNotNull(reference.doi?.takeIf(String::isNotBlank))
        } else {
            (reference.doiIdentifiers + listOfNotNull(reference.doi)).filter(String::isNotBlank)
        }
        return supplied.mapNotNull(DoiNormalizer::normalize).distinct().singleOrNull()
    }

    private fun remainingDeadlineMillis(reservation: ReferenceResolutionAttemptReservation): Long =
        runCatching { Duration.between(attemptService.databaseNow(), reservation.deadlineAt).toMillis() }
            .getOrDefault(0L)
            .coerceAtLeast(0)

    private fun metrics(
        startedAtNanos: Long?,
        failure: ScholarlyMetadataProviderFailure?,
        response: ProviderChainMetadataResponse<*>? = null,
    ): ReferenceResolutionAttemptMetrics = ReferenceResolutionAttemptMetrics(
        httpStatus = response?.httpStatus ?: failure?.httpStatus,
        durationMillis = startedAtNanos?.let { ((System.nanoTime() - it) / NANOS_PER_MILLISECOND).coerceAtLeast(0) },
        responseBytes = response?.responseBytes ?: failure?.responseBytes,
    )

    private fun outcomeForPreflight(decision: ReferenceResolutionDecision): ReferenceResolutionChainOutcome = when {
        decision.reasonCode in CONFLICT_REASONS -> ReferenceResolutionChainOutcome.CONFLICT_OR_AMBIGUITY
        decision.status == ReferenceResolutionStatus.UNSUPPORTED_REFERENCE_TYPE ||
            decision.reasonCode == "INSUFFICIENT_MATCH_METADATA" -> ReferenceResolutionChainOutcome.NOT_ATTEMPTED
        else -> ReferenceResolutionChainOutcome.NOT_ATTEMPTED
    }

    private fun outcomeForDecision(decision: ReferenceResolutionDecision): ReferenceResolutionChainOutcome = when {
        decision.status == ReferenceResolutionStatus.RESOLVED -> ReferenceResolutionChainOutcome.RESOLVED
        decision.reasonCode in CONFLICT_REASONS -> ReferenceResolutionChainOutcome.CONFLICT_OR_AMBIGUITY
        decision.status == ReferenceResolutionStatus.UNSUPPORTED_REFERENCE_TYPE -> ReferenceResolutionChainOutcome.NOT_ATTEMPTED
        else -> ReferenceResolutionChainOutcome.NO_CANDIDATE_OR_DOI_UNCONFIRMED
    }

    private fun failedTerminal(
        outcome: ReferenceResolutionChainOutcome,
        reasonCode: String,
    ) = ProviderChainTerminalResult(operationalDecision(reasonCode), outcome, null)

    private fun LookupExecution.withPendingPermit(
        permit: ProviderEgressPermitGate.Permit,
    ): LookupExecution? = when (this) {
        is LookupExecution.Returned -> terminalAttempt?.let { copy(terminalAttempt = it.copy(permit = permit)) }
        is LookupExecution.Failed -> completion?.let { copy(completion = it.copy(permit = permit)) }
        is LookupExecution.Deferred -> null
    }

    private fun releasePermit(permit: ProviderEgressPermitGate.Permit?) {
        permit?.let { runCatching { it.release() } }
    }

    private fun failedLookup(
        outcome: ReferenceResolutionChainOutcome,
        reasonCode: String,
        completion: AttemptCompletion? = null,
        unknown: Boolean = false,
    ) = LookupExecution.Failed(outcome, reasonCode, completion, unknown)

    private fun operationalDecision(reasonCode: String) = ReferenceResolutionDecision(
        status = ReferenceResolutionStatus.UNRESOLVED,
        reasonCode = reasonCode,
    )

    private fun ChainQuery.payloadValue(): Map<String, String> = when (this) {
        is ChainQuery.Doi -> mapOf("doi" to value)
        is ChainQuery.Title -> mapOf("bibliographicQuery" to value)
    }

    private sealed interface ChainQuery {
        data class Doi(val value: String) : ChainQuery
        data class Title(val value: String) : ChainQuery
    }

    private sealed interface LookupExecution {
        data class Returned(
            val outcomeCode: ReferenceResolutionProviderLookupOutcomeCode,
            val works: List<ScholarlyWork>,
            val source: ReferenceResolutionProviderLookupSource,
            val terminalAttempt: AttemptCompletion?,
        ) : LookupExecution

        data class Failed(
            val outcome: ReferenceResolutionChainOutcome,
            val reasonCode: String,
            val completion: AttemptCompletion? = null,
            val unknown: Boolean = false,
        ) : LookupExecution

        data class Deferred(val until: Instant) : LookupExecution
    }

    private data class AttemptCompletion(
        val reservation: ReferenceResolutionAttemptReservation,
        val outcomeCode: ReferenceResolutionAttemptOutcomeCode,
        val metrics: ReferenceResolutionAttemptMetrics,
        val nextActionWindow: ReferenceResolutionNextActionWindow? = null,
        val permit: ProviderEgressPermitGate.Permit? = null,
    )

    private class AttemptStartRejectedException(
        val reason: ReferenceResolutionAttemptNoSendReason,
    ) : RuntimeException("Provider attempt was not started.")

    private class FixedMetadataLookup(
        private val doiWork: ScholarlyWork?,
        private val searchWorks: List<ScholarlyWork>,
    ) : ScholarlyMetadataLookup {
        override fun byDoi(doi: String): ScholarlyWork? = doiWork
        override fun search(reference: BibliographyReference): List<ScholarlyWork> = searchWorks
    }

    companion object {
        private const val REQUEST_BUDGET_MILLIS = 25_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val CONFLICT_REASONS = setOf(
            "INVALID_IDENTIFIER",
            "CONFLICTING_DOI_IDENTIFIERS",
            "DOI_CONFLICT",
            "DOI_TITLE_CONFLICT",
            "DOI_AUTHOR_CONFLICT",
            "DOI_YEAR_CONFLICT",
            "TITLE_CONFLICT",
            "AMBIGUOUS_MATCH",
            "AUTHOR_CONFLICT",
            "YEAR_CONFLICT",
            "CANDIDATE_AUTHORS_MISSING",
            "CANDIDATE_YEAR_MISSING",
            "BIBLIOGRAPHIC_CONFLICT",
        )

        private val EmptyMetadataLookup = object : ScholarlyMetadataLookup {
            override fun byDoi(doi: String): ScholarlyWork? = null
            override fun search(reference: BibliographyReference): List<ScholarlyWork> = emptyList()
        }
    }
}
