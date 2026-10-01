"use client";

import { useLayoutEffect, useRef, useState, type FormEvent } from "react";
import { useIsMutating } from "@tanstack/react-query";
import { ArrowLeft, ArrowRight, Check, CheckCheck, ChevronDown, FileUp, LockKeyhole } from "lucide-react";
import {
  REANALYZE_ANALYSIS_RUN_MUTATION_KEY,
  useUploadAnalysisRun,
} from "@/features/analysis-runs/queries/analysis-run-queries";
import { useProviderConfiguration } from "@/features/providers/provider-configuration-context";
import { selectableProviderOptions } from "@/features/providers/provider-configuration";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import {
  Field,
  FieldDescription,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Spinner } from "@/components/ui/spinner";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";

const PROVIDER_FIELDS = [
  ["claimExtractor", "Claim extraction", "claimExtractorProvider"],
  ["embedding", "Embeddings", "embeddingProvider"],
  ["systemOne", "Evidence assessment", "systemOneProvider"],
  ["scholarlyMetadata", "Bibliography resolution", "scholarlyMetadataProvider"],
  ["openAccess", "Cited full-text access", "openAccessProvider"],
] as const;

const UPLOAD_STEPS = [
  {
    label: "Services",
    title: "Choose your services",
    description: "Provider choices are saved with this Analysis Run. You can review the external services in the next step.",
  },
  {
    label: "Data sharing",
    title: "Review data sharing",
    description: "External providers receive only the categories you approve for this run.",
  },
  {
    label: "Source PDF",
    title: "Select your PDF",
    description: "Choose an English, text-based academic PDF to start the Analysis Run.",
  },
] as const;

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback;
}

export function ProviderConfigurationCard({
  disabled = false,
  onRunCreated,
}: {
  disabled?: boolean;
  onRunCreated: (analysisRunId: string) => void;
}) {
  const {
    directory,
    directoryLoading,
    directoryError,
    selections,
    consentRequirements,
    approvedCategories,
    configurationReady,
    selectProvider,
    approveCategory,
    createConfiguration,
  } = useProviderConfiguration();
  const uploadMutation = useUploadAnalysisRun();
  const reanalysisPending = useIsMutating({ mutationKey: REANALYZE_ANALYSIS_RUN_MUTATION_KEY }) > 0;
  const [validationError, setValidationError] = useState<string | null>(null);
  const [selectedFileName, setSelectedFileName] = useState<string | null>(null);
  const [currentStep, setCurrentStep] = useState(0);
  const stepHeadingRef = useRef<HTMLDivElement>(null);
  const stepContentRef = useRef<HTMLDivElement>(null);
  const shouldFocusStepRef = useRef(false);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const busy = disabled || reanalysisPending || uploadMutation.isPending;

  useLayoutEffect(() => {
    if (!shouldFocusStepRef.current) return;
    shouldFocusStepRef.current = false;
    stepHeadingRef.current?.focus({ preventScroll: true });
  }, [currentStep]);

  function startRun(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fileInput = form.elements.namedItem("file");
    if (!(fileInput instanceof HTMLInputElement) || !fileInput.files?.[0]) {
      setValidationError("Choose an English, text-based PDF to continue.");
      return;
    }

    let configuration;
    try {
      configuration = createConfiguration();
    } catch (cause) {
      setValidationError(errorMessage(cause, "Review provider consent before continuing."));
      return;
    }

    setValidationError(null);
    uploadMutation.mutate({ file: fileInput.files[0], configuration }, {
      onSuccess: (created) => {
        onRunCreated(created.analysisRunId);
      },
    });
  }

  const activeError = validationError
    ?? (uploadMutation.error ? errorMessage(uploadMutation.error, "The upload could not be processed.") : null)
    ?? directoryError;
  const selectedProviders = PROVIDER_FIELDS.map(([role, label, selectionField]) => ({
    role,
    label,
    provider: directory
      ? selectableProviderOptions(directory, role).find((provider) => provider.providerId === selections[selectionField]) ?? null
      : null,
  }));
  const allConsentCategoriesApproved = directory !== null && consentRequirements.every((provider) =>
    provider.dataCategories.every((category) => approvedCategories[provider.providerId]?.includes(category)),
  );
  const consentCategoryCount = consentRequirements.reduce((total, provider) => total + provider.dataCategories.length, 0);
  const approvedCategoryCount = consentRequirements.reduce((total, provider) =>
    total + provider.dataCategories.filter((category) => approvedCategories[provider.providerId]?.includes(category)).length,
  0);

  function goToStep(nextStep: number) {
    setValidationError(null);
    shouldFocusStepRef.current = true;
    if (stepContentRef.current) stepContentRef.current.scrollTop = 0;
    setCurrentStep(nextStep);
  }

  function toggleAllConsentCategories() {
    const nextApproved = !allConsentCategoriesApproved;
    consentRequirements.forEach((provider) => {
      provider.dataCategories.forEach((category) => approveCategory(provider.providerId, category, nextApproved));
    });
  }

  const step = UPLOAD_STEPS[currentStep];

  return (
    <Card className="flex h-full min-h-0 w-full min-w-0 flex-col gap-0 border-0 py-0 shadow-none">
      <CardHeader className="gap-2 border-b border-border/70 px-4 pb-4 pt-4 sm:px-5">
        <p className="font-mono text-xs tracking-[0.12em] text-muted-foreground uppercase">
          Run setup · Step {String(currentStep + 1).padStart(2, "0")} of {UPLOAD_STEPS.length}
        </p>
        <CardTitle ref={stepHeadingRef} role="heading" aria-level={3} tabIndex={-1} className="text-base tracking-tight">{step.title}</CardTitle>
        <p className="text-sm leading-relaxed text-muted-foreground">{step.description}</p>
      </CardHeader>
      <CardContent className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden px-4 pb-0 pt-4 sm:px-5">
        <div ref={stepContentRef} className="min-h-0 min-w-0 flex-1 space-y-5 overflow-y-auto pb-4">
          <ol aria-label="Run setup progress" className="grid min-w-0 grid-cols-3 gap-2">
            {UPLOAD_STEPS.map((item, index) => {
              const isCurrent = currentStep === index;
              const isComplete = currentStep > index;
              return (
                <li
                  key={item.label}
                  aria-current={isCurrent ? "step" : undefined}
                  className={`min-w-0 rounded-md border p-2 sm:p-2.5 ${isCurrent ? "border-primary/30 bg-primary/5" : isComplete ? "border-border bg-muted/50" : "border-border/70 bg-background"}`}
                >
                  <div className="flex min-w-0 items-center gap-1.5 sm:gap-2">
                    <span className={`flex size-5 shrink-0 items-center justify-center rounded-full text-[0.65rem] font-semibold ${isCurrent ? "bg-primary text-primary-foreground" : isComplete ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground"}`}>
                      {isComplete ? <Check className="size-3" aria-hidden="true" /> : index + 1}
                    </span>
                    <span className="min-w-0 break-words text-xs font-medium leading-tight sm:text-sm">{item.label}</span>
                  </div>
                  <span className={`mt-2 block h-1 rounded-full ${isComplete || isCurrent ? "bg-primary" : "bg-muted"}`} aria-hidden="true" />
                </li>
              );
            })}
          </ol>

          {currentStep === 0 && (
            <div className="space-y-5">
              <Collapsible defaultOpen className="rounded-lg border border-border bg-muted/20">
                <CollapsibleTrigger className="group flex min-h-12 w-full items-center justify-between gap-3 rounded-lg px-4 py-3 text-left text-sm font-medium focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
                  <span>Provider settings</span>
                  <ChevronDown className="size-4 text-muted-foreground transition-transform group-data-[open]:rotate-180" aria-hidden="true" />
                </CollapsibleTrigger>
                <CollapsibleContent className="border-t border-border px-4 py-4">
                  <div className="grid gap-4 sm:grid-cols-2">
                    {PROVIDER_FIELDS.map(([role, label, selectionField]) => {
                      const selectId = `provider-${role}`;
                      return (
                        <Field key={role}>
                          <FieldLabel htmlFor={selectId} className="text-xs font-medium text-foreground">{label}</FieldLabel>
                          <NativeSelect
                            id={selectId}
                            className="w-full [&_[data-slot=native-select]]:h-11"
                            value={selections[selectionField]}
                            disabled={busy || !directory}
                            onChange={(event) => selectProvider(role, event.target.value)}
                          >
                            {directory ? selectableProviderOptions(directory, role).map((provider) => (
                              <NativeSelectOption key={provider.providerId} value={provider.providerId}>{provider.displayName}</NativeSelectOption>
                            )) : (
                              <NativeSelectOption value={selections[selectionField]}>Loading provider choices…</NativeSelectOption>
                            )}
                          </NativeSelect>
                        </Field>
                      );
                    })}
                  </div>
                </CollapsibleContent>
              </Collapsible>

              <section className="min-w-0 space-y-3" aria-labelledby="selected-providers-heading">
                <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
                  <h3 id="selected-providers-heading" className="font-mono text-xs tracking-wide text-muted-foreground uppercase">Selected providers</h3>
                  <span className="shrink-0 text-right text-xs text-muted-foreground">Pinned to this run</span>
                </div>
                <ul className="grid min-w-0 gap-2 sm:grid-cols-2">
                  {selectedProviders.map(({ role, label, provider }) => (
                    <li key={role} className="grid min-w-0 grid-cols-[minmax(0,0.85fr)_minmax(0,1.15fr)] items-start gap-3 rounded-md border border-border bg-background px-3 py-2.5 text-xs">
                      <span className="min-w-0 break-words leading-snug text-muted-foreground">{label}</span>
                      <span className="min-w-0 break-words text-right font-medium leading-snug text-foreground">
                        {provider?.displayName ?? "Loading…"}
                        {provider && <span className="mt-0.5 block font-mono text-[0.6rem] font-normal text-muted-foreground">{provider.trustBoundary.toLowerCase()}</span>}
                      </span>
                    </li>
                  ))}
                </ul>
              </section>
              {!directory && (
                <p className="text-sm text-muted-foreground" role="status">
                  {directoryLoading ? "Loading provider choices…" : "Provider choices are unavailable."}
                </p>
              )}
            </div>
          )}

          {currentStep === 1 && (
            <section className="min-w-0 space-y-4" aria-labelledby="consent-heading">
              <div className="space-y-1">
                <h3 id="consent-heading" className="font-mono text-xs tracking-wide text-muted-foreground uppercase">Per-run data sharing approval</h3>
                <p className="text-sm leading-relaxed text-muted-foreground">Review the disclosure for each external provider. Every listed category must be approved before this run can start.</p>
              </div>
              {!directory ? (
                <p className="text-sm text-muted-foreground" role="status">
                  {directoryLoading ? "Loading provider disclosures…" : "Provider disclosures are unavailable."}
                </p>
              ) : consentRequirements.length === 0 ? (
                <div className="flex gap-3 rounded-lg border border-primary/15 bg-primary/5 p-4 text-sm" role="note">
                  <LockKeyhole className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
                  <div className="space-y-1">
                    <p className="font-medium text-foreground">No external providers selected</p>
                    <p className="text-sm leading-relaxed text-muted-foreground">No external provider receives data for this run.</p>
                  </div>
                </div>
              ) : (
                <>
                  {consentRequirements.map((provider) => {
                    const providerApprovedCount = provider.dataCategories.filter((category) =>
                      approvedCategories[provider.providerId]?.includes(category),
                    ).length;
                    return (
                      <section
                        className="min-w-0 space-y-3 rounded-lg border border-warning/30 bg-warning/10 p-4"
                        key={provider.providerId}
                        aria-labelledby={`consent-${provider.providerId}`}
                      >
                        <div className="min-w-0 space-y-1">
                          <h4 id={`consent-${provider.providerId}`} className="break-words font-medium">
                            {provider.displayName} data access
                          </h4>
                          <p className="text-sm leading-relaxed text-muted-foreground">May receive only the categories approved for this run:</p>
                          {provider.retentionDisclosure && (
                            <p className="break-words text-sm leading-relaxed text-warning-foreground">{provider.retentionDisclosure}</p>
                          )}
                        </div>
                        <FieldSet className="min-w-0 gap-3 border-0 p-0">
                          <FieldLegend variant="label" className="text-sm">Approve categories</FieldLegend>
                          {provider.dataCategories.map((categoryId) => {
                            const category = directory.dataCategories.find((item) => item.id === categoryId);
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
                                  <FieldDescription className="break-words text-xs leading-relaxed">
                                    <code className="break-all font-mono text-[0.7rem]">{categoryId}</code>
                                    {category?.description ? ` · ${category.description}` : ""}
                                  </FieldDescription>
                                </div>
                              </Field>
                            );
                          })}
                        </FieldSet>
                        <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-1">
                          <p className="text-xs leading-relaxed text-warning-foreground">Consent applies only to this run and these categories.</p>
                          <span className="shrink-0 font-mono text-[0.65rem] text-muted-foreground" aria-live="polite">
                            {providerApprovedCount} of {provider.dataCategories.length} approved
                          </span>
                        </div>
                      </section>
                    );
                  })}
                  {consentCategoryCount > 0 && (
                    <div className="flex flex-wrap items-center justify-between gap-3 rounded-lg border border-border bg-background p-3 sm:px-4">
                      <p className="text-sm text-muted-foreground" aria-live="polite">
                        {approvedCategoryCount} of {consentCategoryCount} categories approved
                      </p>
                      <Button
                        type="button"
                        variant="outline"
                        size="sm"
                        disabled={busy}
                        aria-label={allConsentCategoriesApproved
                          ? `Clear approvals for all ${consentCategoryCount} categories across external providers`
                          : `Approve all ${consentCategoryCount} categories across external providers`}
                        onClick={toggleAllConsentCategories}
                      >
                        <CheckCheck className="size-4" aria-hidden="true" />
                        {allConsentCategoriesApproved ? "Clear all approvals" : `Approve all ${consentCategoryCount} categories`}
                      </Button>
                    </div>
                  )}
                </>
              )}
            </section>
          )}

          {currentStep === 2 && (
            <form id="start-analysis-run-form" onSubmit={startRun} className="w-full min-w-0 space-y-4">
              <Field className="min-w-0">
                <FieldLabel htmlFor="source-file">Source Document PDF</FieldLabel>
                <div className="grid min-h-11 w-full min-w-0 grid-cols-[auto_minmax(0,1fr)] items-center gap-3 rounded-lg border border-input bg-background px-2.5 py-1">
                  <Button
                    type="button"
                    variant="secondary"
                    size="sm"
                    disabled={busy}
                    aria-describedby="source-file-description"
                    aria-label="Choose a Source Document PDF"
                    onClick={() => fileInputRef.current?.click()}
                  >
                    Choose File
                  </Button>
                  <span className="block min-w-0 max-w-full truncate text-sm text-muted-foreground" aria-live="polite" title={selectedFileName ?? undefined}>
                    {selectedFileName ?? "No file chosen"}
                  </span>
                  <input
                    ref={fileInputRef}
                    id="source-file"
                    name="file"
                    type="file"
                    accept="application/pdf,.pdf"
                    disabled={busy}
                    className="sr-only"
                    onChange={(event) => {
                      setSelectedFileName(event.currentTarget.files?.[0]?.name ?? null);
                      setValidationError(null);
                    }}
                  />
                </div>
                <FieldDescription id="source-file-description">English PDFs with selectable text only. The uploaded Source Document stays in this local installation.</FieldDescription>
              </Field>
              <div className="flex gap-3 rounded-lg border border-primary/15 bg-primary/5 p-4 text-sm" role="note">
                <LockKeyhole className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
                <p className="leading-relaxed text-muted-foreground">
                  Provider selections and the categories you approved are recorded with this Analysis Run. The Source Document stays in this local installation.
                </p>
              </div>
            </form>
          )}

          {activeError && (
            <Alert variant="destructive">
              <AlertTitle>Could not continue</AlertTitle>
              <AlertDescription>{activeError}</AlertDescription>
            </Alert>
          )}
        </div>
        <div className="flex shrink-0 flex-col-reverse gap-2 border-t border-border/70 bg-card py-4 sm:flex-row sm:items-center sm:justify-between">
          {currentStep > 0 ? (
            <Button type="button" variant="outline" disabled={busy} onClick={() => goToStep(currentStep - 1)}>
              <ArrowLeft className="size-4" aria-hidden="true" />
              Back
            </Button>
          ) : <span aria-hidden="true" />}

          {currentStep < UPLOAD_STEPS.length - 1 ? (
            <Button
              key="continue"
              type="button"
              className="min-h-11 sm:min-w-36"
              disabled={busy || (currentStep === 0 && !directory) || (currentStep === 1 && !allConsentCategoriesApproved)}
              onClick={() => goToStep(currentStep + 1)}
            >
              Continue
              <ArrowRight className="size-4" aria-hidden="true" />
            </Button>
          ) : (
            <Button key="submit" type="submit" form="start-analysis-run-form" size="lg" className="min-h-11 w-full justify-center sm:w-auto sm:min-w-64" disabled={busy || !configurationReady}>
              <span className="inline-flex items-center justify-center gap-2">
                {uploadMutation.isPending && <Spinner aria-hidden="true" />}
                {!uploadMutation.isPending && <FileUp className="size-4" aria-hidden="true" />}
                {uploadMutation.isPending ? "Starting run…" : "Upload & start Analysis Run"}
              </span>
            </Button>
          )}
        </div>
      </CardContent>
    </Card>
  );
}
