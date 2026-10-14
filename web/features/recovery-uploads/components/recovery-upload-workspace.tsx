"use client";

import { useRef, useState } from "react";
import { ChevronDown } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Checkbox } from "@/components/ui/checkbox";
import { Field, FieldDescription, FieldLabel } from "@/components/ui/field";
import { Input } from "@/components/ui/input";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Spinner } from "@/components/ui/spinner";
import { LocalDateTime } from "@/components/local-date-time";
import type { AnalysisRun, ParsedDocument } from "@/features/analysis-runs/types";
import {
  useAcceptRecoveryRights,
  useCreateRecoveryBatch,
  useFinalizeRecoveryUpload,
  useRecoveryBatch,
  useRecoveryRightsDeclaration,
  useRemoveRecoveryIdentifierAssistance,
  useRemoveRecoveryUpload,
  useStageRecoveryIdentifierAssistance,
  useUploadRecoveryPdf,
  useRecoveryUploadValidation,
  useValidateRecoveryUpload,
  useConfirmRecoveryIdentity,
  useSelectRecoveryUpload,
} from "@/features/recovery-uploads/queries/recovery-upload-queries";
import type { RecoveryIdentifierAssistance, RecoveryUpload, RecoveryUploadStatus, RecoveryUploadValidation } from "@/features/recovery-uploads/types";
import { RecoverySuccessorWorkflow } from "@/features/recovery-uploads/components/recovery-successor-workflow";
import { cn } from "@/lib/utils";

type BibliographyEntry = ParsedDocument["bibliographyEntries"][number];

const CURRENT_IDENTITY_POLICY_VERSION = "recovery-upload-identity-v2";

type RecoveryUploadWorkspaceProps = {
  analysisRunId: string;
  predecessorConfiguration: AnalysisRun["configuration"];
  predecessorRunStatus: AnalysisRun["status"];
  entries: BibliographyEntry[] | null;
  entriesLoading: boolean;
  entriesError: string | null;
  enabled: boolean;
};

function formatBytes(bytes: number): string {
  if (bytes < 1024 * 1024) return `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 0 }).format(bytes / 1024)} KiB`;
  return `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 1 }).format(bytes / (1024 * 1024))} MiB`;
}

function pdfUploadSelectionError(file: File, maxFileBytes: number | null): string | null {
  if (!file.name.toLocaleLowerCase().endsWith(".pdf")) return "Choose a file with a .pdf filename.";
  if (file.size === 0) return "Choose a non-empty PDF file.";
  if (maxFileBytes !== null && file.size > maxFileBytes) {
    return `This file exceeds the configured ${formatBytes(maxFileBytes)} per-file limit.`;
  }
  return null;
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
    case "TITLE_MATCH_DIFFERENT_DOI": return "The extracted title candidate matches the resolved cited work despite a different DOI. This is machine evidence, not human confirmation.";
    case "CHAPTER_IDENTITY_UNSUPPORTED": return "The upload could not be identified as the cited chapter. Whole-book PDFs are blocked until chapter identity can be established.";
    case "BOOK_CHAPTER_IDENTITY_UNSUPPORTED":
    case "CHAPTER_BOOK_IDENTITY_REQUIRES_CONFIRMATION": return "This Analysis Run cannot distinguish an older book entry from a cited chapter. Inconclusive whole-book uploads are blocked; re-analyze the Source Document with current bibliography classification.";
    case "REFERENCE_IDENTITY_UNRESOLVED": return "This Bibliography Entry has no resolved identity. Human confirmation is required.";
    case "DOI_NOT_EXTRACTED": return "No DOI was extracted. Confirm the cited work before selecting this version.";
    case "DOI_DIFFERS_REQUIRES_CONFIRMATION": return "The extracted DOI differs, and the title evidence does not establish a match. Confirm this exact version before selecting it.";
    case "REFERENCE_DOI_UNAVAILABLE": return "The resolved work has no DOI to compare. Human confirmation is required.";
    case "DOI_TITLE_CONFLICT": return "The extracted title conflicts with the resolved cited work. This mismatch cannot be selected.";
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
  predecessorConfiguration,
  predecessorRunStatus,
  entries,
  entriesLoading,
  entriesError,
  enabled,
}: RecoveryUploadWorkspaceProps) {
  const rightsQuery = useRecoveryRightsDeclaration();
  const batchQuery = useRecoveryBatch(analysisRunId, enabled);
  const createBatch = useCreateRecoveryBatch();
  const acceptRights = useAcceptRecoveryRights();
  const uploadPdf = useUploadRecoveryPdf();
  const finalizeUpload = useFinalizeRecoveryUpload();
  const removeUpload = useRemoveRecoveryUpload();
  const validateUpload = useValidateRecoveryUpload();
  const confirmIdentity = useConfirmRecoveryIdentity();
  const selectVersion = useSelectRecoveryUpload();
  const stageIdentifier = useStageRecoveryIdentifierAssistance();
  const removeIdentifier = useRemoveRecoveryIdentifierAssistance();
  const [rightsAcceptance, setRightsAcceptance] = useState<{ batchId: string; version: string } | null>(null);
  const batch = batchQuery.data ?? null;
  const rights = rightsQuery.data;
  const rightsAcceptanceIsCurrent = Boolean(
    batch && rights && rightsAcceptance?.batchId === batch.id && rightsAcceptance?.version === rights.version,
  );
  const isBusy = createBatch.isPending || acceptRights.isPending || uploadPdf.isPending || finalizeUpload.isPending || removeUpload.isPending || validateUpload.isPending || confirmIdentity.isPending || selectVersion.isPending || stageIdentifier.isPending || removeIdentifier.isPending;
  const activeError = [createBatch.error, acceptRights.error, uploadPdf.error, finalizeUpload.error, removeUpload.error, validateUpload.error, confirmIdentity.error, selectVersion.error, stageIdentifier.error, removeIdentifier.error]
    .find((error): error is Error => error instanceof Error);


  function startUpload(localReferenceKey: string, file: File, idempotencyKey: string) {
    if (!batch || !rights || !batch.rightsDeclaredAt || batch.rightsDeclarationVersion !== rights.version || batch.status !== "OPEN") return;
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

  function confirm(
    batchId: string,
    uploadId: string,
    validationAttemptId: string,
    confirmedDoi?: string,
    doiCandidateFingerprint?: string,
  ) {
    confirmIdentity.reset();
    confirmIdentity.mutate({ analysisRunId, batchId, uploadId, validationAttemptId, confirmedDoi, doiCandidateFingerprint });
  }

  function select(batchId: string, uploadId: string) {
    selectVersion.reset();
    selectVersion.mutate({ analysisRunId, batchId, uploadId });
  }

  return (
    <section id="recovery-staging" className="space-y-5" aria-labelledby="recovery-staging-heading">
      <header className="space-y-2">
        <p className="font-mono text-xs tracking-[0.13em] text-muted-foreground uppercase">Local recovery workflow</p>
        <h2 id="recovery-staging-heading" className="m-0 font-serif text-2xl font-semibold tracking-tight">Recovery and identity assistance</h2>
        <p className="m-0 max-w-3xl text-sm leading-relaxed text-muted-foreground">
          Keep this immutable predecessor Analysis Run unchanged while reviewing candidate PDF versions or asserting a DOI identity. A DOI assertion does not verify an exact PDF, lawful access, language eligibility, or claim support. Staging and preview do not start provider assessment.
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

      {enabled && rightsQuery.isError && !batch && (
        <Alert variant="destructive">
          <AlertTitle>Current PDF-rights declaration unavailable</AlertTitle>
          <AlertDescription>{rightsQuery.error instanceof Error ? rightsQuery.error.message : "PDF upload cannot begin until the current declaration is available."} You may still create an identifier-only batch.</AlertDescription>
        </Alert>
      )}
      {enabled && rightsQuery.isPending && <p className="sr-only" role="status">Loading the current PDF-rights declaration</p>}

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

      {enabled && !entriesLoading && entries && entries.length > 0 && batchQuery.data === null && !batchQuery.isPending && (
        <Card className="shadow-sm">
          <CardHeader>
            <CardTitle className="font-serif text-lg">Start a temporary Recovery Batch</CardTitle>
            <CardDescription>A batch may be created for DOI-only work without accepting PDF rights. Explicit acceptance of the current declaration is required separately before any PDF upload.</CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            {rights && <p className="m-0 text-sm leading-relaxed text-muted-foreground">Up to {rights.maxFilesPerBatch} PDF versions · {formatBytes(rights.maxBatchBytes)} total · {formatBytes(rights.maxFileBytes)} per file · expires after {Math.ceil(rights.inactivityTtlSeconds / 86_400)} inactive days.</p>}
            <Button type="button" disabled={createBatch.isPending} onClick={() => {
              createBatch.reset();
              createBatch.mutate({ analysisRunId, idempotencyKey: globalThis.crypto.randomUUID() });
            }}>
              {createBatch.isPending ? <><Spinner /> Creating identifier-only batch…</> : "Create Recovery Batch"}
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
                  <CardTitle className="font-serif text-lg">{batch.status === "SUBMITTED" ? "Submitted Recovery Batch" : "Recovery Batch"}</CardTitle>
                  <CardDescription>Created <LocalDateTime value={batch.createdAt} /> · last user change <LocalDateTime value={batch.lastActivityAt} /> · expires <LocalDateTime value={batch.expiresAt} /></CardDescription>
                </div>
                <Badge variant="outline">{batch.uploads.filter((upload) => ["PENDING_UPLOAD", "FINALIZING", "STAGED"].includes(upload.status)).length} active PDF versions</Badge>
              </div>
              <p className="m-0 text-sm leading-relaxed text-muted-foreground">DOI-only identity assistance does not require PDF rights. Before any new PDF upload, this batch must record the current declaration. It is not a license grant or provider consent.</p>
              {rights && batch.status === "OPEN" && batch.rightsDeclaredAt && batch.rightsDeclarationVersion !== rights.version ? (
                <Alert className="border-warning/40 bg-warning/20 text-warning-foreground">
                  <AlertTitle>PDF-rights declaration changed</AlertTitle>
                  <AlertDescription>This batch recorded PDF-rights declaration {batch.rightsDeclarationVersion ?? "an earlier version"}, which cannot be changed to {rights.version}. No new PDF version can be uploaded in this batch. Existing candidates may still be reviewed and submitted if the successor preview allows them, and DOI-only assistance remains available. To upload under the current declaration, let this batch expire or submit eligible existing work, then create a new batch and accept {rights.version}.</AlertDescription>
                </Alert>
              ) : rights && batch.rightsDeclaredAt && batch.rightsDeclarationVersion === rights.version ? (
                <p className="m-0 text-sm text-success-foreground" role="status">Current PDF-rights declaration {rights.version} accepted at <LocalDateTime value={batch.rightsDeclaredAt} />.</p>
              ) : rights && batch.status === "OPEN" ? (
                <section className="space-y-3 rounded-md border border-border bg-background p-4" aria-labelledby="recovery-rights-heading">
                  <div className="space-y-1">
                    <h3 id="recovery-rights-heading" className="m-0 text-sm font-semibold">Current PDF-rights declaration · {rights.version}</h3>
                    <p className="m-0 text-sm leading-relaxed">{rights.text}</p>
                  </div>
                  <Field orientation="horizontal" className="items-start gap-3">
                    <Checkbox id="recovery-current-rights-accepted" checked={rightsAcceptanceIsCurrent} disabled={isBusy} onCheckedChange={(checked) => setRightsAcceptance(checked === true ? { batchId: batch.id, version: rights.version } : null)} />
                    <div className="min-w-0 space-y-1">
                      <FieldLabel htmlFor="recovery-current-rights-accepted" className="text-sm font-medium">I have read and accept the current PDF-rights declaration</FieldLabel>
                      <FieldDescription>This records your assertion for PDF upload only. It does not authorize provider processing or state that a DOI identifies a PDF.</FieldDescription>
                    </div>
                  </Field>
                  <Button type="button" variant="secondary" disabled={!rightsAcceptanceIsCurrent || isBusy} onClick={() => {
                    acceptRights.reset();
                    acceptRights.mutate({ analysisRunId, batchId: batch.id, rightsDeclarationVersion: rights.version });
                  }}>
                    {acceptRights.isPending ? <><Spinner /> Recording acceptance…</> : "Accept current declaration for PDF upload"}
                  </Button>
                </section>
              ) : rights ? (
                <p className="m-0 text-sm text-muted-foreground" role="status">This batch no longer accepts PDF-rights changes or uploads.</p>
              ) : (
                <Alert variant="destructive"><AlertTitle>Current PDF-rights declaration unavailable</AlertTitle><AlertDescription>Identifier-only assistance remains available. PDF uploads stay blocked until the current declaration can be displayed and explicitly accepted.</AlertDescription></Alert>
              )}
              {batch.rightsDeclarationText && (
                <Collapsible className="group/recorded-rights rounded-md border border-border bg-background px-3 py-2 text-sm">
                  <CollapsibleTrigger className="flex min-h-8 w-full items-center justify-between gap-2 py-1 text-left font-medium focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
                    View recorded PDF-rights declaration
                    <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[open]/recorded-rights:rotate-180 motion-reduce:transition-none" aria-hidden="true" />
                  </CollapsibleTrigger>
                  <CollapsibleContent className="border-t border-border pt-2">
                    <p className="mb-1 mt-2 leading-relaxed text-muted-foreground">{batch.rightsDeclarationText}</p>
                  </CollapsibleContent>
                </Collapsible>
              )}
            </CardHeader>
            <CardContent className="space-y-3">
              {activeError && (
                <Alert variant="destructive">
                  <AlertTitle>Recovery action did not complete</AlertTitle>
                  <AlertDescription>{activeError.message}</AlertDescription>
                </Alert>
              )}
              {!entries && batch.uploads.length === 0 && batch.identifierAssistance.length === 0 && (
                <p className="m-0 text-sm text-muted-foreground">Bibliography Entries are unavailable, and this batch has no staged work to display yet.</p>
              )}
              <ul className="m-0 grid list-none gap-3 p-0">
                {entries?.map((entry) => {
                  const uploads = batch.uploads.filter((upload) => upload.localReferenceKey === entry.localReferenceKey);
                  const selectedUploadIds = new Set(batch.selectedAssets.map((selection) => selection.uploadId));
                  const assistance = batch.identifierAssistance.find((identifier) => identifier.localReferenceKey === entry.localReferenceKey);
                  return (
                    <RecoveryUploadReferenceCard
                      key={entry.localReferenceKey}
                      batchId={batch.id}
                      localReferenceKey={entry.localReferenceKey}
                      label={entry.title || entry.rawText || `Bibliography Entry ${entry.entryOrder + 1}`}
                      entry={entry}
                      uploads={uploads}
                      selectedUploadIds={selectedUploadIds}
                      identifierAssistance={assistance}
                      maxFileBytes={rights?.maxFileBytes ?? null}
                      canUpload={Boolean(rights && batch.rightsDeclaredAt && batch.rightsDeclarationVersion === rights.version && batch.status === "OPEN")}
                      disabled={isBusy || batch.status !== "OPEN"}
                      onUpload={startUpload}
                      onFinalize={finalize}
                      onRemove={remove}
                      onValidate={validate}
                      validationPendingUploadId={validateUpload.variables?.uploadId}
                      onConfirmIdentity={confirm}
                      onSelectVersion={select}
                      identityActionPending={confirmIdentity.isPending || selectVersion.isPending}
                      onStageIdentifier={(referenceKey, identifierValue) => stageIdentifier.mutate({ analysisRunId, batchId: batch.id, localReferenceKey: referenceKey, identifierValue })}
                      onRemoveIdentifier={(referenceKey) => removeIdentifier.mutate({ analysisRunId, batchId: batch.id, localReferenceKey: referenceKey })}
                      identifierActionPending={stageIdentifier.isPending || removeIdentifier.isPending}
                    />
                  );
                })}
                {!entries && [...new Set(batch.uploads.map((upload) => upload.localReferenceKey))].map((referenceKey) => (
                  <RecoveryUploadReferenceCard
                    key={referenceKey}
                    batchId={batch.id}
                    localReferenceKey={referenceKey}
                    label={`Bibliography Entry · ${referenceKey}`}
                    entry={null}
                    uploads={batch.uploads.filter((upload) => upload.localReferenceKey === referenceKey)}
                    selectedUploadIds={new Set(batch.selectedAssets.map((selection) => selection.uploadId))}
                    identifierAssistance={batch.identifierAssistance.find((identifier) => identifier.localReferenceKey === referenceKey)}
                    maxFileBytes={rights?.maxFileBytes ?? null}
                    canUpload={Boolean(rights && batch.rightsDeclaredAt && batch.rightsDeclarationVersion === rights.version && batch.status === "OPEN")}
                    disabled={isBusy || batch.status !== "OPEN"}
                    onUpload={startUpload}
                    onFinalize={finalize}
                    onRemove={remove}
                    onValidate={validate}
                    validationPendingUploadId={validateUpload.variables?.uploadId}
                    onConfirmIdentity={confirm}
                    onSelectVersion={select}
                    identityActionPending={confirmIdentity.isPending || selectVersion.isPending}
                    onStageIdentifier={(referenceKey, identifierValue) => stageIdentifier.mutate({ analysisRunId, batchId: batch.id, localReferenceKey: referenceKey, identifierValue })}
                    onRemoveIdentifier={(referenceKey) => removeIdentifier.mutate({ analysisRunId, batchId: batch.id, localReferenceKey: referenceKey })}
                    identifierActionPending={stageIdentifier.isPending || removeIdentifier.isPending}
                  />
                ))}
              </ul>
              {!entries && batch.identifierAssistance.length > 0 && (
                <ul className="m-0 grid list-none gap-2 p-0" aria-label="Staged DOI-only identity assistance">
                  {batch.identifierAssistance.map((assistance) => (
                    <li key={assistance.localReferenceKey} className="break-words rounded border border-border bg-background p-3 text-sm">
                      DOI-only assertion for Bibliography Entry · {assistance.localReferenceKey}: {assistance.identifierValue} · confirmed <LocalDateTime value={assistance.recordedAt} />
                    </li>
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>
        </>
      )}
      {enabled && !batchQuery.isPending && (
        <RecoverySuccessorWorkflow key={batch ? `batch-${batch.id}` : "recovery-history"} analysisRunId={analysisRunId} predecessorConfiguration={predecessorConfiguration} predecessorRunStatus={predecessorRunStatus} batch={batch} />
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
  onConfirmIdentity: (batchId: string, uploadId: string, validationAttemptId: string, confirmedDoi?: string, doiCandidateFingerprint?: string) => void;
  onSelectVersion: (batchId: string, uploadId: string) => void;
}) {
  const options = Object.entries(validation.parserOptions)
    .map(([name, value]) => `${name}=${value}`)
    .join(" · ");
  const usesCurrentIdentityPolicy = validation.identityPolicyVersion === CURRENT_IDENTITY_POLICY_VERSION;
  const doiCandidates = validation.metadataCandidates.filter(
    (candidate) => candidate.field === "DOI" && typeof candidate.normalizedDoi === "string" && candidate.normalizedDoi.length > 0,
  );
  const [doiCandidateChoice, setDoiCandidateChoice] = useState<{ validationId: string; fingerprint: string } | null>(null);
  const selectedDoiFingerprint = doiCandidateChoice?.validationId === validation.id ? doiCandidateChoice.fingerprint : "";
  const selectedDoiCandidate = doiCandidates.find((candidate) => candidate.candidateFingerprint === selectedDoiFingerprint);
  const confirmedDoi = validation.humanConfirmation?.identifierConfirmation ?? null;
  const confirmedDoiCandidate = confirmedDoi
    ? doiCandidates.find((candidate) => candidate.candidateFingerprint === confirmedDoi.candidateFingerprint)
    : undefined;
  const needsDoiAssistance = validation.identityReasonCode === "REFERENCE_IDENTITY_UNRESOLVED" && confirmedDoi === null;

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

      <Collapsible className="group/parser-provenance rounded-md border border-border px-3 py-2">
        <CollapsibleTrigger className="flex min-h-7 w-full items-center justify-between gap-2 py-1 text-left font-medium focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
          Parser provenance and options
          <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[open]/parser-provenance:rotate-180 motion-reduce:transition-none" aria-hidden="true" />
        </CollapsibleTrigger>
        <CollapsibleContent className="border-t border-border pt-2">
          <p className="mb-0 mt-2 break-all text-xs leading-relaxed text-muted-foreground">
            {validation.parserId} {validation.parserVersion} · metadata policy {validation.metadataExtractionPolicyVersion} · identity policy {validation.identityPolicyVersion ?? "not reported"}
            <br />Options: {options || "none recorded"}
            <br />Asset SHA-256: {validation.contentSha256}
          </p>
        </CollapsibleContent>
      </Collapsible>
      {validation.validationStatus === "COMPLETED" && !usesCurrentIdentityPolicy ? (
        <div className="rounded-md border border-border p-3 text-muted-foreground" role="status">
          Identity policy is not reported as current. Validate again before confirming or selecting this PDF version.
        </div>
      ) : validation.validationStatus === "COMPLETED" ? (
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
          ) : validation.identityOutcome === "NEEDS_CONFIRMATION" && needsDoiAssistance ? (
            <div className="space-y-3">
              <p className="m-0 leading-relaxed">
                This Bibliography Entry has no resolved work identity. Confirming the PDF version alone cannot identify the cited work. Confirm a DOI only if this candidate from the exact PDF identifies that work; this does not say the PDF supports a claim.
              </p>
              {validation.humanConfirmation && (
                <p className="m-0 text-xs text-muted-foreground">
                  Exact PDF version confirmed · <LocalDateTime value={validation.humanConfirmation.confirmedAt} /> · SHA-256 {validation.humanConfirmation.contentSha256}
                </p>
              )}
              {doiCandidates.length > 0 ? (
                <>
                  <Field>
                    <FieldLabel htmlFor={`recovery-doi-candidate-${uploadId}`}>DOI candidate that identifies the cited work</FieldLabel>
                    <NativeSelect
                      id={`recovery-doi-candidate-${uploadId}`}
                      value={selectedDoiFingerprint}
                      disabled={disabled}
                      onChange={(event) => setDoiCandidateChoice({ validationId: validation.id, fingerprint: event.target.value })}
                      className="w-full"
                    >
                      <NativeSelectOption value="">Choose a DOI candidate</NativeSelectOption>
                      {doiCandidates.map((candidate) => (
                        <NativeSelectOption key={candidate.candidateFingerprint} value={candidate.candidateFingerprint}>
                          {candidate.normalizedDoi} · page {candidate.pageNumber} · {candidate.sourceLabel}
                        </NativeSelectOption>
                      ))}
                    </NativeSelect>
                    <FieldDescription>Check the candidate against the cited work. Its page and source location are evidence; your selection is the identity confirmation.</FieldDescription>
                  </Field>
                  <div className="flex flex-wrap gap-2">
                    <Button
                      type="button"
                      variant="secondary"
                      size="sm"
                      disabled={disabled || !selectedDoiCandidate}
                      onClick={() => {
                        if (!selectedDoiCandidate?.normalizedDoi) return;
                        onConfirmIdentity(
                          batchId,
                          uploadId,
                          validation.id,
                          selectedDoiCandidate.normalizedDoi,
                          selectedDoiCandidate.candidateFingerprint,
                        );
                      }}
                    >
                      {validation.humanConfirmation ? "Confirm this DOI identity" : "Confirm PDF and DOI identity"}
                    </Button>
                    {!validation.humanConfirmation && (
                      <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => onConfirmIdentity(batchId, uploadId, validation.id)}>
                        Confirm PDF version only
                      </Button>
                    )}
                  </div>
                </>
              ) : (
                <>
                  <p className="m-0 text-sm text-muted-foreground">This PDF has no usable DOI candidate. Its work identity remains unresolved, so it cannot be selected for a successor run.</p>
                  {!validation.humanConfirmation && (
                    <Button type="button" variant="outline" size="sm" disabled={disabled} onClick={() => onConfirmIdentity(batchId, uploadId, validation.id)}>
                      Confirm PDF version only
                    </Button>
                  )}
                </>
              )}
            </div>
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
                <div className="text-xs text-muted-foreground">
                  <p className="m-0">Human confirmation recorded · <LocalDateTime value={validation.humanConfirmation.confirmedAt} /> · exact PDF SHA-256 {validation.humanConfirmation.contentSha256}</p>
                  {confirmedDoi && (
                    <p className="m-0 mt-1">
                      DOI identity confirmed: {confirmedDoi.identifierValue}
                      {confirmedDoiCandidate ? ` · page ${confirmedDoiCandidate.pageNumber} · ${confirmedDoiCandidate.sourceLabel}` : " · candidate provenance recorded"}
                    </p>
                  )}
                </div>
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
      ) : null}
      <p className="m-0 text-xs leading-relaxed text-muted-foreground">Validation does not record human confirmation or start evidence assessment.</p>
    </div>
  );
}

const DOI_ASSISTANCE_REFERENCE_TYPES = new Set([
  "JOURNAL_ARTICLE",
  "CONFERENCE_PAPER",
  "PREPRINT",
  "ACADEMIC_MANUSCRIPT",
  "BOOK",
  "BOOK_CHAPTER",
  "INBOOK",
  "INCOLLECTION",
]);

function RecoveryUploadReferenceCard({
  batchId,
  localReferenceKey,
  label,
  entry,
  uploads,
  selectedUploadIds,
  identifierAssistance,
  maxFileBytes,
  canUpload,
  disabled,
  onUpload,
  onFinalize,
  onRemove,
  onValidate,
  validationPendingUploadId,
  onConfirmIdentity,
  onSelectVersion,
  identityActionPending,
  onStageIdentifier,
  onRemoveIdentifier,
  identifierActionPending,
}: {
  batchId: string;
  localReferenceKey: string;
  label: string;
  entry: BibliographyEntry | null;
  uploads: RecoveryUpload[];
  selectedUploadIds: Set<string>;
  identifierAssistance: RecoveryIdentifierAssistance | undefined;
  maxFileBytes: number | null;
  canUpload: boolean;
  disabled: boolean;
  onUpload: (localReferenceKey: string, file: File, idempotencyKey: string) => void;
  onFinalize: (batchId: string, uploadId: string) => void;
  onRemove: (batchId: string, uploadId: string) => void;
  onValidate: (batchId: string, uploadId: string) => void;
  validationPendingUploadId: string | undefined;
  onConfirmIdentity: (batchId: string, uploadId: string, validationAttemptId: string, confirmedDoi?: string, doiCandidateFingerprint?: string) => void;
  onSelectVersion: (batchId: string, uploadId: string) => void;
  identityActionPending: boolean;
  onStageIdentifier: (localReferenceKey: string, identifierValue: string) => void;
  onRemoveIdentifier: (localReferenceKey: string) => void;
  identifierActionPending: boolean;
}) {
  const fileInput = useRef<HTMLInputElement>(null);
  const [localError, setLocalError] = useState<string | null>(null);
  const [identifierValue, setIdentifierValue] = useState(identifierAssistance?.identifierValue ?? entry?.doi ?? "");
  const [confirmsBibliographyIdentity, setConfirmsBibliographyIdentity] = useState(false);
  const canStageDoi = Boolean(entry
    && entry.resolutionStatus === "UNRESOLVED"
    && DOI_ASSISTANCE_REFERENCE_TYPES.has(entry.referenceType));
  const unsupportedDoiType = Boolean(entry && entry.resolutionStatus === "UNRESOLVED" && !canStageDoi);
  const stagedVersionCount = uploads.filter((upload) => upload.status === "STAGED").length;
  const pendingVersionCount = uploads.filter((upload) => upload.status === "PENDING_UPLOAD" || upload.status === "FINALIZING").length;
  const selectedVersionCount = uploads.filter((upload) => selectedUploadIds.has(upload.id)).length;
  const fullTextRecoveryState = selectedVersionCount > 0
    ? `${selectedVersionCount} exact PDF version${selectedVersionCount === 1 ? "" : "s"} selected`
    : stagedVersionCount > 0
      ? `${stagedVersionCount} candidate PDF version${stagedVersionCount === 1 ? "" : "s"} staged; none selected`
      : pendingVersionCount > 0
        ? `${pendingVersionCount} PDF upload${pendingVersionCount === 1 ? "" : "s"} in progress; none selected`
        : "No available PDF version staged";
  const fileInputId = `recovery-new-version-${batchId}-${encodeURIComponent(localReferenceKey)}`;
  const fileDescriptionId = `${fileInputId}-description`;

  function addVersion(file: File | undefined) {
    if (!file) return;
    setLocalError(null);
    const validationError = pdfUploadSelectionError(file, maxFileBytes);
    if (validationError) {
      setLocalError(validationError);
      return;
    }
    onUpload(localReferenceKey, file, globalThis.crypto.randomUUID());
  }

  return (
    <li className="min-w-0 rounded-lg border border-border bg-card p-4">
      <div className="min-w-0 space-y-1">
        <p className="m-0 break-all font-mono text-xs text-muted-foreground">Bibliography Entry · {localReferenceKey}</p>
        <p className="m-0 break-words text-sm leading-relaxed">{label}</p>
      </div>
      {entry ? (
        <dl className="mt-3 grid min-w-0 gap-2 text-xs sm:grid-cols-2 lg:grid-cols-4">
          <div className="min-w-0"><dt className="text-muted-foreground">Bibliography identity</dt><dd className="m-0 break-words">{entry.resolutionStatus.toLocaleLowerCase().replaceAll("_", " ")}</dd></div>
          <div className="min-w-0"><dt className="text-muted-foreground">Reference type</dt><dd className="m-0 break-words">{entry.referenceType.toLocaleLowerCase().replaceAll("_", " ")}</dd></div>
          <div className="min-w-0"><dt className="text-muted-foreground">Bibliography DOI</dt><dd className="m-0 break-all font-mono">{entry.doi ?? "Not recorded"}</dd></div>
          <div className="min-w-0"><dt className="text-muted-foreground">Full-text recovery</dt><dd className="m-0 break-words">{fullTextRecoveryState}</dd></div>
        </dl>
      ) : (
        <p className="mt-2 mb-0 text-xs text-muted-foreground">Bibliography identity and full-text state are unavailable; existing recovery assets remain inspectable.</p>
      )}
      <div className="mt-4 space-y-3">
        {identifierAssistance ? (
          <div className="space-y-2 rounded-md border border-border bg-background p-3" role="status">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant="outline">DOI-only researcher assertion</Badge>
              <span className="break-all font-mono text-sm">{identifierAssistance.identifierValue}</span>
            </div>
            <p className="m-0 text-xs leading-relaxed text-muted-foreground">This assertion identifies the Bibliography Entry only. It is not provider verification, exact PDF identity, lawful access, language eligibility, or claim support.</p>
            <p className="m-0 text-xs text-muted-foreground">Confirmed <LocalDateTime value={identifierAssistance.recordedAt} /></p>
            <Button type="button" variant="ghost" size="sm" disabled={disabled || identifierActionPending} onClick={() => onRemoveIdentifier(localReferenceKey)}>Remove DOI assistance</Button>
          </div>
        ) : null}
        {canStageDoi && (
          <section className="space-y-3 rounded-md border border-border bg-background p-3" aria-label={`DOI-only identity assistance for ${localReferenceKey}`}>
            <div className="space-y-1">
              <p className="m-0 text-sm font-medium">{identifierAssistance ? "Replace DOI-only identity assertion" : "DOI-only identity assistance"}</p>
              <p className="m-0 text-xs leading-relaxed text-muted-foreground">You may assert that a normalized DOI identifies this unresolved Bibliography Entry without uploading a PDF. This does not identify any exact PDF version and does not make a provider-verified match.</p>
            </div>
            <Field>
              <FieldLabel htmlFor={`recovery-asserted-doi-${batchId}-${encodeURIComponent(localReferenceKey)}`}>DOI you assert identifies this Bibliography Entry</FieldLabel>
              <Input
                id={`recovery-asserted-doi-${batchId}-${encodeURIComponent(localReferenceKey)}`}
                value={identifierValue}
                disabled={disabled || identifierActionPending}
                placeholder="10.1234/example"
                autoComplete="off"
                onChange={(event) => {
                  setIdentifierValue(event.currentTarget.value);
                  setConfirmsBibliographyIdentity(false);
                }}
              />
              <FieldDescription>The API normalizes the DOI before recording this assertion. It remains separate from any PDF identity validation.</FieldDescription>
            </Field>
            <Field orientation="horizontal" className="items-start gap-3">
              <Checkbox id={`recovery-confirm-doi-${batchId}-${encodeURIComponent(localReferenceKey)}`} checked={confirmsBibliographyIdentity} disabled={disabled || identifierActionPending} onCheckedChange={(checked) => setConfirmsBibliographyIdentity(checked === true)} />
              <FieldLabel htmlFor={`recovery-confirm-doi-${batchId}-${encodeURIComponent(localReferenceKey)}`} className="text-sm font-medium">I confirm this DOI identifies this Bibliography Entry</FieldLabel>
            </Field>
            <Button type="button" variant="secondary" size="sm" disabled={disabled || identifierActionPending || !confirmsBibliographyIdentity || !identifierValue.trim()} onClick={() => onStageIdentifier(localReferenceKey, identifierValue)}>
              {identifierActionPending ? <><Spinner /> Recording assertion…</> : identifierAssistance ? "Replace DOI identity assertion" : "Stage DOI-only assistance"}
            </Button>
          </section>
        )}
        {unsupportedDoiType && <p className="m-0 text-xs text-muted-foreground">DOI-only assistance is unavailable for this unsupported Bibliography Entry type.</p>}
        {uploads.length > 0 && (
          <div className="space-y-2">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h3 className="m-0 text-sm font-semibold">Candidate PDF versions</h3>
              <p className="m-0 text-xs text-muted-foreground">Each version is validated independently; selecting a new version replaces the current selection without removing older outcomes.</p>
            </div>
            <div className="grid min-w-0 gap-3 sm:grid-cols-2">
              {uploads.map((upload) => (
                <RecoveryUploadVersionCard
                  key={upload.id}
                  batchId={batchId}
                  localReferenceKey={localReferenceKey}
                  label={label}
                  upload={upload}
                  isSelected={selectedUploadIds.has(upload.id)}
                  maxFileBytes={maxFileBytes}
                  canUpload={canUpload && (upload.status === "PENDING_UPLOAD" || upload.status === "FINALIZING")}
                  disabled={disabled}
                  onUpload={onUpload}
                  onFinalize={onFinalize}
                  onRemove={onRemove}
                  onValidate={onValidate}
                  validationPending={validationPendingUploadId === upload.id}
                  onConfirmIdentity={onConfirmIdentity}
                  onSelectVersion={onSelectVersion}
                  identityActionPending={identityActionPending}
                />
              ))}
            </div>
          </div>
        )}
        {canUpload ? (
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" variant="outline" size="sm" disabled={disabled || maxFileBytes === null} aria-describedby={fileDescriptionId} onClick={() => fileInput.current?.click()}>
              Choose another PDF version
            </Button>
            <input
              ref={fileInput}
              id={fileInputId}
              type="file"
              accept="application/pdf,.pdf"
              disabled={disabled || maxFileBytes === null}
              className="sr-only"
              aria-label={`Choose a new PDF version for ${label}`}
              onChange={(event) => {
                addVersion(event.currentTarget.files?.[0]);
                event.currentTarget.value = "";
              }}
            />
            <span id={fileDescriptionId} className="text-xs text-muted-foreground">Current PDF-rights acceptance is recorded before upload intent.</span>
          </div>
        ) : (
          <p className="m-0 text-xs leading-relaxed text-muted-foreground">{maxFileBytes === null ? "Upload limits are unavailable; PDF upload is blocked." : "Accept the current PDF-rights declaration before uploading any PDF version."}</p>
        )}
        {localError && <p className="m-0 text-sm text-destructive" role="alert">{localError}</p>}
      </div>
    </li>
  );
}

function RecoveryUploadVersionCard({
  batchId,
  localReferenceKey,
  label,
  upload,
  isSelected,
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
  isSelected: boolean;
  maxFileBytes: number | null;
  canUpload: boolean;
  disabled: boolean;
  onUpload: (localReferenceKey: string, file: File, idempotencyKey: string) => void;
  onFinalize: (batchId: string, uploadId: string) => void;
  onRemove: (batchId: string, uploadId: string) => void;
  onValidate: (batchId: string, uploadId: string) => void;
  validationPending: boolean;
  onConfirmIdentity: (batchId: string, uploadId: string, validationAttemptId: string, confirmedDoi?: string, doiCandidateFingerprint?: string) => void;
  onSelectVersion: (batchId: string, uploadId: string) => void;
  identityActionPending: boolean;
}) {
  const fileInput = useRef<HTMLInputElement>(null);
  const [localError, setLocalError] = useState<string | null>(null);
  const isResumable = upload?.status === "PENDING_UPLOAD" || upload?.status === "FINALIZING";
  const canStartNew = !upload || upload.status === "REMOVED" || upload.status === "EXPIRED";
  const fileInputId = `recovery-file-${batchId}-${encodeURIComponent(localReferenceKey)}-${upload?.id ?? "new"}`;
  const descriptionId = `${fileInputId}-description`;
  const validationQuery = useRecoveryUploadValidation(batchId, upload?.id ?? "", upload?.status === "STAGED");

  function onFileSelected(file: File | undefined) {
    if (!file) return;
    setLocalError(null);
    const validationError = pdfUploadSelectionError(file, maxFileBytes);
    if (validationError) {
      setLocalError(validationError);
      return;
    }
    const idempotencyKey = isResumable && upload ? upload.idempotencyKey : globalThis.crypto.randomUUID();
    onUpload(localReferenceKey, file, idempotencyKey);
  }

  return (
    <div className="min-w-0 rounded-lg border border-border bg-background p-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1 space-y-1">
          <p className="m-0 break-all font-mono text-xs text-muted-foreground">Bibliography Entry · {localReferenceKey}</p>
          <p className="m-0 line-clamp-4 break-words text-sm leading-relaxed">{label}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {upload && <Badge variant="outline" className="shrink-0">{statusLabel(upload.status)}</Badge>}
          {isSelected && <Badge variant="outline" className="shrink-0 border-success-foreground/30 bg-success text-success-foreground">Current selected exact version</Badge>}
        </div>
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
            disabled={disabled || validationPending || validationQuery.isPending || (validationQuery.data?.validationStatus === "COMPLETED" && validationQuery.data.identityPolicyVersion === CURRENT_IDENTITY_POLICY_VERSION)}
            onClick={() => onValidate(batchId, upload.id)}
          >
            {validationPending ? <><Spinner /> Validating locally…</> : validationQuery.data?.validationStatus === "FAILED" ? "Retry validation" : validationQuery.data?.validationStatus === "COMPLETED" && validationQuery.data.identityPolicyVersion !== CURRENT_IDENTITY_POLICY_VERSION ? "Revalidate with current identity policy" : validationQuery.data?.validationStatus === "COMPLETED" ? "Validation saved" : "Validate PDF metadata"}
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
    </div>
  );
}
