"use client";

import { FormEvent, MouseEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowLeft,
  ArrowRight,
  ArrowUpRight,
  ChevronDown,
  FileText,
  LockKeyhole,
} from "lucide-react";
import type { AnalysisRun, AnalysisRunPage, ApiError, CreatedRun, ParsedDocument, ReferenceResolutionReportResponse } from "@/lib/types";
import {
  consentRequirements,
  createRunConfiguration,
  missingConsents,
  type ProviderDirectory,
  type ProviderRole,
  type ProviderSelections,
} from "@/lib/provider-configuration";
import { cn } from "@/lib/utils";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import {
  Field,
  FieldDescription,
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import {
  NativeSelect,
  NativeSelectOption,
} from "@/components/ui/native-select";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import { TabsContent } from "@/components/ui/tabs";
import { ReferenceResolutionBadge } from "@/components/reference-resolution-badge";
import { ReferenceResolutionEntryCard } from "@/components/reference-resolution-entry-card";
import { WorkflowStepTabs, type WorkflowStep } from "@/components/workflow-step-tabs";
import { formatConfidenceThreshold } from "@/lib/format-confidence-threshold";
import { scrollToAnchorTarget } from "@/lib/scroll-to-anchor";

const RUN_PAGE_SIZE = 25;

type AnalysisRunDetailTab = "progress" | "parsed" | "report";

const DEFAULT_SELECTIONS: ProviderSelections = {
  claimExtractorProvider: "heuristic",
  embeddingProvider: "local",
  systemOneProvider: "mock",
  scholarlyMetadataProvider: "recorded-fixtures",
};

const STATUS_CLASS_NAMES: Record<AnalysisRun["status"], string> = {
  QUEUED: "border-border bg-muted text-muted-foreground",
  PROCESSING: "border-primary/25 bg-primary/10 text-primary",
  PARSED: "border-primary/20 bg-primary/5 text-primary",
  COMPLETED: "border-primary/20 bg-primary/5 text-primary",
  COMPLETED_WITH_WARNINGS: "border-warning/40 bg-warning/10 text-warning-foreground",
  FAILED: "border-destructive/25 bg-destructive/10 text-destructive",
};

async function readError(response: Response): Promise<string> {
  try {
    const error = (await response.json()) as ApiError;
    return error.message || "The request was rejected.";
  } catch {
    return `Request failed (${response.status}).`;
  }
}

function statusLabel(status: AnalysisRun["status"]): string {
  return status.replaceAll("_", " ").toLowerCase();
}

function RunStatusBadge({ status }: { status: AnalysisRun["status"] }) {
  return (
    <Badge
      variant="outline"
      className={cn("shrink-0 capitalize", STATUS_CLASS_NAMES[status])}
    >
      {statusLabel(status)}
    </Badge>
  );
}

function isParsedDocumentReady(status: AnalysisRun["status"] | undefined): boolean {
  return status === "PARSED" || status === "COMPLETED" || status === "COMPLETED_WITH_WARNINGS";
}

function runProgressStep(status: AnalysisRun["status"] | undefined): Pick<WorkflowStep, "state" | "statusLabel"> {
  switch (status) {
    case "QUEUED":
      return { state: "waiting", statusLabel: "Queued" };
    case "PROCESSING":
      return { state: "in-progress", statusLabel: "In progress" };
    case "PARSED":
      return { state: "ready", statusLabel: "Parsed" };
    case "COMPLETED":
      return { state: "complete", statusLabel: "Done" };
    case "COMPLETED_WITH_WARNINGS":
      return { state: "complete", statusLabel: "Done with warnings" };
    case "FAILED":
      return { state: "failed", statusLabel: "Failed" };
    default:
      return { state: "waiting", statusLabel: "Select a run" };
  }
}

function scrollToDetails(element: HTMLElement | null) {
  element?.scrollIntoView({
    behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth",
    block: "start",
  });
}

function scrollToParsedDocumentTarget(event: MouseEvent<HTMLAnchorElement>) {
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;

  const targetId = decodeURIComponent(event.currentTarget.hash.slice(1));
  const target = document.getElementById(targetId);
  if (!target) return;

  event.preventDefault();
  scrollToAnchorTarget(target, event.currentTarget.hash);
}

function referenceResolutionAnchorId(referenceKey: string): string {
  return `reference-resolution-${referenceKey}`;
}

export function UploadDashboard() {
  const [runPage, setRunPage] = useState<AnalysisRunPage>({ items: [], nextCursor: null });
  const [pageCursors, setPageCursors] = useState<Array<string | null>>([null]);
  const [pageIndex, setPageIndex] = useState(0);
  const [activeDetailTab, setActiveDetailTab] = useState<AnalysisRunDetailTab>("progress");
  const [providerDirectory, setProviderDirectory] = useState<ProviderDirectory | null>(null);
  const [providerSelections, setProviderSelections] = useState<ProviderSelections>(DEFAULT_SELECTIONS);
  const [approvedCategories, setApprovedCategories] = useState<Record<string, string[]>>({});
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [providerError, setProviderError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [selectedFileName, setSelectedFileName] = useState<string | null>(null);
  const [parsedDocumentResult, setParsedDocumentResult] = useState<
    { runId: string; document: ParsedDocument } | { runId: string; error: string } | null
  >(null);
  const [reportResult, setReportResult] = useState<
    { runId: string; report: ReferenceResolutionReportResponse } | { runId: string; error: string } | null
  >(null);
  const detailsCardRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const pendingDetailTarget = useRef<{ tab: AnalysisRunDetailTab; targetId: string } | null>(null);
  const listRequestSequence = useRef(0);
  const pageCursor = pageCursors[pageIndex] ?? null;
  const runs = runPage.items;

  const selectedRun = useMemo(
    () => runs.find((run) => run.id === selectedRunId) ?? null,
    [runs, selectedRunId],
  );
  const selectedRunStatus = selectedRun?.status;
  const parsedDocumentResultForSelection = parsedDocumentResult?.runId === selectedRunId
    ? parsedDocumentResult
    : null;
  const parsedDocument = parsedDocumentResultForSelection && "document" in parsedDocumentResultForSelection
    ? parsedDocumentResultForSelection.document
    : null;
  const parsedDocumentError = parsedDocumentResultForSelection && "error" in parsedDocumentResultForSelection
    ? parsedDocumentResultForSelection.error
    : null;
  const reportResultForSelection = reportResult?.runId === selectedRunId ? reportResult : null;
  const coverageReport = reportResultForSelection && "report" in reportResultForSelection
    ? reportResultForSelection.report
    : null;
  const coverageReportError = reportResultForSelection && "error" in reportResultForSelection
    ? reportResultForSelection.error
    : null;
  const resolutionEntriesByReferenceKey = useMemo(
    () => new Map(coverageReport?.referenceResolution.entries.map((entry) => [entry.localReferenceKey, entry] as const) ?? []),
    [coverageReport],
  );
  const parsedReferenceKeys = useMemo(
    () => new Set(parsedDocument?.bibliographyEntries.map((entry) => entry.localReferenceKey) ?? []),
    [parsedDocument],
  );
  const coverageReportLoading = Boolean(
    selectedRun && isParsedDocumentReady(selectedRunStatus) && !reportResultForSelection,
  );
  const citationContextAnchorsByReferenceKey = useMemo(() => {
    const anchorsByReferenceKey = new Map<string, Array<{ id: string; label: string }>>();
    if (!parsedDocument) return anchorsByReferenceKey;

    parsedDocument.citationContexts.forEach((context, contextIndex) => {
      const referenceKeys = new Set(
        context.occurrences.flatMap((occurrence) => occurrence.bibliographyReferenceKeys),
      );
      for (const referenceKey of referenceKeys) {
        const anchors = anchorsByReferenceKey.get(referenceKey) ?? [];
        anchors.push({ id: context.id, label: `Citation Context ${contextIndex + 1}` });
        anchorsByReferenceKey.set(referenceKey, anchors);
      }
    });

    return anchorsByReferenceKey;
  }, [parsedDocument]);
  const parsedDocumentLoading = Boolean(
    selectedRun && isParsedDocumentReady(selectedRunStatus) && !parsedDocumentResultForSelection,
  );
  const consentRequirementsForRun = useMemo(
    () => providerDirectory ? consentRequirements(providerDirectory, providerSelections) : [],
    [providerDirectory, providerSelections],
  );

  const refreshRuns = useCallback(async (cursor: string | null = pageCursor) => {
    const requestSequenceNumber = ++listRequestSequence.current;
    try {
      const query = new URLSearchParams({ limit: String(RUN_PAGE_SIZE) });
      if (cursor) query.set("cursor", cursor);
      const response = await fetch(`/api/v1/analysis-runs?${query.toString()}`, { cache: "no-store" });
      if (!response.ok) throw new Error(await readError(response));
      const currentPage = (await response.json()) as AnalysisRunPage;
      if (requestSequenceNumber !== listRequestSequence.current) return;
      setRunPage(currentPage);
      setSelectedRunId((currentId) => currentId && currentPage.items.some((run) => run.id === currentId)
        ? currentId
        : null);
      setError(null);
    } catch (cause) {
      if (requestSequenceNumber !== listRequestSequence.current) return;
      setError(cause instanceof Error ? cause.message : "Could not load saved Analysis Runs.");
    } finally {
      if (requestSequenceNumber === listRequestSequence.current) setLoading(false);
    }
  }, [pageCursor]);

  useEffect(() => {
    const controller = new AbortController();
    void fetch("/api/v1/providers", { cache: "no-store", signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error(await readError(response));
        const directory = (await response.json()) as ProviderDirectory;
        const roles: ProviderRole[] = ["claimExtractor", "embedding", "systemOne", "scholarlyMetadata"];
        if (roles.some((role) => !directory.providers[role]?.length)) {
          throw new Error("The API has no enabled provider for one or more Analysis Run stages.");
        }
        const selectAvailable = (role: ProviderRole, current: string) =>
          directory.providers[role]?.find((provider) => provider.providerId === current)?.providerId
          ?? directory.providers[role]?.[0]?.providerId
          ?? current;
        setProviderDirectory(directory);
        setProviderSelections((current) => ({
          claimExtractorProvider: selectAvailable("claimExtractor", current.claimExtractorProvider),
          embeddingProvider: selectAvailable("embedding", current.embeddingProvider),
          systemOneProvider: selectAvailable("systemOne", current.systemOneProvider),
          scholarlyMetadataProvider: selectAvailable("scholarlyMetadata", current.scholarlyMetadataProvider),
        }));
        setProviderError(null);
      })
      .catch((cause: unknown) => {
        if (cause instanceof DOMException && cause.name === "AbortError") return;
        setProviderError(cause instanceof Error ? cause.message : "Could not load available providers.");
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const initialLoad = window.setTimeout(() => void refreshRuns(), 0);
    const interval = window.setInterval(() => void refreshRuns(), 2500);
    return () => {
      window.clearTimeout(initialLoad);
      window.clearInterval(interval);
    };
  }, [refreshRuns]);

  useEffect(() => {
    const pending = pendingDetailTarget.current;
    if (!pending || activeDetailTab !== pending.tab) return;

    let frame = 0;
    let attempts = 0;
    const locateTarget = () => {
      if (pendingDetailTarget.current !== pending) return;

      const target = document.getElementById(pending.targetId);
      if (target) {
        pendingDetailTarget.current = null;
        scrollToAnchorTarget(target);
        return;
      }

      attempts += 1;
      if (attempts < 8) {
        frame = window.requestAnimationFrame(locateTarget);
        return;
      }

      const targetDataLoaded = pending.tab === "parsed" ? Boolean(parsedDocument) : Boolean(coverageReport);
      const targetDataFailed = pending.tab === "parsed" ? Boolean(parsedDocumentError) : Boolean(coverageReportError);
      if (targetDataLoaded || targetDataFailed) pendingDetailTarget.current = null;
    };

    frame = window.requestAnimationFrame(locateTarget);
    return () => window.cancelAnimationFrame(frame);
  }, [activeDetailTab, parsedDocument, parsedDocumentError, coverageReport, coverageReportError]);

  useEffect(() => {
    if (!selectedRunId || !isParsedDocumentReady(selectedRunStatus)) return;

    let active = true;
    void fetch(`/api/v1/analysis-runs/${encodeURIComponent(selectedRunId)}/report`, { cache: "no-store" })
      .then(async (response) => {
        if (!response.ok) throw new Error(await readError(response));
        return (await response.json()) as ReferenceResolutionReportResponse;
      })
      .then((report) => { if (active) setReportResult({ runId: selectedRunId, report }); })
      .catch((cause: unknown) => {
        if (active) setReportResult({
          runId: selectedRunId,
          error: cause instanceof Error ? cause.message : "Could not load the Reference Resolution Report.",
        });
      });

    return () => { active = false; };
  }, [selectedRunId, selectedRunStatus]);

  useEffect(() => {
    if (!selectedRunId || !isParsedDocumentReady(selectedRunStatus)) return;

    let active = true;
    void fetch(`/api/v1/analysis-runs/${encodeURIComponent(selectedRunId)}/parsed-document`, { cache: "no-store" })
      .then(async (response) => {
        if (!response.ok) throw new Error(await readError(response));
        return (await response.json()) as ParsedDocument;
      })
      .then((document) => { if (active) setParsedDocumentResult({ runId: selectedRunId, document }); })
      .catch((cause: unknown) => {
        if (active) setParsedDocumentResult({
          runId: selectedRunId,
          error: cause instanceof Error ? cause.message : "Could not load the parsed document.",
        });
      });

    return () => { active = false; };
  }, [selectedRunId, selectedRunStatus]);

  function runConfiguration() {
    if (!providerDirectory) throw new Error("Available providers have not loaded yet.");
    if (missingConsents(consentRequirementsForRun, approvedCategories).length > 0) {
      throw new Error("Approve every disclosed data category for each selected external provider, or choose a local provider.");
    }
    return createRunConfiguration(providerSelections, consentRequirementsForRun, approvedCategories);
  }

  function selectProvider(role: ProviderRole, providerId: string) {
    setProviderSelections((current) => ({
      ...current,
      claimExtractorProvider: role === "claimExtractor" ? providerId : current.claimExtractorProvider,
      embeddingProvider: role === "embedding" ? providerId : current.embeddingProvider,
      systemOneProvider: role === "systemOne" ? providerId : current.systemOneProvider,
      scholarlyMetadataProvider: role === "scholarlyMetadata" ? providerId : current.scholarlyMetadataProvider,
    }));
  }

  function approveCategory(providerId: string, category: string, approved: boolean) {
    setApprovedCategories((current) => {
      const existing = new Set(current[providerId] ?? []);
      if (approved) existing.add(category);
      else existing.delete(category);
      return { ...current, [providerId]: [...existing] };
    });
  }

  function providerOptions(role: ProviderRole) {
    return providerDirectory?.providers[role] ?? [];
  }

  function selectRun(runId: string) {
    pendingDetailTarget.current = null;
    setSelectedRunId(runId);
    setActiveDetailTab("progress");
    scrollToDetails(detailsCardRef.current);
  }

  function goToNextRunPage() {
    if (!runPage.nextCursor || loading) return;
    pendingDetailTarget.current = null;
    setSelectedRunId(null);
    setLoading(true);
    setActiveDetailTab("progress");
    setPageCursors((current) => [...current.slice(0, pageIndex + 1), runPage.nextCursor!]);
    setPageIndex(pageIndex + 1);
  }

  function goToPreviousRunPage() {
    if (pageIndex === 0 || loading) return;
    pendingDetailTarget.current = null;
    setSelectedRunId(null);
    setLoading(true);
    setActiveDetailTab("progress");
    setPageIndex(pageIndex - 1);
  }

  function navigateToDetailTarget(
    event: MouseEvent<HTMLAnchorElement>,
    tab: AnalysisRunDetailTab,
    targetId: string,
  ) {
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;

    event.preventDefault();
    pendingDetailTarget.current = { tab, targetId };
    setActiveDetailTab(tab);
  }

  async function startRun(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fileInput = form.elements.namedItem("file");
    if (!(fileInput instanceof HTMLInputElement) || !fileInput.files?.[0]) {
      setError("Choose an English, text-based PDF to continue.");
      return;
    }

    let configuration;
    try {
      configuration = runConfiguration();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Review provider consent before continuing.");
      return;
    }
    const data = new FormData();
    data.append("file", fileInput.files[0]);
    data.append("configuration", JSON.stringify(configuration));
    setBusy(true);
    setError(null);
    try {
      const response = await fetch("/api/v1/analysis-runs", { method: "POST", body: data });
      if (!response.ok) throw new Error(await readError(response));
      const created = (await response.json()) as CreatedRun;
      setPageCursors([null]);
      setPageIndex(0);
      setActiveDetailTab("progress");
      setSelectedRunId(created.analysisRunId);
      form.reset();
      setSelectedFileName(null);
      await refreshRuns(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "The upload could not be processed.");
    } finally {
      setApprovedCategories({});
      setBusy(false);
    }
  }

  async function reanalyze() {
    if (!selectedRun) return;
    setBusy(true);
    setError(null);
    try {
      const configuration = runConfiguration();
      const response = await fetch(`/api/v1/documents/${encodeURIComponent(selectedRun.documentId)}/analysis-runs`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(configuration),
      });
      if (!response.ok) throw new Error(await readError(response));
      const created = (await response.json()) as CreatedRun;
      setPageCursors([null]);
      setPageIndex(0);
      setActiveDetailTab("progress");
      setSelectedRunId(created.analysisRunId);
      await refreshRuns(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "A new Analysis Run could not be created.");
    } finally {
      setApprovedCategories({});
      setBusy(false);
    }
  }

  const activeError = error ?? providerError;
  const parsedDocumentReady = isParsedDocumentReady(selectedRunStatus);
  const parsedStepState: Pick<WorkflowStep, "state" | "statusLabel"> = !parsedDocumentReady
    ? { state: "waiting", statusLabel: "Waiting" }
    : parsedDocumentLoading
      ? { state: "loading", statusLabel: "Loading" }
      : parsedDocumentError
        ? { state: "failed", statusLabel: "Unavailable" }
        : { state: "ready", statusLabel: "Ready" };
  const reportStepState: Pick<WorkflowStep, "state" | "statusLabel"> = !parsedDocumentReady
    ? { state: "waiting", statusLabel: "Waiting" }
    : coverageReportLoading
      ? { state: "loading", statusLabel: "Loading" }
      : coverageReportError
        ? { state: "failed", statusLabel: "Unavailable" }
        : { state: "ready", statusLabel: "Ready" };
  const workflowSteps: WorkflowStep[] = [
    { value: "progress", number: "01", label: "Run Progress", compactLabel: "Progress", ...runProgressStep(selectedRunStatus) },
    { value: "parsed", number: "02", label: "Parsed Document", compactLabel: "Parsed", ...parsedStepState, disabled: !parsedDocumentReady },
    { value: "report", number: "03", label: "Reference Resolution Report", compactLabel: "Report", ...reportStepState, disabled: !parsedDocumentReady },
  ];

  function scrollToDetailsHeading() {
    document.getElementById("parsed-document-heading")?.focus({ preventScroll: true });
    scrollToDetails(detailsCardRef.current);
  }

  return (
    <section className="space-y-6" aria-label="Source Document workspace">
      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
        <Card className="shadow-sm">
          <CardHeader className="gap-2 border-b border-border/70 pb-5">
            <p className="flex items-center gap-2 font-mono text-xs tracking-[0.12em] text-muted-foreground uppercase">
              <span className="font-semibold text-warning-foreground">01</span> Source Document
            </p>
            <CardTitle id="upload-heading" role="heading" aria-level={2} className="text-xl tracking-tight">
              Start with your PDF
            </CardTitle>
          </CardHeader>
          <CardContent className="space-y-5">
            <form onSubmit={startRun} className="space-y-5">
              <FieldGroup className="gap-4">
                {([
                  ["claimExtractor", "Claim extraction", "claimExtractorProvider"],
                  ["embedding", "Embeddings", "embeddingProvider"],
                  ["systemOne", "Evidence assessment", "systemOneProvider"],
                  ["scholarlyMetadata", "Bibliography resolution", "scholarlyMetadataProvider"],
                ] as const).map(([role, label, selectionField]) => {
                  const selectId = `provider-${role}`;
                  return (
                    <Field key={role}>
                      <FieldLabel htmlFor={selectId} className="text-xs font-medium text-foreground">
                        {label}
                      </FieldLabel>
                      <NativeSelect
                        id={selectId}
                        className="w-full [&_[data-slot=native-select]]:h-11"
                        value={providerSelections[selectionField]}
                        disabled={busy || !providerDirectory}
                        onChange={(event) => selectProvider(role, event.target.value)}
                      >
                        {providerDirectory ? providerOptions(role).map((provider) => (
                          <NativeSelectOption key={provider.providerId} value={provider.providerId}>
                            {provider.displayName}
                          </NativeSelectOption>
                        )) : (
                          <NativeSelectOption value={providerSelections[selectionField]}>
                            Loading provider choices…
                          </NativeSelectOption>
                        )}
                      </NativeSelect>
                    </Field>
                  );
                })}
              </FieldGroup>

              {!providerDirectory ? (
                <p className="text-sm text-muted-foreground" role="status">Loading provider disclosures…</p>
              ) : consentRequirementsForRun.length === 0 ? (
                <div className="flex gap-3 rounded-lg border border-primary/15 bg-primary/5 p-4 text-sm" role="note" aria-live="polite">
                  <LockKeyhole className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
                  <div className="space-y-1">
                    <p className="font-medium text-foreground">Local/mock providers selected</p>
                    <p className="text-sm leading-relaxed text-muted-foreground">
                      No external provider receives document content for this run.
                    </p>
                  </div>
                </div>
              ) : consentRequirementsForRun.map((provider) => (
                <section
                  className="space-y-4 rounded-lg border border-border bg-muted/20 p-4"
                  key={provider.providerId}
                  aria-labelledby={`consent-${provider.providerId}`}
                >
                  <div className="space-y-1">
                    <h3 id={`consent-${provider.providerId}`} className="font-medium">
                      {provider.displayName} data access
                    </h3>
                    <p className="text-sm leading-relaxed text-muted-foreground">
                      May receive in this run:
                    </p>
                    {provider.retentionDisclosure && (
                      <p className="text-sm text-warning-foreground">{provider.retentionDisclosure}</p>
                    )}
                  </div>
                  <FieldSet className="min-w-0 gap-3 border-0 p-0">
                    <FieldLegend variant="label" className="text-sm">
                      Approve each category to continue
                    </FieldLegend>
                    {provider.dataCategories.map((categoryId) => {
                      const category = providerDirectory?.dataCategories.find((item) => item.id === categoryId);
                      const checkboxId = `consent-${provider.providerId}-${categoryId}`;
                      return (
                        <Field orientation="horizontal" key={categoryId} className="items-start gap-3">
                          <Checkbox
                            id={checkboxId}
                            disabled={busy}
                            checked={approvedCategories[provider.providerId]?.includes(categoryId) ?? false}
                            onCheckedChange={(checked) => approveCategory(provider.providerId, categoryId, checked === true)}
                          />
                          <div className="min-w-0 space-y-1">
                            <FieldLabel htmlFor={checkboxId} className="text-sm font-medium">
                              {category?.label ?? categoryId}
                            </FieldLabel>
                            <FieldDescription className="text-xs leading-relaxed">
                              <code className="font-mono text-[0.7rem]">{categoryId}</code>
                              {category?.description ? ` · ${category.description}` : ""}
                            </FieldDescription>
                          </div>
                        </Field>
                      );
                    })}
                  </FieldSet>
                  <p className="text-xs leading-relaxed text-warning-foreground">
                    Consent applies only to this run and these categories.
                  </p>
                </section>
              ))}

              <Field>
                <FieldLabel htmlFor="source-file-trigger">Choose a PDF</FieldLabel>
                <div className="flex min-h-11 items-center gap-3 rounded-lg border border-input bg-background px-2.5 py-1">
                  <Button
                    id="source-file-trigger"
                    type="button"
                    variant="secondary"
                    size="sm"
                    disabled={busy}
                    aria-describedby="source-file-description"
                    onClick={() => fileInputRef.current?.click()}
                  >
                    Choose File
                  </Button>
                  <span className="min-w-0 flex-1 truncate text-sm text-muted-foreground" aria-live="polite">
                    {selectedFileName ?? "No file chosen"}
                  </span>
                  <input
                    ref={fileInputRef}
                    id="source-file"
                    name="file"
                    type="file"
                    accept="application/pdf,.pdf"
                    disabled={busy}
                    aria-hidden="true"
                    tabIndex={-1}
                    className="sr-only"
                    onChange={(event) => setSelectedFileName(event.currentTarget.files?.[0]?.name ?? null)}
                  />
                </div>
                <FieldDescription id="source-file-description">
                  English PDFs with selectable text only.
                </FieldDescription>
              </Field>

              <Button type="submit" size="lg" className="min-h-11 w-full justify-between" disabled={busy || !providerDirectory}>
                <span className="inline-flex items-center gap-2">
                  {busy && <Spinner aria-hidden="true" />}
                  {busy ? "Starting run…" : "Upload & start Analysis Run"}
                </span>
                {!busy && <ArrowUpRight className="size-4" aria-hidden="true" />}
              </Button>
            </form>

            {activeError && (
              <Alert variant="destructive">
                <AlertTitle>Could not continue</AlertTitle>
                <AlertDescription>{activeError}</AlertDescription>
              </Alert>
            )}
          </CardContent>
        </Card>

        <Card className="shadow-sm">
          <CardHeader className="gap-2 border-b border-border/70 pb-5">
            <p className="flex items-center gap-2 font-mono text-xs tracking-[0.12em] text-muted-foreground uppercase">
              <span className="font-semibold text-warning-foreground">02</span> Persisted Progress
            </p>
            <div className="flex items-center justify-between gap-3">
              <CardTitle id="runs-heading" role="heading" aria-level={2} className="text-xl tracking-tight">
                Analysis Runs
              </CardTitle>
              <Badge variant="secondary" className="font-mono text-xs">
                {runs.length.toString().padStart(2, "0")}
              </Badge>
            </div>
          </CardHeader>
          <CardContent className="space-y-4">
            {loading ? (
              <div className="space-y-3" role="status" aria-label="Loading saved Analysis Runs">
                <span className="sr-only">Loading saved Analysis Runs</span>
                <Skeleton className="h-14 w-full" aria-hidden="true" />
                <Skeleton className="h-14 w-full" aria-hidden="true" />
                <Skeleton className="h-14 w-4/5" aria-hidden="true" />
              </div>
            ) : runs.length === 0 ? (
              <div className="grid min-h-40 place-items-center rounded-lg border border-dashed border-border bg-muted/20 p-6 text-center">
                <div className="space-y-2">
                  <FileText className="mx-auto size-6 text-muted-foreground" aria-hidden="true" />
                  <p className="text-sm font-medium">Your first Analysis Run will appear here.</p>
                  <p className="text-xs text-muted-foreground">Upload an academic PDF to begin.</p>
                </div>
              </div>
            ) : (
              <div className="max-h-[26rem] space-y-2 overflow-y-auto pr-1" role="list" aria-label="Saved Analysis Runs">
                {runs.map((run) => (
                  <div role="listitem" key={run.id}>
                    <Button
                      type="button"
                      variant="ghost"
                      aria-current={run.id === selectedRunId ? "true" : undefined}
                      className={cn(
                        "h-auto min-h-14 w-full justify-between gap-3 rounded-lg border border-transparent px-3 py-3 text-left",
                        run.id === selectedRunId && "border-primary/20 bg-accent hover:bg-accent",
                      )}
                      onClick={() => selectRun(run.id)}
                    >
                      <span className="min-w-0 flex-1 space-y-1">
                        <span className="block truncate text-sm font-medium text-foreground">{run.filename}</span>
                        <span className="block font-mono text-xs text-muted-foreground">{new Date(run.createdAt).toLocaleString()}</span>
                      </span>
                      <RunStatusBadge status={run.status} />
                    </Button>
                  </div>
                ))}
              </div>
            )}

            <Separator />
            <nav className="flex items-center justify-between gap-3" aria-label="Analysis Run pages">
              <span className="font-mono text-xs text-muted-foreground" aria-live="polite">
                Page {pageIndex + 1}
              </span>
              <div className="flex items-center gap-2">
                {pageIndex > 0 && (
                  <Button type="button" variant="outline" size="sm" className="min-h-11" onClick={goToPreviousRunPage} disabled={loading}>
                    <ArrowLeft aria-hidden="true" /> Previous
                  </Button>
                )}
                <Button type="button" variant="outline" size="sm" className="min-h-11" onClick={goToNextRunPage} disabled={!runPage.nextCursor || loading}>
                  Next <ArrowRight aria-hidden="true" />
                </Button>
              </div>
            </nav>
          </CardContent>
        </Card>

        <Card className="scroll-mt-5 min-w-0 overflow-visible shadow-sm lg:col-span-2" ref={detailsCardRef}>
          <CardHeader className="gap-3 border-b border-border/70 pb-5">
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div className="min-w-0 space-y-2">
                <p className="flex items-center gap-2 font-mono text-xs tracking-[0.12em] text-muted-foreground uppercase">
                  <span className="font-semibold text-warning-foreground">03</span> Parsed Document
                </p>
                <CardTitle id="parsed-document-heading" role="heading" aria-level={2} tabIndex={-1} className="break-words text-xl tracking-tight">
                  {selectedRun?.filename ?? "Analysis Run details"}
                </CardTitle>
              </div>
              {selectedRun && <RunStatusBadge status={selectedRun.status} />}
            </div>
          </CardHeader>

          {!selectedRun ? (
            <CardContent>
              <div className="grid min-h-44 place-items-center p-6 text-center">
                <div className="space-y-2">
                  <FileText className="mx-auto size-6 text-muted-foreground" aria-hidden="true" />
                  <p className="text-sm font-medium">Select an Analysis Run to inspect its progress and parsed document.</p>
                </div>
              </div>
            </CardContent>
          ) : (
            <CardContent className="space-y-5">
              <WorkflowStepTabs
                value={activeDetailTab}
                onValueChange={(value) => {
                  if (value === "progress" || value === "parsed" || value === "report") {
                    pendingDetailTarget.current = null;
                    setActiveDetailTab(value);
                  }
                }}
                steps={workflowSteps}
                onScrollToTop={scrollToDetailsHeading}
                scrollToTopLabel="Back to Parsed Document heading"
              >

                <TabsContent value="progress" className="space-y-5 outline-none">
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <p className="font-mono text-xs tracking-[0.1em] text-muted-foreground uppercase">Current run progress</p>
                    <RunStatusBadge status={selectedRun.status} />
                  </div>
                  <p className="text-sm leading-relaxed text-foreground" aria-live="polite">
                    {selectedRun.progress.message ?? (selectedRun.status === "QUEUED" ? "Waiting for a worker." : "Progress saved.")}
                  </p>
                  <Separator />
                  <dl className="grid gap-4 sm:grid-cols-[minmax(9rem,0.35fr)_minmax(0,1fr)]">
                    <dt className="font-mono text-xs tracking-wide text-muted-foreground uppercase">Source SHA-256</dt>
                    <dd className="m-0 break-all font-mono text-xs leading-relaxed text-foreground">{selectedRun.sourceContentSha256}</dd>
                    <dt className="font-mono text-xs tracking-wide text-muted-foreground uppercase">Configuration</dt>
                    <dd className="m-0 break-words text-sm text-foreground">
                      {selectedRun.configuration.claimExtractor.provider} · {selectedRun.configuration.embedding.provider} · {selectedRun.configuration.systemOne.provider} · {selectedRun.configuration.referenceResolution?.provider?.provider ?? "not configured"}
                    </dd>
                    <dt className="font-mono text-xs tracking-wide text-muted-foreground uppercase">Worker stage</dt>
                    <dd className="m-0 text-sm capitalize text-foreground">
                      {selectedRun.progress.stage?.replaceAll("_", " ").toLowerCase() ?? "queued"}
                    </dd>
                  </dl>
                  {selectedRun.status === "FAILED" && selectedRun.failureReason && (
                    <Alert variant="destructive">
                      <AlertTitle>Analysis Run failed</AlertTitle>
                      <AlertDescription>{selectedRun.failureReason}</AlertDescription>
                    </Alert>
                  )}
                  <Button type="button" variant="outline" className="min-h-11 w-full justify-between sm:w-auto" disabled={busy || !providerDirectory} onClick={reanalyze}>
                    Create a new run from this document <ArrowUpRight aria-hidden="true" />
                  </Button>
                </TabsContent>

                <TabsContent value="parsed" className="space-y-5 outline-none">
                  {!isParsedDocumentReady(selectedRunStatus) && (
                    <p className="rounded-lg border border-border bg-muted/30 p-4 text-sm text-muted-foreground">
                      The parsed document becomes available when this run reaches PARSED.
                    </p>
                  )}
                  {parsedDocumentLoading && (
                    <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
                      <Spinner aria-hidden="true" /> Loading parsed document structure…
                    </p>
                  )}
                  {parsedDocumentError && (
                    <Alert variant="destructive">
                      <AlertTitle>Parsed document unavailable</AlertTitle>
                      <AlertDescription>{parsedDocumentError}</AlertDescription>
                    </Alert>
                  )}
                  {parsedDocument && (
                    <div className="space-y-6 pt-1">
                      <div className="flex flex-wrap items-center justify-between gap-3">
                        <div className="space-y-1">
                          <p className="font-mono text-xs tracking-[0.12em] text-muted-foreground uppercase">Parsed document</p>
                          <h3 className="font-heading text-lg font-semibold tracking-tight">Sections and references</h3>
                        </div>
                        <Badge variant="secondary" className="font-mono text-xs">
                          {parsedDocument.parser.provider} {parsedDocument.parser.version}
                        </Badge>
                      </div>
                      <p className="rounded-md bg-muted/50 px-3 py-2 text-xs leading-relaxed text-muted-foreground">
                        Source offsets are zero-based and end-exclusive UTF-16 indexes in the normalized source text.
                      </p>

                      <section className="space-y-3" aria-labelledby="sections-heading">
                        <div className="flex items-center justify-between gap-3">
                          <h4 id="sections-heading" className="font-heading text-base font-semibold">Sections</h4>
                          <Badge variant="outline">{parsedDocument.sections.length}</Badge>
                        </div>
                        {parsedDocument.sections.length === 0 ? (
                          <p className="text-sm text-muted-foreground">No sections were returned by the parser.</p>
                        ) : (
                          <ol className="space-y-2">
                            {parsedDocument.sections.map((section) => (
                              <li key={section.id}>
                                <Collapsible className="group/section rounded-lg border border-border bg-card">
                                  <CollapsibleTrigger className="flex min-h-12 w-full items-center justify-between gap-4 px-4 py-3 text-left focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
                                    <span className="min-w-0 flex-1 truncate text-sm font-medium">
                                      {section.heading || `Section ${section.sectionOrder + 1}`}
                                    </span>
                                    <span className="shrink-0 font-mono text-xs text-muted-foreground">
                                      {section.startOffset}–{section.endOffset}
                                    </span>
                                    <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[open]/section:rotate-180" aria-hidden="true" />
                                  </CollapsibleTrigger>
                                  <CollapsibleContent className="border-t border-border px-4 py-3">
                                    <p className="whitespace-pre-wrap break-words text-sm leading-relaxed text-muted-foreground">{section.text}</p>
                                  </CollapsibleContent>
                                </Collapsible>
                              </li>
                            ))}
                          </ol>
                        )}
                      </section>

                      <Separator />
                      <section className="space-y-3" aria-labelledby="contexts-heading">
                        <div className="flex items-center justify-between gap-3">
                          <h4 id="contexts-heading" className="font-heading text-base font-semibold">Citation Contexts</h4>
                          <Badge variant="outline">{parsedDocument.citationContexts.length}</Badge>
                        </div>
                        {parsedDocument.citationContexts.length === 0 ? (
                          <p className="text-sm text-muted-foreground">No citation markers were detected.</p>
                        ) : (
                          <ol className="space-y-3">
                            {parsedDocument.citationContexts.map((context, index) => (
                              <li key={context.id} id={`citation-context-${context.id}`} className="citation-context-anchor scroll-mt-5">
                                <article className="space-y-3 rounded-lg border border-border bg-muted/20 p-4">
                                  <div className="flex flex-wrap items-center justify-between gap-2 font-mono text-xs text-muted-foreground">
                                    <Badge variant="secondary" className="text-[0.65rem] uppercase">
                                      {context.boundaryKind.replaceAll("_", " ").toLowerCase()}
                                    </Badge>
                                    <span>{context.startOffset}–{context.endOffset}</span>
                                  </div>
                                  <p className="break-words text-sm leading-relaxed">{context.text}</p>
                                  <section className="space-y-2" aria-label={`Atomic Claims from Citation Context ${index + 1}`}>
                                    <h5 className="font-mono text-xs tracking-wide text-muted-foreground uppercase">Atomic Claims</h5>
                                    {context.atomicClaims.length === 0 ? (
                                      <p className="text-sm text-muted-foreground">No Atomic Claims were extracted from this Citation Context.</p>
                                    ) : (
                                      <ol className="space-y-2">
                                        {context.atomicClaims.map((claim) => (
                                          <li key={claim.id}>
                                            <article className="space-y-2 rounded-md border border-border bg-card p-3">
                                              <div className="flex flex-wrap items-start justify-between gap-2">
                                                <p className="min-w-0 flex-1 break-words text-sm leading-relaxed">{claim.text}</p>
                                                <Badge variant="outline" className="shrink-0">
                                                  {claim.citationTargets.length > 0 ? "Inferred · provisional" : "No Citation Targets"}
                                                </Badge>
                                              </div>
                                              <p className="font-mono text-xs text-muted-foreground">
                                                Source span {claim.sourceStartOffset}–{claim.sourceEndOffset} in the Citation Context above
                                              </p>
                                              {claim.citationTargets.length === 0 ? (
                                                <p className="text-xs text-muted-foreground">No Citation Targets were resolved in this Citation Context.</p>
                                              ) : (
                                                <ul className="flex flex-wrap gap-x-3 gap-y-1 border-t border-border pt-2" aria-label="Inferred Citation Targets">
                                                  {claim.citationTargets.map((target) => (
                                                    <li key={target.id}>
                                                      <a
                                                        className="max-w-full break-words text-xs text-primary underline underline-offset-4 hover:text-primary/80"
                                                        href={`#bibliography-${target.bibliographyReferenceKey}`}
                                                        onClick={scrollToParsedDocumentTarget}
                                                      >
                                                        <code className="font-mono">{target.markerText}</code>
                                                        <span className="ml-1">{target.bibliographyTitle || target.bibliographyReferenceKey}</span>
                                                      </a>
                                                    </li>
                                                  ))}
                                                </ul>
                                              )}
                                            </article>
                                          </li>
                                        ))}
                                      </ol>
                                    )}
                                  </section>
                                  <ul className="space-y-2 border-l-2 border-primary/20 pl-4">
                                    {context.occurrences.map((occurrence) => (
                                      <li key={occurrence.id} className="break-words text-sm">
                                        <code className="rounded bg-background px-1.5 py-0.5 font-mono text-xs text-primary">{occurrence.markerText}</code>
                                        <span className="ml-2 font-mono text-xs text-muted-foreground">
                                          {occurrence.startOffset}–{occurrence.endOffset}
                                        </span>
                                        {occurrence.bibliographyReferenceKeys.length > 0 && (
                                          <span className="ml-2 inline-flex flex-wrap gap-x-2">
                                            <span className="sr-only">Bibliography entries:</span>
                                            {occurrence.bibliographyReferenceKeys.map((key) => (
                                              <a key={key} className="text-primary underline underline-offset-4 hover:text-primary/80" href={`#bibliography-${key}`} onClick={scrollToParsedDocumentTarget}>
                                                {key}
                                              </a>
                                            ))}
                                          </span>
                                        )}
                                      </li>
                                    ))}
                                  </ul>
                                </article>
                              </li>
                            ))}
                          </ol>
                        )}
                      </section>

                      <Separator />
                      <section className="space-y-3" aria-labelledby="bibliography-heading">
                        <div className="flex items-center justify-between gap-3">
                          <h4 id="bibliography-heading" className="font-heading text-base font-semibold">Bibliography Entries</h4>
                          <Badge variant="outline">{parsedDocument.bibliographyEntries.length}</Badge>
                        </div>
                        {parsedDocument.bibliographyEntries.length === 0 ? (
                          <p className="text-sm text-muted-foreground">No bibliography entries were detected.</p>
                        ) : (
                          <ol className="space-y-3">
                            {parsedDocument.bibliographyEntries.map((entry) => {
                              const citingContexts = citationContextAnchorsByReferenceKey.get(entry.localReferenceKey) ?? [];
                              const resolution = resolutionEntriesByReferenceKey.get(entry.localReferenceKey);
                              return (
                                <li key={entry.localReferenceKey} id={`bibliography-${entry.localReferenceKey}`} className="bibliography-entry-anchor scroll-mt-5 rounded-lg border border-border bg-card p-4">
                                  <h5 className="break-words font-medium leading-relaxed">
                                    {entry.title || entry.localReferenceKey}
                                  </h5>
                                  <p className="mt-1 font-mono text-xs text-muted-foreground uppercase">
                                    {entry.localReferenceKey} · {entry.referenceType.toLowerCase().replaceAll("_", " ")}{entry.year ? ` · ${entry.year}` : ""}
                                  </p>
                                  {entry.authors.length > 0 && (
                                    <p className="mt-2 break-words text-sm text-muted-foreground">{entry.authors.join(", ")}</p>
                                  )}
                                  <p className="mt-3 break-words text-sm leading-relaxed">{entry.rawText}</p>
                                  {entry.doi && (
                                    <p className="mt-2 break-all font-mono text-xs text-muted-foreground">DOI: {entry.doi}</p>
                                  )}
                                  {resolution && (
                                    <div className="mt-3 flex flex-wrap items-center justify-between gap-3 border-t border-border pt-3">
                                      <div className="flex items-center gap-2">
                                        <span className="text-xs text-muted-foreground">Resolution</span>
                                        <ReferenceResolutionBadge status={resolution.status} />
                                      </div>
                                      <a
                                        className="inline-flex min-h-11 items-center gap-2 text-sm font-medium text-primary underline underline-offset-4 hover:text-primary/80 focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
                                        href={`#${referenceResolutionAnchorId(entry.localReferenceKey)}`}
                                        onClick={(event) => navigateToDetailTarget(event, "report", referenceResolutionAnchorId(entry.localReferenceKey))}
                                      >
                                        View resolution result <ArrowRight className="size-4" aria-hidden="true" />
                                      </a>
                                    </div>
                                  )}
                                  {citingContexts.length > 0 && (
                                    <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-border pt-3">
                                      <span className="text-xs text-muted-foreground">Cited in:</span>
                                      {citingContexts.map((context) => (
                                        <a
                                          key={context.id}
                                          className="text-xs text-primary underline underline-offset-4 hover:text-primary/80"
                                          href={`#citation-context-${context.id}`}
                                          onClick={scrollToParsedDocumentTarget}
                                        >
                                          {context.label}
                                        </a>
                                      ))}
                                    </div>
                                  )}
                                </li>
                              );
                            })}
                          </ol>
                        )}
                      </section>
                    </div>
                  )}
                </TabsContent>

                <TabsContent value="report" className="space-y-5 outline-none">
                  {coverageReportLoading && (
                    <p className="flex items-center gap-2 text-sm text-muted-foreground" role="status">
                      <Spinner aria-hidden="true" /> Loading Reference Resolution Report…
                    </p>
                  )}
                  {coverageReportError && (
                    <Alert variant="destructive">
                      <AlertTitle>Report unavailable</AlertTitle>
                      <AlertDescription>{coverageReportError}</AlertDescription>
                    </Alert>
                  )}
                  {coverageReport && (() => {
                    const resolution = coverageReport.referenceResolution;
                    const counts = resolution.summary;
                    return (
                      <div className="space-y-5">
                        <div className="flex flex-wrap items-start justify-between gap-3">
                          <div className="space-y-1">
                            <p className="font-mono text-xs tracking-[0.12em] text-muted-foreground uppercase">Reference Resolution Report</p>
                            <h3 className="font-heading text-lg font-semibold tracking-tight">Bibliography resolution</h3>
                          </div>
                          <Badge variant="secondary" className="font-mono text-xs">
                            {resolution.executionStatus.replaceAll("_", " ").toLowerCase()}
                          </Badge>
                        </div>
                        <dl className="grid gap-3 rounded-lg border border-border bg-muted/20 p-4 sm:grid-cols-2">
                          <div className="space-y-1">
                            <dt className="font-mono text-xs uppercase text-muted-foreground">Score policy</dt>
                            <dd className="m-0 break-words font-mono text-xs text-foreground">{resolution.scorePolicyVersion ?? "Not configured for this run"}</dd>
                          </div>
                          <div className="space-y-1">
                            <dt className="font-mono text-xs uppercase text-muted-foreground">Configured threshold</dt>
                            <dd className="m-0 font-mono text-xs text-foreground">{formatConfidenceThreshold(resolution.confidenceThreshold)}</dd>
                          </div>
                        </dl>
                        <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
                          {([
                            ["Bibliography entries", counts.total],
                            ["Resolved", counts.resolved],
                            ["Unresolved", counts.unresolved],
                            ["Unsupported types", counts.unsupportedReferenceType],
                            ["Not attempted", counts.notAttempted],
                            ["Processing failed", counts.failed],
                          ] as const).map(([label, count]) => (
                            <div key={label} className="rounded-lg border border-border bg-card px-3 py-3">
                              <dt className="text-xs leading-relaxed text-muted-foreground">{label}</dt>
                              <dd className="m-0 mt-1 font-mono text-lg font-semibold text-foreground">{count}</dd>
                            </div>
                          ))}
                        </dl>
                        {resolution.entries.length === 0 ? (
                          <p className="rounded-lg border border-dashed border-border bg-muted/20 p-5 text-sm text-muted-foreground">
                            No Bibliography Entries were available for resolution.
                          </p>
                        ) : (
                          <ol className="space-y-3">
                            {resolution.entries.map((entry) => (
                              <ReferenceResolutionEntryCard
                                key={entry.localReferenceKey}
                                entry={entry}
                                anchorId={referenceResolutionAnchorId(entry.localReferenceKey)}
                                parsedEntryHref={`#bibliography-${entry.localReferenceKey}`}
                                parsedEntryAvailable={parsedReferenceKeys.has(entry.localReferenceKey)}
                                onViewParsedEntry={(event, referenceKey) => navigateToDetailTarget(event, "parsed", `bibliography-${referenceKey}`)}
                              />
                            ))}
                          </ol>
                        )}
                        <p className="text-xs leading-relaxed text-muted-foreground">
                          Ambiguous and below-threshold matches remain unresolved. The configured threshold is pinned to this run; numeric calibration remains a separate release gate. This report does not claim to complete Atomic Claim or evidence analysis.
                        </p>
                      </div>
                    );
                  })()}
                </TabsContent>
              </WorkflowStepTabs>
            </CardContent>
          )}
        </Card>
      </div>
    </section>
  );
}
