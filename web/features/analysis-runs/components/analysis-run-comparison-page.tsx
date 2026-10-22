"use client";

import { useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { ArrowLeft, ArrowRight, ArrowUpRight, GitCompareArrows } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Button, buttonVariants } from "@/components/ui/button";
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card";
import { LocalDateTime } from "@/components/local-date-time";
import { NativeSelect, NativeSelectOption } from "@/components/ui/native-select";
import { Skeleton } from "@/components/ui/skeleton";
import { useAnalysisRunComparison, useRecentAnalysisRuns } from "@/features/analysis-runs/queries/analysis-run-queries";
import { ANALYSIS_RUN_STATUS_CLASS_NAMES, analysisRunStatusLabel } from "@/features/analysis-runs/run-status";
import type {
  AnalysisRun,
  ComparisonItem,
  ComparisonPair,
  ComparisonRun,
} from "@/features/analysis-runs/types";
import { WorkspaceBreadcrumb } from "@/features/workspace/components/workspace-breadcrumb";
import { PageTransitionLink } from "@/features/workspace/components/page-transition";
import { cn } from "@/lib/utils";

type RunOption = Pick<AnalysisRun, "id" | "filename" | "status" | "createdAt" | "sourceContentSha256">;
type ComparisonSelection = { leftRunId: string; rightRunId: string };

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "The Analysis Run comparison could not be loaded.";
}

function statusBadgeClass(status: string): string {
  return ANALYSIS_RUN_STATUS_CLASS_NAMES[status as AnalysisRun["status"]] ?? "border-border bg-muted text-muted-foreground";
}

function runOptionLabel(run: RunOption): string {
  return `${run.filename} · ${analysisRunStatusLabel(run.status)} · ${run.createdAt.slice(0, 10)}`;
}

function runOptionFromComparison(run: ComparisonRun): RunOption {
  return {
    id: run.analysisRunId,
    filename: run.filename,
    status: run.status as AnalysisRun["status"],
    createdAt: run.createdAt,
    sourceContentSha256: run.sourceContentSha256,
  };
}

function itemLabel(kind: ComparisonItem["changeKind"]): string {
  switch (kind) {
    case "UNCHANGED": return "Unchanged";
    case "CHANGED": return "Changed";
    case "ADDED": return "Added in right run";
    case "REMOVED": return "Removed from right run";
    case "UNMATCHED": return "Unmatched";
  }
}

function unmatchedReasonLabel(reason: string | null): string | null {
  if (reason === "AMBIGUOUS_CANDIDATES") return "Multiple compatible candidates were found. Items are kept separate rather than guessed together.";
  if (reason === "NO_COMPATIBLE_REFERENCE_IDENTITY") return "No compatible reference identity is available to align this item safely.";
  return reason;
}

const CHANGED_FIELD_LABELS: Record<string, string> = {
  sourceClaimAndContext: "Claim text, source context, or span",
  citationTargets: "Citation Targets",
  referenceResolution: "Bibliography Entry or Canonical Paper resolution",
  citedPaperAccess: "Cited Paper access or asset provenance",
  evidencePassages: "Evidence Passage set or ranks",
  machineOutcome: "Machine processing or verification result",
};

function PinnedConfiguration({ run }: { run: ComparisonRun }) {
  const configuration = run.configuration;
  const referenceResolution = configuration.referenceResolution;
  const aggregation = configuration.aggregation;
  const roles = [
    ["Claim extraction", configuration.claimExtractor],
    ["Embedding", configuration.embedding],
    ["Evidence assessment", configuration.systemOne],
    ["Source parser", configuration.sourceParser],
    ["Cited-paper parser", configuration.citedPaperParser],
    ["Open access", configuration.openAccess],
  ] as const;

  return (
    <details className="mt-4 rounded-md border border-border bg-muted/20 p-3">
      <summary className="cursor-pointer font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
        Pinned configuration and provider-consent snapshot
      </summary>
      <div className="mt-3 space-y-4">
        <dl className="grid gap-x-4 gap-y-3 sm:grid-cols-2">
          {roles.map(([label, choice]) => (
            <div key={label} className="min-w-0 space-y-1">
              <dt className="text-xs text-muted-foreground">{label}</dt>
              <dd className="m-0 break-words text-sm">
                {choice ? `${choice.provider}${"model" in choice && choice.model ? ` · ${choice.model}` : ""} · ${choice.version}` : "Not configured"}
              </dd>
            </div>
          ))}
          <div className="min-w-0 space-y-1">
            <dt className="text-xs text-muted-foreground">Reference resolution</dt>
            <dd className="m-0 break-words text-sm">
              {referenceResolution?.provider?.provider
                ?? referenceResolution?.providerChain?.chainId
                ?? "Not configured"}
              {referenceResolution?.scorePolicyVersion ? ` · ${referenceResolution.scorePolicyVersion}` : ""}
              {referenceResolution?.confidenceThreshold !== null && referenceResolution?.confidenceThreshold !== undefined
                ? ` · threshold ${referenceResolution.confidenceThreshold}`
                : ""}
            </dd>
          </div>
          <div className="min-w-0 space-y-1">
            <dt className="text-xs text-muted-foreground">Retrieval profile</dt>
            <dd className="m-0 break-words text-sm">
              {configuration.retrieval.profileId} · top {configuration.retrieval.finalCandidateLimit}
            </dd>
          </div>
          <div className="min-w-0 space-y-1">
            <dt className="text-xs text-muted-foreground">Verification and aggregation policies</dt>
            <dd className="m-0 break-words text-sm">
              {aggregation?.verificationPolicyVersion ?? "Not pinned"} · {aggregation?.aggregationPolicyVersion ?? "Not pinned"}
            </dd>
          </div>
          <div className="min-w-0 space-y-1">
            <dt className="text-xs text-muted-foreground">Language detector</dt>
            <dd className="m-0 break-words text-sm">{configuration.languageDetector.provider} · {configuration.languageDetector.version}</dd>
          </div>
        </dl>
        <div className="space-y-1">
          <h4 className="m-0 text-xs font-medium text-muted-foreground">Per-run external provider consent</h4>
          {configuration.externalProviderConsents?.length ? (
            <ul className="m-0 space-y-1 pl-5 text-sm">
              {configuration.externalProviderConsents.map((consent) => (
                <li key={consent.providerId} className="break-words">
                  {consent.providerId}: {consent.dataCategories.join(", ")} — {consent.retentionDisclosure ?? "No legacy disclosure snapshot"}
                </li>
              ))}
            </ul>
          ) : <p className="m-0 text-sm text-muted-foreground">No external provider consent snapshots.</p>}
        </div>
        <details>
          <summary className="cursor-pointer text-sm font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">
            Full immutable configuration snapshot
          </summary>
          <pre className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-all rounded-md bg-background p-3 font-mono text-xs leading-relaxed">
            {JSON.stringify(configuration, null, 2)}
          </pre>
        </details>
      </div>
    </details>
  );
}

function RunProvenance({ run, label }: { run: ComparisonRun; label: string }) {
  return (
    <Card className="min-w-0 shadow-sm">
      <CardHeader className="gap-2 border-b border-border/70 pb-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <CardTitle className="text-lg">{label}: {run.filename}</CardTitle>
          <Badge variant="outline" className={cn("capitalize", statusBadgeClass(run.status))}>
            {analysisRunStatusLabel(run.status)}
          </Badge>
        </div>
        <PageTransitionLink
          direction="forward"
          href={`/analysis-runs/${encodeURIComponent(run.analysisRunId)}`}
          className={cn(buttonVariants({ variant: "ghost", size: "sm" }), "w-fit px-0")}
        >
          Open original Analysis Run <ArrowUpRight aria-hidden="true" />
        </PageTransitionLink>
      </CardHeader>
      <CardContent className="space-y-3">
        <dl className="grid min-w-0 gap-x-4 gap-y-3 sm:grid-cols-2">
          <div className="min-w-0 space-y-1"><dt className="text-xs text-muted-foreground">Analysis Run ID</dt><dd className="m-0 break-all font-mono text-xs">{run.analysisRunId}</dd></div>
          <div className="min-w-0 space-y-1"><dt className="text-xs text-muted-foreground">Source Document ID</dt><dd className="m-0 break-all font-mono text-xs">{run.sourceDocumentId}</dd></div>
          <div className="space-y-1"><dt className="text-xs text-muted-foreground">Created</dt><dd className="m-0 text-sm"><LocalDateTime value={run.createdAt} /></dd></div>
          <div className="space-y-1"><dt className="text-xs text-muted-foreground">Started</dt><dd className="m-0 text-sm"><LocalDateTime value={run.startedAt} /></dd></div>
          <div className="min-w-0 space-y-1 sm:col-span-2">
            <dt className="text-xs text-muted-foreground">Source Document SHA-256</dt>
            <dd className="m-0 break-all font-mono text-xs leading-relaxed">{run.sourceContentSha256}</dd>
          </div>
          <div className="space-y-1"><dt className="text-xs text-muted-foreground">Reference resolution processing</dt><dd className="m-0 text-sm">{run.referenceResolutionExecutionStatus}</dd></div>
          <div className="space-y-1"><dt className="text-xs text-muted-foreground">Evidence processing</dt><dd className="m-0 text-sm">{run.evidenceCoverageExecutionStatus}</dd></div>
          <div className="min-w-0 space-y-1 sm:col-span-2">
            <dt className="text-xs text-muted-foreground">Worker pipeline status</dt>
            <dd className="m-0 break-words text-sm">
              {run.pipeline?.stages.length
                ? run.pipeline.stages.map((stage) => `${stage.id}: ${stage.status}`).join(" · ")
                : "No persisted worker-stage history"}
            </dd>
          </div>
        </dl>
        {run.failureReason && <p className="m-0 break-words text-sm text-warning-foreground">Recorded processing detail: {run.failureReason}</p>}
        {run.pipeline?.stages.length ? (
          <details className="rounded-md border border-border bg-muted/20 p-3">
            <summary className="cursor-pointer font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring">Worker stage warnings and incomplete work</summary>
            <ol className="m-0 mt-3 space-y-3 pl-5 text-sm">
              {run.pipeline.stages.map((stage) => (
                <li key={stage.id} className="min-w-0">
                  <p className="m-0 break-words font-medium">{stage.id}: {stage.status} · {stage.counts.completed}/{stage.counts.total} complete · {stage.counts.waiting} waiting · {stage.counts.inProgress} in progress · {stage.counts.skipped} skipped · {stage.counts.failed} failed</p>
                  {stage.steps.filter((step) => step.status !== "COMPLETED" || step.items.some((item) => item.reasonCode)).map((step) => (
                    <ul key={step.id} className="m-0 mt-1 space-y-1 pl-5 text-xs text-muted-foreground">
                      <li>{step.id}: {step.status}</li>
                      {step.items.filter((item) => item.reasonCode).map((item) => <li key={item.id}>{item.label}: {item.status} · {item.reasonCode}</li>)}
                    </ul>
                  ))}
                </li>
              ))}
            </ol>
          </details>
        ) : null}
        <PinnedConfiguration run={run} />
      </CardContent>
    </Card>
  );
}

function PairSide({ pair, label }: { pair: ComparisonPair; label: string }) {
  const reference = pair.reference;
  const access = pair.citedPaperAccess;
  return (
    <section className="min-w-0 space-y-4 rounded-lg border border-border bg-card p-4" aria-label={`${label} result`}>
      <h3 className="m-0 break-words font-heading text-base font-semibold">{label}: {reference.title ?? (reference.rawText || "Cited Reference")}</h3>
      <dl className="grid min-w-0 gap-3 text-sm">
        <div className="min-w-0 space-y-1">
          <dt className="text-xs font-medium text-muted-foreground">Atomic Claim and source span</dt>
          <dd className="m-0 break-words leading-relaxed">{pair.claimText}</dd>
          <dd className="m-0 font-mono text-xs text-muted-foreground">UTF-16 offsets {pair.sourceStartOffset}–{pair.sourceEndOffset}</dd>
        </div>
        <div className="min-w-0 space-y-1"><dt className="text-xs font-medium text-muted-foreground">Citation Context</dt><dd className="m-0 break-words leading-relaxed">{pair.citationContextText}</dd></div>
        <div className="min-w-0 space-y-1">
          <dt className="text-xs font-medium text-muted-foreground">Citation Targets</dt>
          {pair.citationTargets.length ? (
            <ul className="m-0 list-disc space-y-1 pl-5">
              {pair.citationTargets.map((target, index) => <li key={`${target.markerText}-${index}`}>{target.markerText} · {target.associationKind}</li>)}
            </ul>
          ) : <p className="m-0 text-muted-foreground">No Citation Targets recorded.</p>}
        </div>
        <div className="min-w-0 space-y-1">
          <dt className="text-xs font-medium text-muted-foreground">Cited Reference</dt>
          <dd className="m-0 break-words leading-relaxed">
            {reference.canonicalPaper
              ? <>Canonical Paper: {reference.canonicalPaper.title} ({reference.canonicalPaper.id})</>
              : <>No Canonical Paper identity ({reference.resolutionStatus}); Bibliography Entry: {reference.rawText || reference.localReferenceKey}</>}
          </dd>
          <dd className="m-0 break-words text-xs text-muted-foreground">
            Resolution: {reference.resolutionStatus}
            {reference.resolutionReasonCode ? ` · ${reference.resolutionReasonCode}` : ""}
            {reference.matchMethod ? ` · match method ${reference.matchMethod}` : ""}
          </dd>
          {reference.doi && <dd className="m-0 break-all font-mono text-xs">DOI: {reference.doi}</dd>}
        </div>
      </dl>

      <section className="min-w-0 space-y-2 border-t border-border pt-3" aria-label={`${label} Cited Paper access`}>
        <h4 className="m-0 text-sm font-semibold">Cited Paper access and asset</h4>
        {access ? (
          <dl className="grid min-w-0 gap-2 text-sm sm:grid-cols-2">
            <div><dt className="text-xs text-muted-foreground">Access outcome</dt><dd className="m-0">{access.accessStatus} · {access.accessOrigin}</dd></div>
            <div><dt className="text-xs text-muted-foreground">Access processing</dt><dd className="m-0">{access.accessProgressStatus ?? "Not recorded"}{access.accessProgressReason ? ` · ${access.accessProgressReason}` : ""}</dd></div>
            {access.accessReason && <div className="sm:col-span-2"><dt className="text-xs text-muted-foreground">Access reason</dt><dd className="m-0 break-words">{access.accessReason}{access.accessReasons.length ? ` · ${access.accessReasons.join(", ")}` : ""}</dd></div>}
            <div className="min-w-0 sm:col-span-2"><dt className="text-xs text-muted-foreground">Exact cited asset identity</dt><dd className="m-0 break-all font-mono text-xs">{access.sourceAssetId ?? "No asset ID recorded"}</dd></div>
            <div className="min-w-0 sm:col-span-2"><dt className="text-xs text-muted-foreground">Cited asset SHA-256</dt><dd className="m-0 break-all font-mono text-xs">{access.contentSha256 ?? "No asset hash recorded"}</dd></div>
            <div className="sm:col-span-2"><dt className="text-xs text-muted-foreground">Asset parsing and language</dt><dd className="m-0 break-words">{access.parserProvider ? `${access.parserProvider} ${access.parserVersion ?? ""} · ${access.language ?? "language not recorded"} (${access.languageDetectorVersion ?? "detector not recorded"})` : "Not indexed or no legacy parser provenance"}</dd></div>
          </dl>
        ) : <p className="m-0 text-sm text-muted-foreground">No persisted Cited Paper Access outcome.</p>}
      </section>

      <section className="min-w-0 space-y-2 border-t border-border pt-3" aria-label={`${label} Evidence Passages`}>
        <h4 className="m-0 text-sm font-semibold">Evidence Passages ({pair.evidencePassages.length})</h4>
        <p className="m-0 text-xs leading-relaxed text-muted-foreground">Retrieved passages are candidates for researcher inspection, not automatic support or contradiction findings.</p>
        {pair.evidencePassages.length ? (
          <ol className="m-0 space-y-3 pl-5">
            {pair.evidencePassages.map((passage, index) => (
              <li key={`${passage.sourceAssetId}-${passage.fusedRank}-${index}`} className="min-w-0 space-y-1">
                <p className="m-0 text-xs font-medium">Fused rank {passage.fusedRank} · vector {passage.vectorRank ?? "—"} · lexical {passage.lexicalRank ?? "—"}</p>
                <p className="m-0 break-words text-sm leading-relaxed">{passage.text}</p>
                <p className="m-0 break-words text-xs text-muted-foreground">
                  Section {passage.sectionOrder}{passage.sectionHeading ? ` · ${passage.sectionHeading}` : ""} · paragraphs {passage.paragraphStart}–{passage.paragraphEnd}{passage.pageNumber ? ` · page ${passage.pageNumber}` : ""}
                  {passage.judgement ? ` · machine judgement ${passage.judgement}` : ""}{passage.evidenceRole ? ` · ${passage.evidenceRole}` : ""}
                </p>
                <p className="m-0 break-all font-mono text-[0.7rem] text-muted-foreground">Asset {passage.sourceAssetId} · SHA-256 {passage.contentSha256}</p>
              </li>
            ))}
          </ol>
        ) : <p className="m-0 text-sm text-muted-foreground">No Evidence Passages are persisted for this pair.</p>}
      </section>

      <section className="min-w-0 space-y-2 border-t border-border pt-3" aria-label={`${label} machine outcome`}>
        <h4 className="m-0 text-sm font-semibold">Machine processing and outcome</h4>
        <p className="m-0 text-sm">
          Processing: <Badge variant="outline" className="align-middle">{pair.processingStatus}</Badge>
        </p>
        {pair.processingFailureReason && <p className="m-0 break-words text-sm text-warning-foreground">Incomplete reason: {pair.processingFailureReason}</p>}
        {pair.processingStatus === "INCOMPLETE" ? (
          <p className="m-0 text-sm text-muted-foreground">Processing is incomplete; no final domain status is shown.</p>
        ) : pair.finalStatus ? (
          <p className="m-0 text-sm">Final Claim–Paper Verification status: <strong>{pair.finalStatus.replaceAll("_", " ")}</strong></p>
        ) : <p className="m-0 text-sm text-muted-foreground">No final domain status is persisted yet.</p>}
        {pair.verificationScope && <p className="m-0 text-xs text-muted-foreground">Verification scope: {pair.verificationScope}{pair.terminalReason ? ` · ${pair.terminalReason}` : ""}</p>}
      </section>

      <section className="min-w-0 space-y-2 border-t border-border pt-3" aria-label={`${label} Human Reviews`}>
        <h4 className="m-0 text-sm font-semibold">Human Reviews — {label} only</h4>
        <p className="m-0 text-xs text-muted-foreground">These append-only reviews remain attached to this run&apos;s Verification and are not compared or applied to the other run.</p>
        {pair.humanReviews.length ? (
          <ul className="m-0 space-y-2 pl-5">
            {pair.humanReviews.map((review) => (
              <li key={review.id} className="break-words text-sm">
                <strong>{review.action.replaceAll("_", " ")}</strong>
                {review.overrideStatus ? ` · separate review status ${review.overrideStatus.replaceAll("_", " ")}` : ""}
                {review.note ? <p className="m-0 mt-1 whitespace-pre-wrap">{review.note}</p> : null}
                <p className="m-0 mt-1 text-xs text-muted-foreground">Recorded <LocalDateTime value={review.createdAt} /> · originating run {review.analysisRunId}</p>
              </li>
            ))}
          </ul>
        ) : <p className="m-0 text-sm text-muted-foreground">No Human Reviews recorded for this Verification in this run.</p>}
      </section>
    </section>
  );
}

function ComparisonItemView({ item, index }: { item: ComparisonItem; index: number }) {
  const unmatchedReason = unmatchedReasonLabel(item.unmatchedReason);
  return (
    <li className="min-w-0 list-none rounded-lg border border-border bg-card p-4 shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0 space-y-1">
          <h3 className="m-0 font-heading text-base font-semibold">Pair {index + 1}</h3>
          <p className="m-0 text-sm text-muted-foreground">{itemLabel(item.changeKind)}</p>
        </div>
        <Badge variant="outline">{item.changeKind}</Badge>
      </div>
      {unmatchedReason && <p className="mt-3 text-sm text-warning-foreground">{unmatchedReason}</p>}
      {item.changedFields.length > 0 && (
        <p className="mt-3 text-sm"><strong>Changed:</strong> {item.changedFields.map((field) => CHANGED_FIELD_LABELS[field] ?? field).join("; ")}</p>
      )}
      {item.outcomeChanged && <p className="mt-2 text-sm font-medium">Machine processing state or final outcome changed.</p>}
      <div className="mt-4 grid min-w-0 gap-3 xl:grid-cols-2">
        {item.left
          ? <PairSide pair={item.left} label="Left run" />
          : <p className="m-0 rounded-md border border-dashed border-border p-4 text-sm text-muted-foreground">No aligned item in the left run.</p>}
        {item.right
          ? <PairSide pair={item.right} label="Right run" />
          : <p className="m-0 rounded-md border border-dashed border-border p-4 text-sm text-muted-foreground">No aligned item in the right run.</p>}
      </div>
    </li>
  );
}

function ComparisonResults({
  left,
  right,
  counts,
  items,
  disclaimer,
  sourceContentDiffers,
  sourceDocumentDiffers,
  analysisConfigurationDiffers,
  alignmentPolicy,
}: {
  left: ComparisonRun;
  right: ComparisonRun;
  counts: { unchanged: number; changed: number; added: number; removed: number; unmatched: number; outcomeChanges: number };
  items: ComparisonItem[];
  disclaimer: string;
  sourceContentDiffers: boolean;
  sourceDocumentDiffers: boolean;
  analysisConfigurationDiffers: boolean;
  alignmentPolicy: { id: string; version: string; description: string };
}) {
  return (
    <section className="space-y-5" aria-labelledby="comparison-results-heading">
      <Alert>
        <GitCompareArrows aria-hidden="true" />
        <AlertTitle>Read-only comparison · alignment policy {alignmentPolicy.id} v{alignmentPolicy.version}</AlertTitle>
        <AlertDescription>{disclaimer}</AlertDescription>
      </Alert>
      <div className="grid min-w-0 gap-4 xl:grid-cols-2">
        <RunProvenance run={left} label="Left run" />
        <RunProvenance run={right} label="Right run" />
      </div>
      <Card className="shadow-sm">
        <CardHeader className="border-b border-border/70 pb-4">
          <CardTitle id="comparison-results-heading" className="text-xl">Comparison overview</CardTitle>
          <p className="m-0 text-sm leading-relaxed text-muted-foreground">
            Source content {sourceContentDiffers ? "differs" : "matches"}; Source Document record {sourceDocumentDiffers ? "differs" : "is the same"}; pinned analysis configuration {analysisConfigurationDiffers ? "differs" : "matches"}.
          </p>
          <p className="m-0 text-xs leading-relaxed text-muted-foreground">{alignmentPolicy.description}</p>
        </CardHeader>
        <CardContent className="space-y-5">
          <dl className="grid grid-cols-2 gap-3 sm:grid-cols-3 xl:grid-cols-6">
            {([
              ["Unchanged", counts.unchanged],
              ["Changed", counts.changed],
              ["Added", counts.added],
              ["Removed", counts.removed],
              ["Unmatched", counts.unmatched],
              ["Outcome changes", counts.outcomeChanges],
            ] as const).map(([label, value]) => (
              <div key={label} className="min-w-0 rounded-md border border-border bg-muted/20 p-3">
                <dt className="text-xs leading-relaxed text-muted-foreground">{label}</dt>
                <dd className="m-0 font-mono text-xl font-semibold">{value}</dd>
              </div>
            ))}
          </dl>
          {items.length ? (
            <ol className="m-0 space-y-4 p-0">
              {items.map((item, index) => <ComparisonItemView key={`${item.changeKind}-${item.left?.atomicClaimId ?? "none"}-${item.right?.atomicClaimId ?? "none"}-${index}`} item={item} index={index} />)}
            </ol>
          ) : <p className="m-0 rounded-md border border-dashed border-border p-6 text-center text-sm text-muted-foreground">Neither Analysis Run has an Atomic Claim × Cited Reference pair to compare.</p>}
        </CardContent>
      </Card>
    </section>
  );
}

export function AnalysisRunComparisonPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [cursor, setCursor] = useState<string | null>(null);
  const [pageNumber, setPageNumber] = useState(1);
  const [leftRunId, setLeftRunId] = useState(() => searchParams.get("leftRunId") ?? "");
  const [rightRunId, setRightRunId] = useState(() => searchParams.get("rightRunId") ?? "");
  const [selectedOptions, setSelectedOptions] = useState<Record<string, RunOption>>({});
  const [comparisonSelection, setComparisonSelection] = useState<ComparisonSelection | null>(() => {
    const left = searchParams.get("leftRunId");
    const right = searchParams.get("rightRunId");
    return left && right ? { leftRunId: left, rightRunId: right } : null;
  });
  const runsQuery = useRecentAnalysisRuns(cursor);
  const runPage = runsQuery.data;
  const runs = runPage?.items ?? [];
  const comparisonQuery = useAnalysisRunComparison(
    comparisonSelection?.leftRunId ?? null,
    comparisonSelection?.rightRunId ?? null,
  );
  const comparison = comparisonQuery.data;
  const currentOptions = new Map<string, RunOption>();
  runs.forEach((run) => currentOptions.set(run.id, run));
  Object.values(selectedOptions).forEach((run) => currentOptions.set(run.id, run));
  if (comparison) {
    currentOptions.set(comparison.leftRun.analysisRunId, runOptionFromComparison(comparison.leftRun));
    currentOptions.set(comparison.rightRun.analysisRunId, runOptionFromComparison(comparison.rightRun));
  }
  const options = [...currentOptions.values()];
  const canCompare = Boolean(leftRunId && rightRunId && leftRunId !== rightRunId);
  const runListError = runsQuery.error ? errorMessage(runsQuery.error) : null;
  const compareError = comparisonQuery.isError ? errorMessage(comparisonQuery.error) : null;

  function selectRun(side: "left" | "right", runId: string) {
    const option = runs.find((run) => run.id === runId);
    if (option) setSelectedOptions((current) => ({ ...current, [runId]: option }));
    if (side === "left") setLeftRunId(runId);
    else setRightRunId(runId);
    setComparisonSelection(null);
  }

  function submitComparison() {
    if (!canCompare) return;
    const selection = { leftRunId, rightRunId };
    setComparisonSelection(selection);
    const params = new URLSearchParams({ ...selection });
    router.replace(`/analysis-runs/compare?${params.toString()}`, { scroll: false });
  }

  function changePage(nextCursor: string | null, direction: -1 | 1) {
    setCursor(nextCursor);
    setPageNumber((current) => Math.max(1, current + direction));
  }

  return (
    <section className="analysis-run-comparison space-y-6" aria-labelledby="analysis-run-comparison-heading">
      <WorkspaceBreadcrumb items={[{ label: "Workspace", href: "/" }, { label: "Analysis Runs", href: "/" }, { label: "Compare Analysis Runs" }]} />
      <header className="space-y-2">
        <p className="font-mono text-xs tracking-[0.13em] text-muted-foreground uppercase">Read-only exploration</p>
        <h1 id="analysis-run-comparison-heading" className="font-serif text-3xl font-semibold tracking-tight sm:text-4xl">Compare Analysis Runs</h1>
        <p className="max-w-3xl text-sm leading-relaxed text-muted-foreground">
          Select two saved runs to inspect differences in claim-to-citation results, reference resolution, cited-paper access, Evidence Passages, machine processing, and provenance. Original reports remain independently accessible.
        </p>
      </header>
      <Alert>
        <AlertTitle>Triage comparison, not a quality score</AlertTitle>
        <AlertDescription>
          This view does not certify truth, assess the whole paper, or call a later run better. Model confidence is not calibrated. Human Reviews remain separate and attached to their originating run; they are never used to compare machine results.
        </AlertDescription>
      </Alert>

      <Card className="shadow-sm">
        <CardHeader className="border-b border-border/70 pb-4">
          <CardTitle className="text-lg">Choose two Analysis Runs</CardTitle>
          <p className="m-0 text-sm text-muted-foreground">Run choices follow the saved Analysis Run list. Use its cursor controls to reach older runs.</p>
        </CardHeader>
        <CardContent className="space-y-4">
          {runListError && <Alert variant="destructive"><AlertTitle>Could not load Analysis Runs</AlertTitle><AlertDescription>{runListError}</AlertDescription></Alert>}
          {runsQuery.isPending ? (
            <div role="status" aria-label="Loading Analysis Runs" className="space-y-3"><Skeleton className="h-11 w-full" /><Skeleton className="h-11 w-full" /></div>
          ) : (
            <div className="grid min-w-0 gap-4 md:grid-cols-2">
              <label className="grid min-w-0 gap-1.5 text-sm font-medium">
                Left Analysis Run
                <NativeSelect value={leftRunId} onChange={(event) => selectRun("left", event.currentTarget.value)} className="w-full" selectClassName="h-11">
                  <NativeSelectOption value="">Choose a run</NativeSelectOption>
                  {options.map((run) => <NativeSelectOption key={run.id} value={run.id}>{runOptionLabel(run)}</NativeSelectOption>)}
                </NativeSelect>
              </label>
              <label className="grid min-w-0 gap-1.5 text-sm font-medium">
                Right Analysis Run
                <NativeSelect value={rightRunId} onChange={(event) => selectRun("right", event.currentTarget.value)} className="w-full" selectClassName="h-11">
                  <NativeSelectOption value="">Choose a run</NativeSelectOption>
                  {options.map((run) => <NativeSelectOption key={run.id} value={run.id}>{runOptionLabel(run)}</NativeSelectOption>)}
                </NativeSelect>
              </label>
            </div>
          )}
          {leftRunId && leftRunId === rightRunId && <p className="m-0 text-sm text-warning-foreground">Choose two different Analysis Runs.</p>}
          <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
            <nav className="flex items-center gap-2" aria-label="Analysis Run selection pages">
              <span className="mr-1 text-xs text-muted-foreground">Run list page {pageNumber}</span>
              <Button type="button" variant="outline" className="min-h-11" disabled={!runPage?.previousCursor || runsQuery.isFetching} onClick={() => changePage(runPage?.previousCursor ?? null, -1)}>
                <ArrowLeft aria-hidden="true" /> Previous
              </Button>
              <Button type="button" variant="outline" className="min-h-11" disabled={!runPage?.nextCursor || runsQuery.isFetching} onClick={() => changePage(runPage?.nextCursor ?? null, 1)}>
                Next <ArrowRight aria-hidden="true" />
              </Button>
            </nav>
            <Button type="button" className="min-h-11 w-full sm:w-auto" disabled={!canCompare || comparisonQuery.isFetching} onClick={submitComparison}>
              <GitCompareArrows aria-hidden="true" /> Compare selected runs
            </Button>
          </div>
          {comparisonSelection && <p className="m-0 text-xs text-muted-foreground">This comparison is read-only. It does not change or rerun either Analysis Run.</p>}
        </CardContent>
      </Card>

      {comparisonSelection && comparisonQuery.isPending && (
        <Card role="status" aria-label="Loading Analysis Run comparison" className="shadow-sm">
          <CardContent className="space-y-3 py-5"><Skeleton className="h-8 w-2/3" /><Skeleton className="h-24 w-full" /><Skeleton className="h-48 w-full" /></CardContent>
        </Card>
      )}
      {compareError && <Alert variant="destructive"><AlertTitle>Comparison unavailable</AlertTitle><AlertDescription>{compareError} Select runs with persisted parsed structure and report data, then try again.</AlertDescription></Alert>}
      {comparison && (
        <ComparisonResults
          left={comparison.leftRun}
          right={comparison.rightRun}
          counts={comparison.counts}
          items={comparison.items}
          disclaimer={comparison.disclaimer}
          sourceContentDiffers={comparison.sourceContentDiffers}
          sourceDocumentDiffers={comparison.sourceDocumentDiffers}
          analysisConfigurationDiffers={comparison.analysisConfigurationDiffers}
          alignmentPolicy={comparison.alignmentPolicy}
        />
      )}
      <div className="flex justify-start">
        <PageTransitionLink href="/" direction="back" className={cn(buttonVariants({ variant: "ghost" }), "min-h-11")}>Back to Analysis Runs</PageTransitionLink>
      </div>
    </section>
  );
}
