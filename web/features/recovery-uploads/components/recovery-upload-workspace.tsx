"use client";

import { useRef, useState } from "react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import type { ParsedDocument } from "@/features/analysis-runs/types";
import {
  useCreateRecoveryBatch,
  useFinalizeRecoveryUpload,
  useRecoveryBatch,
  useRecoveryRightsDeclaration,
  useRemoveRecoveryUpload,
  useUploadRecoveryPdf,
  useRecoveryUploadValidation,
  useValidateRecoveryUpload,
  useConfirmRecoveryIdentity,
  useSelectRecoveryUpload,
} from "@/features/recovery-uploads/queries/recovery-upload-queries";
import type { RecoveryUpload, RecoveryUploadStatus, RecoveryUploadValidation } from "@/features/recovery-uploads/types";
import { LocalDateTime } from "@/components/local-date-time";
import { cn } from "@/lib/utils";

type BibliographyEntry = ParsedDocument["bibliographyEntries"][number];

type RecoveryUploadWorkspaceProps = {
  analysisRunId: string;
  entries: BibliographyEntry[] | null;
  entriesLoading: boolean;
  entriesError: string | null;
  enabled: boolean;
};

function formatBytes(bytes: number): string {
  if (bytes < 1024 * 1024) return `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(bytes / 1024)} KiB`;
  return `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(bytes / (1024 * 1024))} MiB`;
}

function statusLabel(status: RecoveryUploadStatus): string {
  switch (status) {
    case "PENDING_UPLOAD": return "Awaiting browser upload";
    case "FINALIZING": return "Verifying uploaded bytes";
    case "STAGED": return "Verified staging snapshot";
    case "REJECTED": return "Rejected by server validation";
    case "REMOVED": return "Removed";
    case "EXPIRED": return "Expired";
  }
}

function identityOutcomeLabel(outcome: RecoveryUploadValidation["identityOutcome"]): string {
  switch (outcome) {
    case "VALIDATED": return "Validated (machine evidence)";
    case "NEEDS_CONFIRMATION": return "Needs human confirmation";
    case "MISMATCH": return "Mismatch";
    default: return "Identity not assessed";
  }
}

function identityReasonMessage(code: string | null): string {
  switch (code) {
    case "DOI_MATCH": return "The extracted DOI matches the resolved cited work and no title conflict was found. This is machine evidence, not human confirmation.";
    case "CHAPTER_BOOK_IDENTITY_REQUIRES_CONFIRMATION": return "The chapter could not be distinguished from its parent book. Human confirmation is required.";
    case "REFERENCE_IDENTITY_UNRESOLVED": return "This Bibliography Entry has no resolved identity. Human confirmation is required.";
    case "DOI_NOT_EXTRACTED": return "No DOI was extracted. Confirm the cited work before selecting this version.";
    case "DOI_DIFFERS_REQUIRES_CONFIRMATION": return "The extracted DOI differs from the resolved work. It may be another version, so human confirmation is required.";
    case "REFERENCE_DOI_UNAVAILABLE": return "The resolved work has no DOI to compare. Human confirmation is required.";
    case "DOI_TITLE_CONFLICT": return "The DOI matches, but the extracted title conflicts with the cited work. This mismatch cannot be selected.";
    case "MULTIPLE_DOI_CANDIDATES":
    case "MULTIPLE_TITLE_CANDIDATES": return "Docling found conflicting metadata candidates. Human confirmation is required.";
    default: return code ? `Identity reason: ${code}.` : "No identity outcome was produced.";
  }
}

function languageEligibilityLabel(validation: RecoveryUploadValidation): string {
  switch (validation.languageEligibility) {
    case "ELIGIBLE": return `English detected (${validation.detectedLanguage ?? "en"}); this is not evidence assessment.`;
    case "INELIGIBLE": return `The pinned run policy detected ${validation.detectedLanguage ?? "a non-English language"}; this upload is not eligible for English-only assessment.`;
    case "INDETERMINATE": return `Language eligibility is undetermined (${validation.languageReasonCode}).`;
  }
}

function rejectionMessage(code: string | null): string {
  switch (code) {
    case "INVALID_PDF": return "The server could not open this PDF. Incomplete or unreadable PDFs are not supported.";
    case "PDF_ENCRYPTED": return "Encrypted PDFs are not supported for Recovery Upload validation.";
    case "PDF_HAS_NO_PAGES": return "The server rejected this PDF because it has no pages.";
    case "PDF_TOO_MANY_PAGES": return "The server rejected this PDF because it exceeds the configured page limit.";
    case "PDF_NO_EXTRACTABLE_TEXT": return "This PDF has no selectable text. Scanned PDFs are not supported for Recovery Upload validation.";
    case "UPLOAD_SIZE_MISMATCH":
    case "UPLOAD_CHECKSUM_MISMATCH": return "The uploaded bytes did not match the declared file. Remove this upload and try again.";
    default: return code ? `The server rejected this upload (${code}).` : "The server rejected this upload.";
  }
}

export function RecoveryUploadWorkspace({
  analysisRunId,
  entries,
  entriesLoading,
  entriesError,
  enabled,
}: RecoveryUploadWorkspaceProps) {
  const rightsQuery = useRecoveryRightsDeclaration();
  const batchQuery = useRecoveryBatch(analysisRunId, enabled);
  const createBatch = useCreateRecoveryBatch();
  const uploadPdf = useUploadRecoveryPdf();
  const finalizeUpload = useFinalizeRecoveryUpload();
  const removeUpload = useRemoveRecoveryUpload();
  const validateUpload = useValidateRecoveryUpload();
  const confirmIdentity = useConfirmRecoveryIdentity();
  const selectVersion = useSelectRecoveryUpload();
  const [rightsAccepted, setRightsAccepted] = useState(false);
  const batch = batchQuery.data ?? null;
  const rights = rightsQuery.data;
  const isBusy = createBatch.isPending || uploadPdf.isPending || finalizeUpload.isPending || removeUpload.isPending || validateUpload.isPending || confirmIdentity.isPending || selectVersion.isPending;
  const activeError = [createBatch.error, uploadPdf.error, finalizeUpload.error, removeUpload.error, validateUpload.error, confirmIdentity.error, selectVersion.error]
    .find((error): error is Error => error instanceof Error);
  const uploadedByReferenceKey = new Map(batch?.uploads.map((upload) => [upload.localReferenceKey, upload] as const) ?? []);

  function createBatchForRun() {
    if (!rights || !rightsAccepted) return;
    createBatch.reset();
    createBatch.mutate({
      analysisRunId,
      idempotencyKey: globalThis.crypto.randomUUID(),
      rightsDeclarationVersion: rights.version,
      rightsDeclarationAccepted: true,
    });
  }

  function startUpload(localReferenceKey: string, file: File, idempotencyKey: string) {
    if (!batch) return;
    uploadPdf.reset();
    uploadPdf.mutate({ analysisRunId, batchId: batch.id, localReferenceKey, file, idempotencyKey });
  }

  function finalize(batchId: string, uploadId: string) {
    finalizeUpload.reset();
    finalizeUpload.mutate({ analysisRunId, batchId, uploadId });
  }

  function remove(batchId: string, uploadId: string) {
    removeUpload.reset();
    removeUpload.mutate({ analysisRunId, batchId, uploadId });
  }

  function validate(batchId: string, uploadId: string) {
    validateUpload.reset();
    validateUpload.mutate({ analysisRunId, batchId, uploadId });
  }

  function confirm(batchId: string, uploadId: string, validationAttemptId: string) {
    confirmIdentity.reset();
    confirmIdentity.mutate({ analysisRunId, batchId, uploadId, validationAttemptId });
  }

  function select(batchId: string, uploadId: string) {
    selectVersion.reset();
    selectVersion.mutate({ analysisRunId, batchId, uploadId });
  }

  return (
    <section id="recovery-staging" className="space-y-5" aria-labelledby="recovery-staging-heading">
      <header className="space-y-2">
        <p className="font-mono text-xs tracking-[0.13em] text-muted-foreground uppercase">Local recovery workflow</p>
        <h2 id="recovery-staging-heading" className="m-0 font-serif text-2xl font-semibold tracking-tight">Recovery PDF staging</h2>
        <p className="m-0 max-w-3xl text-sm leading-relaxed text-muted-foreground">
          Add a PDF for a Bibliography Entry without changing this immutable Analysis Run. A verified upload is only a user-supplied candidate; it does not confirm the paper identity, language eligibility, legal permission, or support for any claim. Uploading never starts provider assessment.
        </p>
      </header>

      {!enabled && (
        <Alert>
          <AlertTitle>Bibliography is not ready</AlertTitle>
          <AlertDescription>Recovery uploads become available after this Analysis Run has a parsed Bibliography.</AlertDescription>
        </Alert>
      )}

      {enabled && entriesLoading && (
        <Card className="shadow-sm"><CardContent className="flex items-center gap-3 py-6 text-sm text-muted-foreground"><Spinner /> Loading Bibliography Entries…</CardContent></Card>
      )}

      {enabled && !entriesLoading && entries && entries.length === 0 && (
        <Alert>
          <AlertTitle>No Bibliography Entries</AlertTitle>
          <AlertDescription>This parsed Source Document has no Bibliography Entries to recover.</AlertDescription>
        </Alert>
      )}

      {enabled && !entriesLoading && entries && entries.length > 0 && rightsQuery.isPending && (
        <Card className="shadow-sm"><CardContent className="flex items-center gap-3 py-6 text-sm text-muted-foreground"><Spinner /> Loading the current rights declaration…</CardContent></Card>
      )}

      {enabled && !entriesLoading && entries && entries.length > 0 && rightsQuery.isError && (
        <Alert variant="destructive">
          <AlertTitle>Recovery Upload service unavailable</AlertTitle>
          <AlertDescription>{rightsQuery.error instanceof Error ? rightsQuery.error.message : "The current rights declaration could not be loaded."}</AlertDescription>
        </Alert>
      )}

      {enabled && !entriesLoading && entries && entries.length > 0 && rights && batchQuery.isPending && (
        <Card className="shadow-sm"><CardContent className="flex items-center gap-3 py-6 text-sm text-muted-foreground"><Spinner /> Checking for an active Recovery Batch…</CardContent></Card>
      )}

      {enabled && batchQuery.isError && (
        <Alert variant="destructive">
          <AlertTitle>Could not load Recovery Batch</AlertTitle>
          <AlertDescription>{batchQuery.error instanceof Error ? batchQuery.error.message : "Recovery staging state could not be loaded."}</AlertDescription>
        </Alert>
      )}
      {enabled && !batch && createBatch.error instanceof Error && (
        <Alert variant="destructive">
          <AlertTitle>Could not create Recovery Batch</AlertTitle>
          <AlertDescription>{createBatch.error.message}</AlertDescription>
        </Alert>
      )}

      {enabled && !entriesLoading && entries && entries.length > 0 && rights && batchQuery.data === null && !batchQuery.isPending && (
        <Card className="shadow-sm">
          <CardHeader>
            <CardTitle className="font-serif text-lg">Start a temporary Recovery Batch</CardTitle>
            <CardDescription>
              The batch allows up to {rights.maxFilesPerBatch} PDFs and {formatBytes(rights.maxBatchBytes)} total. Each file is limited to {formatBytes(rights.maxFileBytes)}. Upload links last {Math.ceil(rights.uploadUrlTtlSeconds / 60)} minutes; an inactive batch expires after {Math.ceil(rights.inactivityTtlSeconds / 86_400)} days.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <section className="rounded-lg border border-border bg-background p-4" aria-labelledby="rights-declaration-heading">
              <h3 id="rights-declaration-heading" className="m-0 text-sm font-semibold">Rights declaration · {rights.version}</h3>
              <p className="mt-2 mb-0 text-sm leading-relaxed">{rights.text}</p>
              <Field orientation="horizontal" className="mt-4 items-start gap-3">
                <Checkbox
                  id="recovery-rights-accepted"
                  checked={rightsAccepted}
                  disabled={isBusy}
                  onCheckedChange={(checked) => setRightsAccepted(checked === true)}
                />
                <div className="min-w-0 space-y-1">
                  <FieldLabel htmlFor="recovery-rights-accepted" className="text-sm font-medium">I have read and accept this declaration</FieldLabel>
                  <FieldDescription>This records your assertion. It is not a license grant and does not authorize external processing.</FieldDescription>
                </div>
              </Field>
            </section>
            <Button type="button" disabled={!rightsAccepted || isBusy} onClick={createBatchForRun}>
              {createBatch.isPending ? <><Spinner /> Creating batch…</> : "Create Recovery Batch"}
            </Button>
          </CardContent>
        </Card>
      )}

      {enabled && batch && (
        <>
          <Card className="shadow-sm">
            <CardHeader>
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0 space-y-1">
                  <CardTitle className="font-serif text-lg">Active Recovery Batch</CardTitle>
                  <CardDescription>Created <LocalDateTime value={batch.createdAt} /> · last user change <LocalDateTime value={batch.lastActivityAt} /> · expires <LocalDateTime value={batch.expiresAt} /></CardDescription>
                </div>
                <Badge variant="outline">{batch.uploads.filter((upload) => ["PENDING_UPLOAD", "FINALIZING", "STAGED"].includes(upload.status)).length} active uploads</Badge>
              </div>
              <p className="m-0 text-sm leading-relaxed text-muted-foreground">
                Rights declaration {batch.rightsDeclarationVersion} was recorded at <LocalDateTime value={batch.rightsDeclaredAt} />. Reads and polling do not extend expiry. Staged PDFs stay inside this trusted workspace and are not sent to an external provider; no user-account isolation is implied.
              </p>
              <details className="rounded-md border border-border bg-background px-3 py-2 text-sm">
                <summary className="min-h-8 cursor-pointer py-1 font-medium focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">View recorded rights declaration</summary>
                <p className="mb-1 mt-2 leading-relaxed text-muted-foreground">{batch.rightsDeclarationText}</p>
              </details>
            </CardHeader>
            <CardContent className="space-y-3">
              {activeError && (
                <Alert variant="destructive">
                  <AlertTitle>Recovery action did not complete</AlertTitle>
                  <AlertDescription>{activeError.message}</AlertDescription>
                </Alert>
              )}
              {!entries && batch.uploads.length === 0 && (
                <p className="m-0 text-sm text-muted-foreground">Bibliography Entries are unavailable, and this batch has no uploads to display yet.</p>
              )}
              <ul className="m-0 grid list-none gap-3 p-0">
                {entries?.map((entry) => (
                  <RecoveryUploadReferenceCard
                    key={entry.localReferenceKey}
                    batchId={batch.id}
                    localReferenceKey={entry.localReferenceKey}
                    label={entry.title || entry.rawText || `Bibliography Entry ${entry.entryOrder + 1}`}
                    upload={uploadedByReferenceKey.get(entry.localReferenceKey)}
                    maxFileBytes={rights?.maxFileBytes ?? null}
                    canUpload={rights !== undefined}
                    disabled={isBusy}
                    onUpload={startUpload}
                    onFinalize={finalize}
                    onRemove={remove}
                    onValidate={validate}
                    validationPending={validateUpload.isPending && validateUpload.variables?.uploadId === uploadedByReferenceKey.get(entry.localReferenceKey)?.id}
                    onConfirmIdentity={confirm}
                    onSelectVersion={select}
                    identityActionPending={confirmIdentity.isPending || selectVersion.isPending}
                  />
                ))}
                {!entries && batch.uploads.map((upload) => (
                  <RecoveryUploadReferenceCard
                    key={upload.id}
                    batchId={batch.id}
                    localReferenceKey={upload.localReferenceKey}
                    label={`Bibliography Entry · ${upload.localReferenceKey}`}
                    upload={upload}
                    maxFileBytes={rights?.maxFileBytes ?? null}
                    canUpload={rights !== undefined}
                    disabled={isBusy}
                    onUpload={startUpload}
                    onFinalize={finalize}
                    onRemove={remove}
                    onValidate={validate}
                    validationPending={validateUpload.isPending && validateUpload.variables?.uploadId === upload.id}
                    onConfirmIdentity={confirm}
                    onSelectVersion={select}
                    identityActionPending={confirmIdentity.isPending || selectVersion.isPending}
                  />
                ))}
              </ul>
            </CardContent>
          </Card>
        </>
      )}

      {enabled && !entriesLoading && !entries && entriesError && (
        <Alert variant="destructive">
          <AlertTitle>Could not load Bibliography Entries</AlertTitle>
          <AlertDescription>{entriesError}</AlertDescription>
        </Alert>
      )}
    </section>
  );
}

function RecoveryUploadValidationPanel({
  validation,
  batchId,
  uploadId,
  disabled,
  onConfirmIdentity,
  onSelectVersion,
}: {
  validation: RecoveryUploadValidation;
  batchId: string;
  uploadId: string;
  disabled: boolean;
  onConfirmIdentity: (batchId: string, uploadId: string, validationAttemptId: string) => void;
  onSelectVersion: (batchId: string, uploadId: string) => void;
}) {
  const options = Object.entries(validation.parserOptions)
    .map(([name, value]) => `${name}=${value}`)
    .join(" · ");

  return (
    <div className="space-y-3 text-sm">
      {validation.validationStatus === "FAILED" ? (
        <div className="rounded-md border border-destructive/30 bg-destructive/5 p-3 text-destructive">
          <p className="m-0 font-medium">Validation failed</p>
          <p className="mb-0 mt-1">{validation.failureCode ?? "The local parser did not produce a result."} · identity was not assessed.</p>
        </div>
      ) : (
        <div className="rounded-md border border-border p-3">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-medium">Machine identity outcome</span>
            <Badge variant="outline">{identityOutcomeLabel(validation.identityOutcome)}</Badge>
          </div>
          <p className="mb-0 mt-2 leading-relaxed">{identityReasonMessage(validation.identityReasonCode)}</p>
        </div>
      )}

      <div className="rounded-md border border-border p-3">
        <p className="m-0 font-medium">English-language eligibility</p>
        <p className="mb-0 mt-1 leading-relaxed">{languageEligibilityLabel(validation)}</p>
        {validation.languageConfidence !== null && (
          <p className="mb-0 mt-1 text-xs text-muted-foreground">
            Detector {validation.languageDetectorId} {validation.languageDetectorVersion} · confidence {(validation.languageConfidence * 100).toFixed(1)}% · run minimum {(validation.minimumLanguageConfidence * 100).toFixed(1)}%
          </p>
        )}
      </div>

      <div className="rounded-md border border-border p-3">
        <p className="m-0 font-medium">Docling metadata candidates</p>
        {validation.metadataCandidates.length > 0 ? (
          <ul className="m-0 mt-2 grid list-none gap-2 p-0">
            {validation.metadataCandidates.map((candidate, index) => (
              <li key={`${candidate.field}-${candidate.pageNumber}-${candidate.sourceElementId ?? index}`} className="rounded border border-border/70 p-2">
                <p className="m-0 break-words"><span className="font-medium">{candidate.field}:</span> {candidate.value}</p>
                <p className="m-0 mt-1 text-xs text-muted-foreground">
                  Page {candidate.pageNumber} · {candidate.sourceLabel} · {candidate.extractionMethod}
                  {candidate.sourceElementId ? ` · source ${candidate.sourceElementId}` : ""}
                  {candidate.sourceCharSpanStart !== null && candidate.sourceCharSpanEnd !== null
                    ? ` · characters ${candidate.sourceCharSpanStart}–${candidate.sourceCharSpanEnd}`
                    : ""}
                </p>
              </li>
            ))}
          </ul>
        ) : (
          <p className="mb-0 mt-1 text-muted-foreground">No metadata candidates were produced.</p>
        )}
      </div>

      <details className="rounded-md border border-border px-3 py-2">
        <summary className="min-h-7 cursor-pointer py-1 font-medium focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">Parser provenance and options</summary>
        <p className="mb-0 mt-2 break-all text-xs leading-relaxed text-muted-foreground">
          {validation.parserId} {validation.parserVersion} · policy {validation.metadataExtractionPolicyVersion}
          <br />Options: {options || "none recorded"}
          <br />Asset SHA-256: {validation.contentSha256}
        </p>
      </details>
      {validation.validationStatus === "COMPLETED" && (
        <div className="rounded-md border border-border p-3">
          {validation.selection ? (
            <div>
              <p className="m-0 font-medium">Selected exact version</p>
              <p className="mb-0 mt-1 break-all text-xs text-muted-foreground">
                {validation.selection.selectionMethod} · SHA-256 {validation.selection.contentSha256}
              </p>
            </div>
          ) : validation.identityOutcome === "MISMATCH" ? (
            <p className="m-0 text-sm text-destructive">This mismatch cannot be selected.</p>
          ) : validation.identityOutcome === "NEEDS_CONFIRMATION" && !validation.humanConfirmation ? (
            <div className="space-y-2">
              <p className="m-0 leading-relaxed">To continue, confirm that this exact PDF is the cited work or intended version. This records a separate human decision; it does not say the PDF supports a claim.</p>
              <Button type="button" variant="secondary" size="sm" disabled={disabled} onClick={() => onConfirmIdentity(batchId, uploadId, validation.id)}>
                I confirm this exact PDF version
              </Button>
            </div>
          ) : (
            <div className="space-y-2">
              {validation.humanConfirmation && (
                <p className="m-0 text-xs text-muted-foreground">
                  Human confirmation recorded · <LocalDateTime value={validation.humanConfirmation.confirmedAt} /> · exact PDF SHA-256 {validation.humanConfirmation.contentSha256}
                </p>
              )}
              {validation.languageEligibility !== "ELIGIBLE" ? (
                <p className="m-0 text-sm text-muted-foreground">Selection is blocked until the exact PDF is eligible under the Analysis Run&apos;s English-language policy.</p>
              ) : validation.identityOutcome === "VALIDATED" || validation.humanConfirmation ? (
                <Button type="button" variant="secondary" size="sm" disabled={disabled} onClick={() => onSelectVersion(batchId, uploadId)}>
                  Select this exact version
                </Button>
              ) : null}
            </div>
          )}
        </div>
      )}
      <p className="m-0 text-xs leading-relaxed text-muted-foreground">Validation does not record human confirmation or start evidence assessment.</p>
    </div>
  );
}

function RecoveryUploadReferenceCard({
  batchId,
  localReferenceKey,
  label,
  upload,
  maxFileBytes,
  canUpload,
  disabled,
  onUpload,
  onFinalize,
  onRemove,
  onValidate,
  validationPending,
  onConfirmIdentity,
  onSelectVersion,
  identityActionPending,
}: {
  batchId: string;
  localReferenceKey: string;
  label: string;
  upload: RecoveryUpload | undefined;
  maxFileBytes: number | null;
  canUpload: boolean;
  disabled: boolean;
  onUpload: (localReferenceKey: string, file: File, idempotencyKey: string) => void;
  onFinalize: (batchId: string, uploadId: string) => void;
  onRemove: (batchId: string, uploadId: string) => void;
  onValidate: (batchId: string, uploadId: string) => void;
  validationPending: boolean;
  onConfirmIdentity: (batchId: string, uploadId: string, validationAttemptId: string) => void;
  onSelectVersion: (batchId: string, uploadId: string) => void;
  identityActionPending: boolean;
}) {
  const fileInput = useRef<HTMLInputElement>(null);
  const [localError, setLocalError] = useState<string | null>(null);
  const isResumable = upload?.status === "PENDING_UPLOAD" || upload?.status === "FINALIZING";
  const canStartNew = !upload || upload.status === "REMOVED" || upload.status === "EXPIRED";
  const fileInputId = `recovery-file-${batchId}-${encodeURIComponent(localReferenceKey)}`;
  const descriptionId = `${fileInputId}-description`;
  const validationQuery = useRecoveryUploadValidation(batchId, upload?.id ?? "", upload?.status === "STAGED");

  function onFileSelected(file: File | undefined) {
    if (!file) return;
    setLocalError(null);
    if (!file.name.toLocaleLowerCase().endsWith(".pdf")) {
      setLocalError("Choose a file with a .pdf filename.");
      return;
    }
    if (file.size === 0) {
      setLocalError("Choose a non-empty PDF file.");
      return;
    }
    if (maxFileBytes !== null && file.size > maxFileBytes) {
      setLocalError(`This file exceeds the configured ${formatBytes(maxFileBytes)} per-file limit.`);
      return;
    }
    const idempotencyKey = isResumable && upload ? upload.idempotencyKey : globalThis.crypto.randomUUID();
    onUpload(localReferenceKey, file, idempotencyKey);
  }

  return (
    <li className="min-w-0 rounded-lg border border-border bg-background p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1 space-y-1">
          <p className="m-0 font-mono text-xs text-muted-foreground">Bibliography Entry · {localReferenceKey}</p>
          <p className="m-0 line-clamp-4 break-words text-sm leading-relaxed">{label}</p>
        </div>
        {upload && <Badge variant="outline" className="shrink-0">{statusLabel(upload.status)}</Badge>}
      </div>

      {upload?.status === "STAGED" && (
        <div className="mt-3 space-y-1 rounded-md border border-border bg-background p-3 text-sm">
          <p className="m-0 font-medium">Server-verified snapshot</p>
          <p className="m-0 text-foreground">{upload.filename} · {formatBytes(upload.actualSize ?? upload.expectedSize)} · SHA-256 {upload.actualSha256 ?? "not available"}</p>
          <p className="m-0 text-sm leading-relaxed text-foreground">This confirms the uploaded bytes and PDF structure only. It does not establish that this is the cited paper or that it supports a claim.</p>
        </div>
      )}
      {upload?.status === "STAGED" && (
        <section className="mt-3 space-y-2 rounded-md border border-border bg-background p-3" aria-label="Recovery PDF metadata validation" aria-live="polite">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <p className="m-0 text-sm font-medium">Identity and language validation</p>
            {validationQuery.isFetching && <span className="text-xs text-muted-foreground">Loading saved result…</span>}
          </div>
          {validationQuery.isError && (
            <p className="m-0 text-sm text-destructive">{validationQuery.error instanceof Error ? validationQuery.error.message : "Saved validation could not be loaded."}</p>
          )}
          {validationQuery.data && (
            <RecoveryUploadValidationPanel
              validation={validationQuery.data}
              batchId={batchId}
              uploadId={upload.id}
              disabled={disabled || identityActionPending}
              onConfirmIdentity={onConfirmIdentity}
              onSelectVersion={onSelectVersion}
            />
          )}
          <Button
            type="button"
            variant="outline"
            size="sm"
            disabled={disabled || validationPending || validationQuery.isPending || validationQuery.data?.validationStatus === "COMPLETED"}
            onClick={() => onValidate(batchId, upload.id)}
          >
            {validationPending ? <><Spinner /> Validating locally…</> : validationQuery.data?.validationStatus === "FAILED" ? "Retry validation" : validationQuery.data?.validationStatus === "COMPLETED" ? "Validation saved" : "Validate PDF metadata"}
          </Button>
        </section>
      )}
      {upload?.status === "REJECTED" && (
        <p className="mt-3 mb-0 rounded-md border border-destructive/30 bg-destructive/5 p-3 text-sm text-destructive">{rejectionMessage(upload.failureCode)}</p>
      )}
      {upload && (upload.status === "PENDING_UPLOAD" || upload.status === "FINALIZING") && (
        <p className="mt-3 mb-0 text-sm text-muted-foreground">If the browser upload completed before the page closed, verify it below. Otherwise, select the same file to resume with the saved idempotency key.</p>
      )}
      {upload?.status === "REMOVED" && (
        <p className="mt-3 mb-0 text-sm text-muted-foreground">This upload is removed. A cleanup tombstone remains to remove any late or replayed upload.</p>
      )}
      {upload?.status === "EXPIRED" && (
        <p className="mt-3 mb-0 text-sm text-muted-foreground">This upload has expired. Create a new Recovery Batch to stage another PDF.</p>
      )}

      <p id={descriptionId} className="mt-3 mb-0 text-xs leading-relaxed text-muted-foreground">
        {maxFileBytes === null
          ? "Upload limits are unavailable. New browser uploads are disabled until the current policy loads."
          : `Selectable-text PDFs only · scanned PDFs are not supported · up to ${formatBytes(maxFileBytes)} · direct private-storage PUT · server checks actual size, SHA-256, and PDF structure.`}
      </p>
      <div className="mt-3 flex flex-wrap items-center gap-2">
        {canStartNew && canUpload && (
          <>
            <Button type="button" variant="outline" size="sm" disabled={disabled} aria-describedby={descriptionId} onClick={() => fileInput.current?.click()}>
              Choose PDF
            </Button>
            <input
              ref={fileInput}
              id={fileInputId}
              type="file"
              accept="application/pdf,.pdf"
              disabled={disabled}
              className="sr-only"
              aria-label={`Choose PDF for ${label}`}
              onChange={(event) => {
                onFileSelected(event.currentTarget.files?.[0]);
                event.currentTarget.value = "";
              }}
            />
          </>
        )}
        {isResumable && upload && (
          <>
            <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => onFinalize(batchId, upload.id)}>
              {upload.status === "FINALIZING" ? "Retry verification" : "Verify uploaded PDF"}
            </Button>
            {canUpload && (
              <>
                <Button type="button" variant="secondary" size="sm" disabled={disabled} aria-describedby={descriptionId} onClick={() => fileInput.current?.click()}>
                  Resume with same PDF
                </Button>
                <input
                  ref={fileInput}
                  id={fileInputId}
                  type="file"
                  accept="application/pdf,.pdf"
                  disabled={disabled}
                  className="sr-only"
                  aria-label={`Resume PDF for ${label}`}
                  onChange={(event) => {
                    onFileSelected(event.currentTarget.files?.[0]);
                    event.currentTarget.value = "";
                  }}
                />
              </>
            )}
          </>
        )}
        {upload && upload.status !== "REMOVED" && upload.status !== "EXPIRED" && (
          <Button type="button" variant="ghost" size="sm" className={cn("text-muted-foreground hover:text-destructive")} disabled={disabled} onClick={() => onRemove(batchId, upload.id)}>
            Remove upload
          </Button>
        )}
        {upload?.status === "REMOVED" && !canStartNew && <span className="text-xs text-muted-foreground">Removal recorded</span>}
      </div>
      {localError && <p className="mt-3 mb-0 text-sm text-destructive" role="alert">{localError}</p>}
    </li>
  );
}
