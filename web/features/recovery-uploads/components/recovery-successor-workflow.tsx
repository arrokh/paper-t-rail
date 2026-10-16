"use client";

import Link from "next/link";
import { useMemo, useRef, useState } from "react";
import { ChevronDown } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { LocalDateTime } from "@/components/local-date-time";
import { isTerminalAnalysisRun, useAnalysisRun, useExecutionSummary } from "@/features/analysis-runs/queries/analysis-run-queries";
import type { AnalysisRun } from "@/features/analysis-runs/types";
import { analysisRunStatusLabel } from "@/features/analysis-runs/run-status";
import { useProviderDirectory } from "@/features/providers/provider-directory-query";
import {
  consentRequirements,
  providerSelectionsAreAvailable,
  type ProviderConsentRequirement,
  type ProviderSelections,
} from "@/features/providers/provider-configuration";
import { useRecoveryHistory, usePreviewRecoverySuccessor, useSubmitRecoverySuccessor } from "@/features/recovery-uploads/queries/recovery-upload-queries";
import type {
  RecoveryBatch,
  RecoveryHistoryEvent,
  RecoveryHistorySubmission,
  RecoverySuccessorPreview,
  RecoverySuccessorRunConfiguration,
  RecoverySuccessorSubmission,
} from "@/features/recovery-uploads/types";
import { cn } from "@/lib/utils";

type RecoverySuccessorWorkflowProps = {
  analysisRunId: string;
  predecessorConfiguration: AnalysisRun["configuration"];
  predecessorRunStatus: AnalysisRun["status"];
  batch: RecoveryBatch | null;
};

type ConsentDraft = {
  categories: Record<string, string[]>;
  fingerprints: Record<string, string>;
};

type SuccessorProviderSelection = {
  selections: ProviderSelections;
  providerChainId: string | null;
};

function providerSelectionsFromRun(configuration: AnalysisRun["configuration"]): SuccessorProviderSelection | null {
  const providerChainId = configuration.referenceResolution?.providerChain?.chainId ?? null;
  const scholarlyMetadataProvider = configuration.referenceResolution?.provider?.provider
    ?? configuration.referenceResolution?.providerChain?.providers[0]?.provider;
  const openAccessProvider = configuration.openAccess?.provider;
  if (!scholarlyMetadataProvider || !openAccessProvider) return null;

  return {
    providerChainId,
    selections: {
      claimExtractorProvider: configuration.claimExtractor.provider,
      embeddingProvider: configuration.embedding.provider,
      systemOneProvider: configuration.systemOne.provider,
      scholarlyMetadataProvider,
      openAccessProvider,
    },
  };
}

function createSuccessorConfiguration(
  predecessor: AnalysisRun["configuration"],
  selections: ProviderSelections,
  providerChainId: string | null,
  requirements: ProviderConsentRequirement[],
  consentDraft: ConsentDraft,
): RecoverySuccessorRunConfiguration {
  const externalProviderConsents = requirements.flatMap((provider) => {
    if (consentDraft.fingerprints[provider.providerId] !== provider.retentionDisclosureFingerprint) return [];
    const approvedCategories = consentDraft.categories[provider.providerId] ?? [];
    if (provider.dataCategories.some((category) => !approvedCategories.includes(category))) return [];
    return [{
      providerId: provider.providerId,
      dataCategories: [...provider.dataCategories],
      retentionDisclosureFingerprint: provider.retentionDisclosureFingerprint,
    }];
  });

  return {
    captureExecution: predecessor.captureExecution ?? true,
    ...selections,
    scholarlyMetadataProvider: providerChainId ? null : selections.scholarlyMetadataProvider,
    ...(providerChainId ? { providerChainId } : {}),
    externalProviderConsents,
  };
}

function eventLabel(event: RecoveryHistoryEvent): string {
  return event.type.replaceAll("_", " ").toLocaleLowerCase();
}

function submissionFromEvent(event: RecoveryHistoryEvent) {
  return event.submission?.successorAnalysisRunId ?? null;
}

export function RecoverySuccessorWorkflow({ analysisRunId, predecessorConfiguration, predecessorRunStatus, batch }: RecoverySuccessorWorkflowProps) {
  const providerQuery = useProviderDirectory(batch !== null);
  const historyQuery = useRecoveryHistory(analysisRunId, true);
  const previewMutation = usePreviewRecoverySuccessor();
  const submitMutation = useSubmitRecoverySuccessor();
  const [consentDraft, setConsentDraft] = useState<ConsentDraft>({ categories: {}, fingerprints: {} });
  const [previewConfigurationKey, setPreviewConfigurationKey] = useState<string | null>(null);
  const submissionKey = useRef<{ batchId: string; revision: number; configurationKey: string; planFingerprint: string; key: string } | null>(null);

  const providerSelection = useMemo(() => providerSelectionsFromRun(predecessorConfiguration), [predecessorConfiguration]);
  const selections = providerSelection?.selections ?? null;
  const providerChainId = providerSelection?.providerChainId ?? null;
  const directory = providerQuery.data ?? null;
  const selectionAvailable = directory && selections
    ? providerSelectionsAreAvailable(directory, selections, providerChainId)
    : false;
  const requirements = useMemo(
    () => directory && selections ? consentRequirements(directory, selections, providerChainId) : [],
    [directory, selections, providerChainId],
  );
  const missingConsents = requirements.filter((provider) =>
    consentDraft.fingerprints[provider.providerId] !== provider.retentionDisclosureFingerprint
      || provider.dataCategories.some((category) => !consentDraft.categories[provider.providerId]?.includes(category)),
  );
  const configuration = directory && selections
    ? createSuccessorConfiguration(predecessorConfiguration, selections, providerChainId, requirements, consentDraft)
    : null;
  const configurationKey = configuration ? JSON.stringify(configuration) : null;
  const events = historyQuery.data?.pages.flatMap((page) => page.events) ?? [];
  const historySuccessors = events.flatMap((event) => {
    const successorId = submissionFromEvent(event);
    return successorId ? [successorId] : [];
  });
  const submittedSuccessorIds = [...new Set([
    ...historySuccessors,
    ...(submitMutation.data ? [submitMutation.data.successorAnalysisRunId] : []),
  ])];
  const preview = previewMutation.data;
  const previewIsCurrent = Boolean(
    batch
      && preview
      && preview.batchId === batch.id
      && preview.predecessorAnalysisRunId === analysisRunId
      && preview.revision === batch.revision
      && previewConfigurationKey === configurationKey,
  );
  const hasWork = Boolean(batch && (batch.selectedAssets.length > 0 || batch.identifierAssistance.length > 0));
  const hasSubmitted = Boolean(batch && (
    batch.status === "SUBMITTED"
      || events.some((event) => event.batchId === batch.id && submissionFromEvent(event) !== null)
      || (submitMutation.data && submitMutation.variables?.batchId === batch.id)
  ));
  const canPreview = Boolean(batch
    && batch.status === "OPEN"
    && hasWork
    && selectionAvailable
    && missingConsents.length === 0
    && configuration !== null
    && !previewMutation.isPending
    && !submitMutation.isPending
    && !hasSubmitted);
  const locallyBlocked = !hasWork
    ? "Select an eligible exact PDF version or stage DOI-only assistance before previewing a successor."
    : !selectionAvailable
      ? "The predecessor provider configuration is incomplete or unavailable. No default provider will be substituted; preview is blocked."
      : missingConsents.length > 0
        ? "Review and explicitly approve every current provider category before previewing or submitting."
        : null;
  const shouldShowHistory = Boolean(batch || events.length > 0 || historyQuery.isPending || historyQuery.isError || submittedSuccessorIds.length > 0);

  function toggleCategory(provider: ProviderConsentRequirement, category: string, approved: boolean) {
    setConsentDraft((current) => {
      const sameDisclosure = current.fingerprints[provider.providerId] === provider.retentionDisclosureFingerprint;
      const categories = new Set(sameDisclosure ? current.categories[provider.providerId] ?? [] : []);
      if (approved) categories.add(category);
      else categories.delete(category);
      const nextCategories = { ...current.categories };
      const nextFingerprints = { ...current.fingerprints };
      if (categories.size > 0) {
        nextCategories[provider.providerId] = [...categories];
        nextFingerprints[provider.providerId] = provider.retentionDisclosureFingerprint;
      } else {
        delete nextCategories[provider.providerId];
        delete nextFingerprints[provider.providerId];
      }
      return { categories: nextCategories, fingerprints: nextFingerprints };
    });
  }

  function previewPlan() {
    if (!batch || !configuration || !canPreview) return;
    previewMutation.reset();
    setPreviewConfigurationKey(null);
    const requestConfiguration = configuration;
    previewMutation.mutate({
      analysisRunId,
      batchId: batch.id,
      expectedRevision: batch.revision,
      configuration: requestConfiguration,
    }, {
      onSuccess: () => setPreviewConfigurationKey(JSON.stringify(requestConfiguration)),
    });
  }

  function submitSuccessor() {
    if (!batch || !configuration || !previewIsCurrent || !preview?.eligible || hasSubmitted || !submissionAllowed(preview)) return;
    const requestConfigurationKey = JSON.stringify(configuration);
    if (!submissionKey.current
      || submissionKey.current.batchId !== batch.id
      || submissionKey.current.revision !== batch.revision
      || submissionKey.current.configurationKey !== requestConfigurationKey
      || submissionKey.current.planFingerprint !== preview.planFingerprint) {
      submissionKey.current = {
        batchId: batch.id,
        revision: batch.revision,
        configurationKey: requestConfigurationKey,
        planFingerprint: preview.planFingerprint,
        key: globalThis.crypto.randomUUID(),
      };
    }
    submitMutation.mutate({
      analysisRunId,
      batchId: batch.id,
      idempotencyKey: submissionKey.current.key,
      expectedRevision: batch.revision,
      configuration,
    });
  }

  const activeError = [previewMutation.error, submitMutation.error]
    .find((error): error is Error => error instanceof Error);

  if (!batch && !shouldShowHistory) return null;

  return (
    <section className="space-y-5" aria-labelledby={batch ? "successor-workflow-heading" : undefined}>
      {batch && (
      <Card className="shadow-sm">
        <CardHeader>
          <CardTitle id="successor-workflow-heading" className="font-serif text-lg">Review successor plan and provider consent</CardTitle>
          <CardDescription>
            This creates at most one immutable successor for this Recovery Batch. Exact selected PDFs and DOI-only identity assertions are separate inputs. Preview is read-only; provider work begins only after submission.
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-5">
          <p className="m-0 text-sm leading-relaxed text-muted-foreground">
            The predecessor report remains unchanged and visible in this page. Rights to stage a PDF are separate from provider consent for the successor.
          </p>
          {providerQuery.isPending && (
            <p className="m-0 flex items-center gap-2 text-sm text-muted-foreground" role="status"><Spinner /> Loading current provider disclosures…</p>
          )}
          {providerQuery.isError && (
            <Alert variant="destructive"><AlertTitle>Provider disclosures unavailable</AlertTitle><AlertDescription>{providerQuery.error instanceof Error ? providerQuery.error.message : "The current provider disclosures could not be loaded."} No preview or submission is available until current consent details can be shown.</AlertDescription></Alert>
          )}
          {directory && !selectionAvailable && (
            <Alert className="border-warning/40 bg-warning/20 text-warning-foreground"><AlertTitle>Successor configuration blocked</AlertTitle><AlertDescription>The predecessor provider settings are incomplete or unavailable. No default provider will be substituted, so successor preview is blocked.</AlertDescription></Alert>
          )}
          {directory && requirements.length === 0 && selectionAvailable && (
            <Alert className="border-info/40 bg-info/20 text-info-foreground"><AlertTitle>No external-provider approval is required by the selected configuration</AlertTitle><AlertDescription>The same provider selections as the predecessor will be used. Local providers still follow the existing successor configuration checks.</AlertDescription></Alert>
          )}
          {directory && requirements.length > 0 && (
            <section className="space-y-4 rounded-md border border-border bg-background p-4" aria-labelledby="successor-provider-consent-heading">
              <div className="space-y-1">
                <h3 id="successor-provider-consent-heading" className="m-0 text-sm font-semibold">Explicit consent for this successor</h3>
                <p className="m-0 text-sm leading-relaxed text-muted-foreground">Provider selections and any fixed provider chain stay the same as the predecessor. Re-approve these disclosures for this new run; previous consent and PDF-rights declarations do not carry over.</p>
              </div>
              <ul className="m-0 grid list-none gap-4 p-0">
                {requirements.map((provider) => (
                  <li key={provider.providerId} className="min-w-0 space-y-3 border-t border-border pt-3 first:border-t-0 first:pt-0">
                    <div className="space-y-1">
                      <h4 className="m-0 break-words text-sm font-semibold">{provider.displayName} · {provider.providerId}</h4>
                      <p className="m-0 text-xs text-muted-foreground">Data categories this provider may receive for the successor:</p>
                      {provider.retentionDisclosure && (
                        <p className="m-0 break-words rounded bg-warning p-3 text-sm leading-relaxed text-warning-foreground">{provider.retentionDisclosure}</p>
                      )}
                    </div>
                    <fieldset className="m-0 grid min-w-0 gap-3 border-0 p-0 sm:grid-cols-2" aria-label={`${provider.displayName} data categories`}>
                      {provider.dataCategories.map((category) => {
                        const disclosure = directory.dataCategories.find(({ id }) => id === category);
                        const id = `successor-consent-${encodeURIComponent(provider.providerId)}-${encodeURIComponent(category)}`;
                        return (
                          <Field key={category} orientation="horizontal" className="min-w-0 items-start gap-3">
                            <Checkbox
                              id={id}
                              checked={consentDraft.fingerprints[provider.providerId] === provider.retentionDisclosureFingerprint && (consentDraft.categories[provider.providerId] ?? []).includes(category)}
                              disabled={previewMutation.isPending || submitMutation.isPending || hasSubmitted}
                              onCheckedChange={(checked) => toggleCategory(provider, category, checked === true)}
                            />
                            <div className="min-w-0 space-y-1">
                              <FieldLabel htmlFor={id} className="text-sm font-medium">{disclosure?.label ?? category}</FieldLabel>
                              <FieldDescription className="break-words">{disclosure?.description ?? `Data category: ${category}`}</FieldDescription>
                            </div>
                          </Field>
                        );
                      })}
                    </fieldset>
                  </li>
                ))}
              </ul>
              <p className="m-0 text-xs leading-relaxed text-muted-foreground">Approval applies only to the named provider, listed categories, current disclosure, and this successor Analysis Run.</p>
            </section>
          )}

          {hasSubmitted && !hasWork ? (
            <p className="m-0 text-sm text-muted-foreground" role="status">This submitted batch is immutable. Use its content-minimized history to locate the successor Analysis Run.</p>
          ) : hasWork ? (
            <p className="m-0 text-sm" role="status">
              {batch.selectedAssets.length} current exact PDF selection{batch.selectedAssets.length === 1 ? "" : "s"} · {batch.identifierAssistance.length} DOI-only identity assertion{batch.identifierAssistance.length === 1 ? "" : "s"}.
              {batch.identifierAssistance.length > 0 ? " DOI-only work is reference-resolution assistance, not PDF parsing or claim assessment from a DOI." : ""}
            </p>
          ) : (
            <Alert className="border-warning/40 bg-warning/20 text-warning-foreground"><AlertTitle>No selected recovery work</AlertTitle><AlertDescription>{locallyBlocked}</AlertDescription></Alert>
          )}
          {locallyBlocked && hasWork && <p className="m-0 text-sm text-warning-foreground" role="status">Blocked: {locallyBlocked}</p>}
          {activeError && (
            <Alert variant="destructive"><AlertTitle>Successor action did not complete</AlertTitle><AlertDescription>{activeError.message}</AlertDescription></Alert>
          )}
          <div className="flex flex-wrap items-center gap-3">
            <Button type="button" disabled={!canPreview} onClick={previewPlan}>
              {previewMutation.isPending ? <><Spinner /> Preparing read-only preview…</> : "Preview successor plan"}
            </Button>
            {hasSubmitted && <Badge variant="outline" className="border-success-foreground/30 bg-success text-success-foreground">One successor has already been submitted for this batch</Badge>}
          </div>
          {previewMutation.isPending && <p className="m-0 text-sm text-muted-foreground" role="status">Checking current selections, compatibility, and consent. No provider calls are made by preview.</p>}
          {preview && !previewIsCurrent && (
            <Alert className="border-warning/40 bg-warning/20 text-warning-foreground"><AlertTitle>Preview needs to be refreshed</AlertTitle><AlertDescription>The batch selections or successor consent changed after this preview. Review consent again and request a new preview before submission.</AlertDescription></Alert>
          )}
          {previewIsCurrent && preview && <SuccessorPlanPreview preview={preview} />}
          {previewIsCurrent && preview && (
            <div className="flex flex-wrap items-center gap-3">
              <Button type="button" disabled={!preview.eligible || !submissionAllowed(preview) || hasSubmitted || submitMutation.isPending} onClick={submitSuccessor}>
                {submitMutation.isPending ? <><Spinner /> Submitting successor…</> : "Submit one immutable successor Analysis Run"}
              </Button>
              {!preview.eligible && <span className="text-sm text-warning-foreground">Submission is blocked until the listed plan blockers are resolved.</span>}
            </div>
          )}
          {submitMutation.isPending && <p className="m-0 text-sm text-muted-foreground" role="status">Submitting this selected subset once. Provider work is queued by the server after successful submission.</p>}
          {submitMutation.data && !historySuccessors.includes(submitMutation.data.successorAnalysisRunId) && (
            <SubmissionDetails submission={submitMutation.data} />
          )}
        </CardContent>
      </Card>
      )}
      {shouldShowHistory && (
        <>
          {historyQuery.isPending && <p className="m-0 flex items-center gap-2 text-sm text-muted-foreground" role="status"><Spinner /> Loading recovery and work history…</p>}
          {historyQuery.isError && (
            <Alert variant="destructive"><AlertTitle>Recovery history unavailable</AlertTitle><AlertDescription>{historyQuery.error instanceof Error ? historyQuery.error.message : "Content-minimized recovery history could not be loaded."}</AlertDescription></Alert>
          )}
          <RecoveryHistory
            analysisRunId={analysisRunId}
            events={events}
            isLoading={historyQuery.isPending}
            hasNextPage={historyQuery.hasNextPage}
            isFetchingNextPage={historyQuery.isFetchingNextPage}
            hasError={historyQuery.isError}
            onLoadMore={() => void historyQuery.fetchNextPage()}
            submittedSuccessorIds={submittedSuccessorIds}
            batchSubmitted={batch ? hasSubmitted : submittedSuccessorIds.length > 0}
            predecessorCaptureRequested={predecessorConfiguration.captureExecution}
            predecessorRunStatus={predecessorRunStatus}
          />
        </>
      )}
    </section>
  );
}

function submissionAllowed(preview: RecoverySuccessorPreview): boolean {
  return preview.selectedEntries.every((entry) => entry.blockers.length === 0)
    && preview.identifierAssistanceEntries.every((entry) => entry.blockers.length === 0);
}

function SuccessorPlanPreview({ preview }: { preview: RecoverySuccessorPreview }) {
  return (
    <section className="space-y-4 rounded-md border border-border bg-background p-4" aria-labelledby="successor-plan-heading">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="space-y-1">
          <h3 id="successor-plan-heading" className="m-0 text-base font-semibold">Successor plan preview</h3>
          <p className="m-0 text-sm text-muted-foreground">Batch revision {preview.revision} · preview makes no provider calls</p>
        </div>
        <Badge variant="outline" className={preview.eligible ? "border-success-foreground/30 bg-success text-success-foreground" : "border-warning/40 bg-warning text-warning-foreground"}>{preview.eligible ? "Ready to submit" : "Blocked"}</Badge>
      </div>
      {preview.selectedEntries.length > 0 && (
        <div className="space-y-2">
          <h4 className="m-0 text-sm font-semibold">Selected exact PDF versions</h4>
          <ul className="m-0 grid list-none gap-2 p-0">
            {preview.selectedEntries.map((entry) => (
              <li key={entry.uploadId} className="min-w-0 rounded border border-border p-3">
                <p className="m-0 break-words text-sm font-medium">Bibliography Entry · {entry.localReferenceKey}</p>
                <ul className="m-0 mt-2 grid list-none gap-1 p-0 text-xs text-muted-foreground">
                  {entry.stages.map((stage) => <li key={stage.stage}>{stage.stage}: {stage.disposition} · {stage.reasonCode}</li>)}
                </ul>
                {entry.blockers.length > 0 && <p className="mb-0 mt-2 text-sm text-warning-foreground">Blocked: {entry.blockers.join(" · ")}</p>}
              </li>
            ))}
          </ul>
        </div>
      )}
      {preview.identifierAssistanceEntries.length > 0 && (
        <div className="space-y-2">
          <h4 className="m-0 text-sm font-semibold">DOI-only reference-resolution work</h4>
          <ul className="m-0 grid list-none gap-2 p-0">
            {preview.identifierAssistanceEntries.map((entry) => (
              <li key={entry.predecessorBibliographyEntryId} className="min-w-0 rounded border border-border p-3">
                <p className="m-0 break-words text-sm font-medium">Bibliography Entry · {entry.localReferenceKey} · {entry.identifierType} {entry.identifierValue}</p>
                <p className="mb-0 mt-1 text-xs text-muted-foreground">Researcher-asserted DOI identity assistance; no PDF parsing, evidence retrieval, or claim assessment is inferred from the DOI.</p>
                <ul className="m-0 mt-2 grid list-none gap-1 p-0 text-xs text-muted-foreground">{entry.stages.map((stage) => <li key={stage.stage}>{stage.stage}: {stage.disposition} · {stage.reasonCode}</li>)}</ul>
                {entry.blockers.length > 0 && <p className="mb-0 mt-2 text-sm text-warning-foreground">Blocked: {entry.blockers.join(" · ")}</p>}
              </li>
            ))}
          </ul>
        </div>
      )}
      <p className="m-0 text-sm text-muted-foreground">{preview.retainedReferenceKeys.length} unaffected Bibliography Entry coverage item(s) remain retained · {preview.retainedDecision.stage}: {preview.retainedDecision.disposition} · {preview.retainedDecision.reasonCode}.</p>
      {preview.expectedProviderWork.length > 0 && (
        <div className="space-y-2">
          <h4 className="m-0 text-sm font-semibold">Provider work expected only after submission</h4>
          <ul className="m-0 grid list-none gap-2 p-0 text-sm">
            {preview.expectedProviderWork.map((work, index) => (
              <li key={`${work.stage}-${work.providerId}-${index}`} className="min-w-0 rounded border border-border p-3">
                <p className="m-0 break-words font-medium">{work.stage} · {work.providerId} · {work.trustBoundary}</p>
                <p className="mb-0 mt-1 break-words text-xs text-muted-foreground">Categories: {work.dataCategories.join(", ") || "none"}</p>
                {work.retentionDisclosure && <p className="mb-0 mt-1 break-words text-xs text-muted-foreground">{work.retentionDisclosure}</p>}
              </li>
            ))}
          </ul>
        </div>
      )}
      {preview.blockers.length > 0 && (
        <Alert className="border-warning/40 bg-warning/20 text-warning-foreground"><AlertTitle>Plan blockers</AlertTitle><AlertDescription><ul className="m-0 grid gap-1 pl-5">{preview.blockers.map((blocker) => <li key={blocker} className="break-words">{blocker}</li>)}</ul></AlertDescription></Alert>
      )}
      {preview.invalidationReasons.length > 0 && (
        <Collapsible className="group/invalidation-reasons rounded border border-border px-3 py-2 text-sm">
          <CollapsibleTrigger className="flex min-h-8 w-full items-center justify-between gap-2 py-1 text-left font-medium focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
            Why predecessor work cannot be reused unchanged
            <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[open]/invalidation-reasons:rotate-180 motion-reduce:transition-none" aria-hidden="true" />
          </CollapsibleTrigger>
          <CollapsibleContent className="border-t border-border pt-2">
            <ul className="mb-0 mt-2 grid gap-1 pl-5 text-muted-foreground">{preview.invalidationReasons.map((reason) => <li key={reason} className="break-words">{reason}</li>)}</ul>
          </CollapsibleContent>
        </Collapsible>
      )}
    </section>
  );
}

function RecoveryHistory({
  analysisRunId,
  events,
  isLoading,
  hasNextPage,
  isFetchingNextPage,
  hasError,
  onLoadMore,
  submittedSuccessorIds,
  batchSubmitted,
  predecessorCaptureRequested,
  predecessorRunStatus,
}: {
  analysisRunId: string;
  events: RecoveryHistoryEvent[];
  isLoading: boolean;
  hasNextPage: boolean;
  isFetchingNextPage: boolean;
  hasError: boolean;
  onLoadMore: () => void;
  submittedSuccessorIds: string[];
  batchSubmitted: boolean;
  predecessorCaptureRequested: boolean | undefined;
  predecessorRunStatus: AnalysisRun["status"];
}) {
  return (
    <section className="space-y-3 border-t border-border pt-4" aria-labelledby="recovery-history-heading">
      <div className="space-y-1">
        <h3 id="recovery-history-heading" className="m-0 text-base font-semibold">Recovery and work history</h3>
        <p className="m-0 text-sm leading-relaxed text-muted-foreground">Content-minimized provenance includes workflow and per-stage work attempts. Identifiers such as DOI values, filenames, hashes, and temporary upload links are not shown in this history.</p>
      </div>
      {events.length > 0 ? (
        <ol className="m-0 grid list-none gap-2 p-0">
          {events.map((event) => (
            <li key={event.id} className="min-w-0 rounded border border-border bg-background p-3">
              <div className="flex flex-wrap items-start justify-between gap-2">
                <p className="m-0 break-words text-sm font-medium">{eventLabel(event)}</p>
                <span className="shrink-0 text-xs text-muted-foreground"><LocalDateTime value={event.createdAt} /></span>
              </div>
              <p className="m-0 mt-1 break-words text-xs text-muted-foreground">
                {event.outcomeCode ? `Outcome ${event.outcomeCode}` : "Outcome not applicable"}
                {event.stageCode ? ` · stage ${event.stageCode}` : ""}
                {event.attemptNumber !== null ? ` · attempt ${event.attemptNumber}` : ""}
                {event.batchStatus ? ` · batch ${event.batchStatus}` : ""}
              </p>
              {event.bibliographyEntryId && <p className="m-0 mt-1 break-all font-mono text-xs text-muted-foreground">Bibliography Entry ID · {event.bibliographyEntryId}</p>}
              {event.uploadId && <p className="m-0 mt-1 break-all font-mono text-xs text-muted-foreground">Recovery Upload ID · {event.uploadId}</p>}
              {event.previousUploadId && <p className="m-0 mt-1 break-all font-mono text-xs text-muted-foreground">Previous Recovery Upload ID · {event.previousUploadId}</p>}
              {event.submission && <SubmissionDetails submission={event.submission} />}
            </li>
          ))}
        </ol>
      ) : !isLoading && !hasError ? (
        <p className="m-0 text-sm text-muted-foreground">No recovery workflow events are recorded for this predecessor run yet.</p>
      ) : null}
      {batchSubmitted && submittedSuccessorIds.length === 0 && hasNextPage && <p className="m-0 text-sm text-muted-foreground" role="status">The submitted successor is not in this history page. Load older events to locate its immutable run ID.</p>}
      {hasNextPage && (
        <Button type="button" variant="outline" size="sm" disabled={isFetchingNextPage} onClick={onLoadMore}>
          {isFetchingNextPage ? <><Spinner /> Loading older events…</> : batchSubmitted && submittedSuccessorIds.length === 0 ? "Load older history to find successor run" : "Load older history events"}
        </Button>
      )}
      {submittedSuccessorIds.map((successorId) => <SuccessorRunReference key={successorId} analysisRunId={successorId} />)}
      <PredecessorRunReference analysisRunId={analysisRunId} captureExecution={predecessorCaptureRequested} runStatus={predecessorRunStatus} />
    </section>
  );
}

function SubmissionDetails({ submission }: {
  submission: Omit<RecoveryHistorySubmission, "status">
    & Partial<Pick<RecoveryHistorySubmission, "status">>
    & Partial<Pick<RecoverySuccessorSubmission, "queuedEntryCount" | "retainedEntryCount">>;
}) {
  const counts = [
    `${submission.selectedEntryCount} exact PDF ${submission.selectedEntryCount === 1 ? "entry" : "entries"}`,
    `${submission.identifierAssistanceEntryCount} DOI-only ${submission.identifierAssistanceEntryCount === 1 ? "entry" : "entries"}`,
    `${submission.resolutionQueuedEntryCount} reference-resolution ${submission.resolutionQueuedEntryCount === 1 ? "item" : "items"} queued`,
  ];
  if (submission.queuedEntryCount !== undefined) counts.push(`${submission.queuedEntryCount} selected-PDF indexing ${submission.queuedEntryCount === 1 ? "item" : "items"} queued`);
  if (submission.retainedEntryCount !== undefined) counts.push(`${submission.retainedEntryCount} unaffected ${submission.retainedEntryCount === 1 ? "entry" : "entries"} retained`);

  return (
    <div className="mt-3 rounded border border-border bg-muted/40 p-3">
      <p className="m-0 text-sm font-medium">Immutable successor · {submission.status ?? "submitted"}</p>
      <p className="m-0 mt-1 break-words text-xs text-muted-foreground">Successor Analysis Run ID · <Link className="font-mono underline underline-offset-4 focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50" href={`/analysis-runs/${encodeURIComponent(submission.successorAnalysisRunId)}`}>{submission.successorAnalysisRunId}</Link></p>
      <p className="mb-0 mt-2 text-xs leading-relaxed text-muted-foreground">{counts.join(" · ")}.</p>
    </div>
  );
}

function PredecessorRunReference({ analysisRunId, captureExecution, runStatus }: {
  analysisRunId: string;
  captureExecution: boolean | undefined;
  runStatus: AnalysisRun["status"];
}) {
  const terminalRun = isTerminalAnalysisRun({ status: runStatus });
  const executionQuery = useExecutionSummary(analysisRunId, captureExecution !== false && terminalRun, terminalRun);
  const summary = executionQuery.data;
  const traceAvailable = captureExecution !== false
    && Boolean(summary && summary.recordingState !== "NOT_RECORDED" && summary.completeness !== "NOT_RECORDED");

  return (
    <p className="m-0 flex flex-wrap items-center gap-x-2 gap-y-1 text-xs text-muted-foreground">
      <span>Predecessor Analysis Run:</span>
      <Link aria-label={`Open predecessor Analysis Run report ${analysisRunId}`} className="font-mono underline underline-offset-4 focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50" href={`/analysis-runs/${encodeURIComponent(analysisRunId)}?view=pipeline`}>{analysisRunId}</Link>
      {traceAvailable ? (
        <Link className="underline underline-offset-4 focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50" href={`/analysis-runs/${encodeURIComponent(analysisRunId)}?view=execution`}>Open predecessor execution trace</Link>
      ) : captureExecution === false ? (
        <span>Execution capture was disabled for this Analysis Run; no predecessor trace link is available.</span>
      ) : summary?.recordingState === "NOT_RECORDED" || summary?.completeness === "NOT_RECORDED" ? (
        <span>The predecessor execution trace was not recorded or has since been removed; no trace link is available.</span>
      ) : executionQuery.isError ? (
        <span>Predecessor trace availability could not be confirmed.</span>
      ) : !terminalRun ? (
        <span>Predecessor execution trace availability is checked after the Analysis Run completes.</span>
      ) : (
        <span role="status">Checking predecessor execution capture…</span>
      )}
    </p>
  );
}

function SuccessorRunReference({ analysisRunId }: { analysisRunId: string }) {
  const runQuery = useAnalysisRun(analysisRunId);
  const run = runQuery.data;
  const executionQuery = useExecutionSummary(analysisRunId, Boolean(run), Boolean(run && isTerminalAnalysisRun(run)));
  const summary = executionQuery.data;
  const traceAvailable = run?.configuration.captureExecution !== false
    && Boolean(summary && summary.recordingState !== "NOT_RECORDED" && summary.completeness !== "NOT_RECORDED");

  return (
    <div className="mt-2 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs">
      {run ? (
        <span className="text-muted-foreground" role="status">Successor status: {analysisRunStatusLabel(run.status)}</span>
      ) : runQuery.isError ? (
        <span className="text-muted-foreground">Successor status unavailable; use its stable run ID to open the report.</span>
      ) : (
        <span className="text-muted-foreground" role="status">Loading successor run status…</span>
      )}
      <Link className={cn("underline underline-offset-4", "focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50")} href={`/analysis-runs/${encodeURIComponent(analysisRunId)}`}>Open successor Analysis Run report · {analysisRunId}</Link>
      {traceAvailable ? (
        <Link className={cn("underline underline-offset-4", "focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50")} href={`/analysis-runs/${encodeURIComponent(analysisRunId)}?view=execution`}>Open this run&apos;s execution trace</Link>
      ) : run?.configuration.captureExecution === false || summary?.recordingState === "NOT_RECORDED" || summary?.completeness === "NOT_RECORDED" ? (
        <span className="text-muted-foreground">Execution capture was disabled or not recorded; no trace link is available.</span>
      ) : executionQuery.isError ? (
        <span className="text-muted-foreground">Execution trace availability could not be confirmed.</span>
      ) : (
        <span className="text-muted-foreground" role="status">Checking this run&apos;s execution capture…</span>
      )}
    </div>
  );
}
