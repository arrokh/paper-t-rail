"use client";

import { FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowLeft,
  ArrowRight,
  ArrowUpRight,
  ChevronDown,
  FileText,
  LockKeyhole,
} from "lucide-react";
import type { AnalysisRun, AnalysisRunPage, ApiError, CreatedRun, ParsedDocument } from "@/lib/types";
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
  CardDescription,
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
import { Input } from "@/components/ui/input";
import {
  NativeSelect,
  NativeSelectOption,
} from "@/components/ui/native-select";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { Spinner } from "@/components/ui/spinner";
import {
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from "@/components/ui/tabs";

const RUN_PAGE_SIZE = 25;

type AnalysisRunDetailTab = "progress" | "parsed";

const DEFAULT_SELECTIONS: ProviderSelections = {
  claimExtractorProvider: "heuristic",
  embeddingProvider: "local",
  systemOneProvider: "mock",
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

function scrollToDetails(element: HTMLElement | null) {
  element?.scrollIntoView({
    behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth",
    block: "start",
  });
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
  const [parsedDocumentResult, setParsedDocumentResult] = useState<
    { runId: string; document: ParsedDocument } | { runId: string; error: string } | null
  >(null);
  const detailsCardRef = useRef<HTMLDivElement>(null);
  const pendingDetailsScroll = useRef(false);
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
        : currentPage.items[0]?.id ?? null);
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
        const roles: ProviderRole[] = ["claimExtractor", "embedding", "systemOne"];
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
    if (!pendingDetailsScroll.current || !selectedRunId) return;
    scrollToDetails(detailsCardRef.current);
    pendingDetailsScroll.current = false;
  }, [selectedRunId]);

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
    setSelectedRunId(runId);
    setActiveDetailTab("progress");
    scrollToDetails(detailsCardRef.current);
  }

  function goToNextRunPage() {
    if (!runPage.nextCursor || loading) return;
    pendingDetailsScroll.current = true;
    setLoading(true);
    setActiveDetailTab("progress");
    setPageCursors((current) => [...current.slice(0, pageIndex + 1), runPage.nextCursor!]);
    setPageIndex(pageIndex + 1);
  }

  function goToPreviousRunPage() {
    if (pageIndex === 0 || loading) return;
    pendingDetailsScroll.current = true;
    setLoading(true);
    setActiveDetailTab("progress");
    setPageIndex(pageIndex - 1);
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
            <CardDescription className="max-w-prose leading-relaxed">
              Upload an English academic document with selectable text. Scanned PDFs and other languages are rejected with a reason.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-5">
            <form onSubmit={startRun} className="space-y-5">
              <FieldGroup className="gap-4">
                {([
                  ["claimExtractor", "Claim extraction", "claimExtractorProvider"],
                  ["embedding", "Embeddings", "embeddingProvider"],
                  ["systemOne", "Evidence assessment", "systemOneProvider"],
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
                      This run needs no external consent and sends no document content to an external provider.
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
                      This external provider may receive only the following request categories for this run:
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
                    Consent applies only to this Analysis Run. It does not change previous runs or authorize additional categories.
                  </p>
                </section>
              ))}

              {providerDirectory && providerDirectory.dataCategories.length > 0 && (
                <Collapsible className="group/collapsible border-t border-border pt-3">
                  <CollapsibleTrigger className="flex min-h-11 w-full items-center justify-between gap-3 rounded-md text-left text-sm font-medium text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
                    All stable data categories
                    <ChevronDown className="size-4 shrink-0 transition-transform group-data-[open]/collapsible:rotate-180" aria-hidden="true" />
                  </CollapsibleTrigger>
                  <CollapsibleContent className="pt-3">
                    <ul className="space-y-3 pl-4 text-sm text-muted-foreground">
                      {providerDirectory.dataCategories.map((category) => (
                        <li key={category.id} className="leading-relaxed">
                          <strong className="font-mono text-xs text-foreground">{category.id}</strong>
                          <span> — {category.description}</span>
                        </li>
                      ))}
                    </ul>
                  </CollapsibleContent>
                </Collapsible>
              )}

              <Field>
                <FieldLabel htmlFor="source-file">Choose a PDF</FieldLabel>
                <Input
                  id="source-file"
                  name="file"
                  type="file"
                  accept="application/pdf,.pdf"
                  required
                  disabled={busy}
                  aria-describedby="source-file-description"
                  className="h-11 cursor-pointer file:mr-3 file:rounded-md file:border-0 file:bg-secondary file:px-3 file:py-1.5 file:text-xs file:font-medium file:text-secondary-foreground hover:file:bg-accent"
                />
                <FieldDescription id="source-file-description">
                  PDF only. Selectable text is verified before storage; scanned PDFs are not processed.
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

            <div className="flex gap-3 rounded-lg bg-muted/60 p-4 text-sm" role="note">
              <LockKeyhole className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
              <p className="leading-relaxed text-muted-foreground">
                <strong className="font-medium text-foreground">Local/mock providers are the default.</strong>{" "}
                Disabled or unclassified providers are not offered for selection. External providers require explicit approval of every disclosed category for each run. Upload limits are configurable; over-limit files are rejected, never trimmed.
              </p>
            </div>

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
              <Button type="button" variant="outline" size="sm" className="min-h-11" onClick={goToPreviousRunPage} disabled={pageIndex === 0 || loading}>
                <ArrowLeft aria-hidden="true" /> Previous
              </Button>
              <span className="font-mono text-xs text-muted-foreground" aria-live="polite">
                Page {pageIndex + 1}
              </span>
              <Button type="button" variant="outline" size="sm" className="min-h-11" onClick={goToNextRunPage} disabled={!runPage.nextCursor || loading}>
                Next <ArrowRight aria-hidden="true" />
              </Button>
            </nav>
          </CardContent>
        </Card>

        <Card className="scroll-mt-5 shadow-sm lg:col-span-2" ref={detailsCardRef}>
          <CardHeader className="gap-3 border-b border-border/70 pb-5">
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div className="min-w-0 space-y-2">
                <p className="flex items-center gap-2 font-mono text-xs tracking-[0.12em] text-muted-foreground uppercase">
                  <span className="font-semibold text-warning-foreground">03</span> Parsed Document
                </p>
                <CardTitle id="parsed-document-heading" role="heading" aria-level={2} className="break-words text-xl tracking-tight">
                  {selectedRun?.filename ?? "Analysis Run details"}
                </CardTitle>
                <CardDescription className="max-w-prose leading-relaxed">
                  Inspect persisted progress, parser provenance, Citation Contexts, and Bibliography Entries.
                </CardDescription>
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
              <Tabs
                value={activeDetailTab}
                onValueChange={(value) => {
                  if (value === "progress" || value === "parsed") setActiveDetailTab(value);
                }}
                className="gap-4"
              >
                <TabsList variant="line" className="h-auto w-full min-w-0 justify-start gap-1 rounded-none border-b border-border bg-transparent p-0 sm:gap-3">
                  <TabsTrigger
                    value="progress"
                    aria-label="Run Progress"
                    className="min-h-11 min-w-0 justify-start rounded-none px-1.5 text-[0.65rem] text-muted-foreground uppercase tracking-normal data-active:text-primary disabled:opacity-100 aria-disabled:opacity-100 sm:flex-none sm:px-2 sm:text-xs sm:tracking-[0.08em]"
                  >
                    <span className="font-mono text-warning-foreground">01</span>
                    <span className="sm:hidden">Progress</span>
                    <span className="hidden sm:inline">Run Progress</span>
                  </TabsTrigger>
                  <ArrowRight className="hidden size-4 shrink-0 text-muted-foreground sm:block" aria-hidden="true" />
                  <TabsTrigger
                    value="parsed"
                    aria-label="Parsed Document"
                    disabled={!isParsedDocumentReady(selectedRunStatus)}
                    className="min-h-11 min-w-0 justify-start rounded-none px-1.5 text-[0.65rem] text-muted-foreground uppercase tracking-normal data-active:text-primary disabled:opacity-100 aria-disabled:opacity-100 sm:flex-none sm:px-2 sm:text-xs sm:tracking-[0.08em]"
                  >
                    <span className="font-mono text-warning-foreground">02</span>
                    <span className="sm:hidden">Parsed</span>
                    <span className="hidden sm:inline">Parsed Document</span>
                  </TabsTrigger>
                </TabsList>

                <div className="flex justify-end">
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="min-h-11"
                    disabled={activeDetailTab === "progress" && !isParsedDocumentReady(selectedRunStatus)}
                    onClick={() => setActiveDetailTab(activeDetailTab === "progress" ? "parsed" : "progress")}
                  >
                    {activeDetailTab === "progress" ? (
                      <>Next: Parsed Document <ArrowRight aria-hidden="true" /></>
                    ) : (
                      <><ArrowLeft aria-hidden="true" /> Run Progress</>
                    )}
                  </Button>
                </div>

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
                      {selectedRun.configuration.claimExtractor.provider} · {selectedRun.configuration.embedding.provider} · {selectedRun.configuration.systemOne.provider}
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
                            {parsedDocument.citationContexts.map((context) => (
                              <li key={context.id}>
                                <article className="space-y-3 rounded-lg border border-border bg-muted/20 p-4">
                                  <div className="flex flex-wrap items-center justify-between gap-2 font-mono text-xs text-muted-foreground">
                                    <Badge variant="secondary" className="text-[0.65rem] uppercase">
                                      {context.boundaryKind.replaceAll("_", " ").toLowerCase()}
                                    </Badge>
                                    <span>{context.startOffset}–{context.endOffset}</span>
                                  </div>
                                  <p className="break-words text-sm leading-relaxed">{context.text}</p>
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
                                              <a key={key} className="text-primary underline underline-offset-4 hover:text-primary/80" href={`#bibliography-${key}`}>
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
                            {parsedDocument.bibliographyEntries.map((entry) => (
                              <li key={entry.localReferenceKey} id={`bibliography-${entry.localReferenceKey}`} className="scroll-mt-5 rounded-lg border border-border bg-card p-4">
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
                              </li>
                            ))}
                          </ol>
                        )}
                      </section>
                    </div>
                  )}
                </TabsContent>
              </Tabs>
            </CardContent>
          )}
        </Card>
      </div>
    </section>
  );
}
