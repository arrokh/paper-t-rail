"use client";

import { useRef, useState, type FormEvent } from "react";
import { useIsMutating } from "@tanstack/react-query";
import { ArrowUpRight, LockKeyhole } from "lucide-react";
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
  FieldGroup,
  FieldLabel,
  FieldLegend,
  FieldSet,
} from "@/components/ui/field";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Spinner } from "@/components/ui/spinner";

const PROVIDER_FIELDS = [
  ["claimExtractor", "Claim extraction", "claimExtractorProvider"],
  ["embedding", "Embeddings", "embeddingProvider"],
  ["systemOne", "Evidence assessment", "systemOneProvider"],
  ["scholarlyMetadata", "Bibliography resolution", "scholarlyMetadataProvider"],
  ["openAccess", "Cited full-text access", "openAccessProvider"],
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
  const fileInputRef = useRef<HTMLInputElement>(null);
  const busy = disabled || reanalysisPending || uploadMutation.isPending;

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
        form.reset();
        setSelectedFileName(null);
        onRunCreated(created.analysisRunId);
      },
    });
  }

  const activeError = validationError
    ?? (uploadMutation.error ? errorMessage(uploadMutation.error, "The upload could not be processed.") : null)
    ?? directoryError;

  return (
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
            {PROVIDER_FIELDS.map(([role, label, selectionField]) => {
              const selectId = `provider-${role}`;
              return (
                <Field key={role}>
                  <FieldLabel htmlFor={selectId} className="text-xs font-medium text-foreground">
                    {label}
                  </FieldLabel>
                  <NativeSelect
                    id={selectId}
                    className="w-full [&_[data-slot=native-select]]:h-11"
                    value={selections[selectionField]}
                    disabled={busy || !directory}
                    onChange={(event) => selectProvider(role, event.target.value)}
                  >
                    {directory ? selectableProviderOptions(directory, role).map((provider) => (
                      <NativeSelectOption key={provider.providerId} value={provider.providerId}>
                        {provider.displayName}
                      </NativeSelectOption>
                    )) : (
                      <NativeSelectOption value={selections[selectionField]}>
                        Loading provider choices…
                      </NativeSelectOption>
                    )}
                  </NativeSelect>
                </Field>
              );
            })}
          </FieldGroup>

          {!directory ? (
            <p className="text-sm text-muted-foreground" role="status">
              {directoryLoading ? "Loading provider disclosures…" : "Provider disclosures are unavailable."}
            </p>
          ) : consentRequirements.length === 0 ? (
            <div className="flex gap-3 rounded-lg border border-info-foreground/20 bg-info p-4 text-sm" role="note" aria-live="polite">
              <LockKeyhole className="mt-0.5 size-4 shrink-0 text-info-foreground" aria-hidden="true" />
              <div className="space-y-1">
                <p className="font-medium text-foreground">Local providers selected</p>
                <p className="text-sm leading-relaxed text-muted-foreground">
                  No external provider receives document content for this run.
                </p>
              </div>
            </div>
          ) : consentRequirements.map((provider) => (
            <section
              className="space-y-4 rounded-lg border border-border bg-muted/20 p-4"
              key={provider.providerId}
              aria-labelledby={`consent-${provider.providerId}`}
            >
              <div className="space-y-1">
                <h3 id={`consent-${provider.providerId}`} className="font-medium">
                  {provider.displayName} data access
                </h3>
                <p className="text-sm leading-relaxed text-muted-foreground">May receive in this run:</p>
                {provider.retentionDisclosure && (
                  <p className="text-sm text-warning-foreground">{provider.retentionDisclosure}</p>
                )}
              </div>
              <FieldSet className="min-w-0 gap-3 border-0 p-0">
                <FieldLegend variant="label" className="text-sm">Approve each category to continue</FieldLegend>
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
            <FieldDescription id="source-file-description">English PDFs with selectable text only.</FieldDescription>
          </Field>

          <Button type="submit" size="lg" className="min-h-11 w-full justify-between" disabled={busy || !configurationReady}>
            <span className="inline-flex items-center gap-2">
              {uploadMutation.isPending && <Spinner aria-hidden="true" />}
              {uploadMutation.isPending ? "Starting run…" : "Upload & start Analysis Run"}
            </span>
            {!uploadMutation.isPending && <ArrowUpRight className="size-4" aria-hidden="true" />}
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
  );
}
