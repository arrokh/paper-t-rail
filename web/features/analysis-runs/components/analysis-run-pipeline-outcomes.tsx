import { ArrowRight } from "lucide-react";
import type { CSSProperties } from "react";
import { PIPELINE_STAGES, PIPELINE_STAGE_STATE_LABELS, pipelineStageState, type PipelineStageId } from "@/features/analysis-runs/pipeline";
import type { AnalysisRun, ParsedDocument, ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";
import styles from "./analysis-run-pipeline-outcomes.module.css";

type PipelineOutcome = {
  stageId: PipelineStageId;
  metric: string;
  label: string;
  detail?: string;
};

const FUNNEL_STAGE_HEIGHTS = [
  "17rem",
  "15.5rem",
  "14rem",
  "12.5rem",
  "11rem",
] as const;

function formatCount(value: number): string {
  return new Intl.NumberFormat().format(value);
}

function countOrStageState(value: number | undefined, run: AnalysisRun, stageId: PipelineStageId): string {
  return value === undefined ? PIPELINE_STAGE_STATE_LABELS[pipelineStageState(run, stageId)] : formatCount(value);
}

function resultDistribution(summary: ReferenceResolutionReportResponse["evidenceCoverage"]["summary"]): string {
  const outcomes: Array<[number, string]> = [
    [summary.supported, "supported"],
    [summary.partiallySupported, "partially supported"],
    [summary.contradicted, "contradicted"],
    [summary.insufficientEvidence, "insufficient evidence"],
    [summary.inaccessible, "inaccessible"],
    [summary.unresolved, "unresolved"],
    [summary.unsupportedReferenceType, "unsupported reference type"],
  ];

  return outcomes
    .filter(([count]) => count > 0)
    .map(([count, label]) => `${formatCount(count)} ${label}`)
    .join(" · ");
}

function pipelineOutcomes(
  run: AnalysisRun,
  parsedDocument: ParsedDocument | null,
  report: ReferenceResolutionReportResponse | null,
): PipelineOutcome[] {
  const progress = run.progress;
  const claimCount = parsedDocument
    ? parsedDocument.citationContexts.reduce((total, context) => total + context.atomicClaims.length, 0)
    : progress.atomicClaimCount;
  const bibliographyCount = parsedDocument?.bibliographyEntries.length ?? progress.bibliographyEntryCount;
  const citationContextCount = parsedDocument?.citationContexts.length ?? progress.citationContextCount;
  const resolution = report?.referenceResolution.summary;
  const resolved = resolution?.resolved ?? progress.resolvedReferenceCount;
  const resolutionTotal = resolution?.total ?? bibliographyCount;
  const unresolved = resolution?.unresolved ?? progress.unresolvedReferenceCount;
  const unsupported = resolution?.unsupportedReferenceType ?? progress.unsupportedReferenceTypeCount;
  const notAttempted = resolution?.notAttempted ?? progress.notAttemptedReferenceCount;
  const resolutionFailed = resolution?.failed ?? progress.failedReferenceResolutionCount;
  const acquired = progress.acquiredCitedPaperCount;
  const indexed = progress.indexedCitedPaperCount;
  const coverage = report?.evidenceCoverage;
  const verification = coverage?.summary;
  const verificationTotal = verification?.totalVerifications ?? progress.totalVerifications;
  const verificationCompleted = verification?.completedVerifications ?? progress.completedVerifications;
  const verificationIncomplete = verification?.incompleteVerifications ?? progress.incompleteVerifications;
  const isVerificationNotRun = coverage?.executionStatus === "NOT_RUN";
  const isVerificationReady = coverage?.executionStatus === "COMPLETED" || coverage?.executionStatus === "COMPLETED_WITH_WARNINGS";
  const isVerificationFailed = coverage?.executionStatus === "FAILED";

  const resolutionExceptions = [
    unresolved === undefined ? null : `${formatCount(unresolved)} unresolved`,
    unsupported === undefined ? null : `${formatCount(unsupported)} unsupported type`,
    notAttempted === undefined ? null : `${formatCount(notAttempted)} not attempted`,
    resolutionFailed === undefined ? null : `${formatCount(resolutionFailed)} failed`,
  ].filter((value): value is string => value !== null && !value.startsWith("0 "));

  const verificationDetails = isVerificationNotRun
    ? "Final Claim–Paper Verification was not run for this Analysis Run."
    : isVerificationReady
      ? [
          verification ? resultDistribution(verification) || "No final outcomes were recorded." : "No final outcomes were recorded.",
          verificationIncomplete ? `${formatCount(verificationIncomplete)} incomplete` : null,
        ].filter((value): value is string => value !== null).join(" · ")
      : verificationTotal === undefined
        ? isVerificationFailed ? "Verification failed before final outcomes were available." : undefined
        : `${formatCount(verificationCompleted ?? 0)} completed · ${formatCount(verificationIncomplete ?? 0)} incomplete`;

  return [
    {
      stageId: "source",
      metric: countOrStageState(claimCount, run, "source"),
      label: "Atomic Claims extracted",
      detail: [
        bibliographyCount === undefined ? null : `${formatCount(bibliographyCount)} Bibliography Entries`,
        citationContextCount === undefined ? null : `${formatCount(citationContextCount)} Citation Contexts`,
      ].filter((value): value is string => value !== null).join(" · ") || undefined,
    },
    {
      stageId: "references",
      metric: resolved === undefined
        ? countOrStageState(undefined, run, "references")
        : `${formatCount(resolved)}${resolutionTotal === undefined ? "" : ` / ${formatCount(resolutionTotal)}`}`,
      label: "Bibliography Entries resolved",
      detail: resolutionExceptions.join(" · ") || undefined,
    },
    {
      stageId: "access",
      metric: countOrStageState(acquired, run, "access"),
      label: "Cited Papers with full text",
      detail: progress.failedCitedPaperAcquisitionCount
        ? `${formatCount(progress.failedCitedPaperAcquisitionCount)} acquisition failures`
        : undefined,
    },
    {
      stageId: "evidence",
      metric: countOrStageState(indexed, run, "evidence"),
      label: "Cited Papers indexed",
      detail: progress.failedEvidenceIndexingCount
        ? `${formatCount(progress.failedEvidenceIndexingCount)} indexing failures`
        : undefined,
    },
    {
      stageId: "verification",
      metric: isVerificationNotRun
        ? "Not run"
        : isVerificationFailed
          ? "Failed"
        : verificationTotal === undefined
          ? countOrStageState(undefined, run, "verification")
          : formatCount(isVerificationReady ? verificationCompleted ?? verificationTotal : verificationTotal),
      label: isVerificationReady ? "Claim–Reference pairs assessed" : isVerificationFailed ? "Final assessment" : "Claim–Reference pairs",
      detail: verificationDetails,
    },
  ];
}

export function AnalysisRunPipelineOutcomes({
  run,
  parsedDocument,
  report,
}: {
  run: AnalysisRun;
  parsedDocument: ParsedDocument | null;
  report: ReferenceResolutionReportResponse | null;
}) {
  const outcomes = pipelineOutcomes(run, parsedDocument, report);

  return (
    <section className="mt-5 space-y-3 border-t border-border pt-5" aria-labelledby="pipeline-outcomes-heading">
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-2">
        <div>
          <h3 id="pipeline-outcomes-heading" className="m-0 text-sm font-semibold">Pipeline outputs</h3>
          <p className="mt-1 mb-0 text-xs text-muted-foreground">Saved results across the five processing stages</p>
        </div>
        <p id="pipeline-outcomes-note" className="m-0 max-w-xl text-xs leading-relaxed text-muted-foreground">
          Stages run from source reading through final assessment. Counts use stage-specific units, not conversion rates.
        </p>
      </div>

      <ol
        className={styles.funnel}
        aria-label="Left-to-right analysis pipeline funnel, with saved results at each stage"
        aria-describedby="pipeline-outcomes-note"
      >
        {outcomes.map((outcome, index) => {
          const stage = PIPELINE_STAGES.find((candidate) => candidate.id === outcome.stageId);
          if (!stage) return null;

          return (
            <li
              key={outcome.stageId}
              className={styles.stage}
            >
              <div
                className={styles.stagePanel}
                style={{ "--pipeline-stage-min-height": FUNNEL_STAGE_HEIGHTS[index] ?? "11rem" } as CSSProperties}
              >
                <div className={styles.stageContent}>
                  <p className="m-0 flex min-w-0 flex-wrap items-baseline gap-x-2 text-xs leading-tight text-muted-foreground">
                    <span className="font-mono">{stage.number}</span>
                    <span className="font-medium text-foreground">{stage.label}</span>
                  </p>
                  <p className="m-0 flex min-w-0 flex-wrap items-baseline gap-x-2">
                    <span className="break-words text-lg font-semibold leading-tight">{outcome.metric}</span>
                    <span className="break-words text-xs font-medium leading-snug">{outcome.label}</span>
                  </p>
                  {outcome.detail
                    ? <p className="m-0 min-w-0 break-words text-xs leading-snug text-muted-foreground">{outcome.detail}</p>
                    : <span aria-hidden="true" />}
                </div>
              </div>
              {index < outcomes.length - 1 && (
                <span className={styles.stageArrow} aria-hidden="true">
                  <ArrowRight className="size-4" />
                </span>
              )}
            </li>
          );
        })}
      </ol>
    </section>
  );
}
