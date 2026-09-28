import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import type { EvidencePassageSpanDiagnostic } from "@/features/analysis-runs/types";

type SpanWorkStatus = "PENDING" | "COMPLETED" | "INCOMPLETE";

function workStatus(spans: EvidencePassageSpanDiagnostic[]): SpanWorkStatus {
  if (spans.some((span) => span.status === "FAILED" || span.status === "INCOMPLETE")) return "INCOMPLETE";
  if (spans.some((span) => span.status === "PENDING")) return "PENDING";
  return "COMPLETED";
}

function spanStatusLabel(status: EvidencePassageSpanDiagnostic["status"]): string {
  if (status === "COMPLETED") return "diagnostic judgement available";
  if (status === "PENDING") return "evaluation pending";
  return "incomplete span";
}

export function EvidencePassageSpanDiagnostics({
  passageId,
  spans,
}: {
  passageId: string;
  spans: EvidencePassageSpanDiagnostic[];
}) {
  if (spans.length === 0) return null;

  const status = workStatus(spans);
  const isIncomplete = status === "INCOMPLETE";

  return (
    <section className="space-y-3 border-t border-border pt-3" aria-label={`Diagnostic sentence spans for Evidence Passage ${passageId}`}>
      <Alert variant={isIncomplete ? "destructive" : "default"}>
        <AlertTitle className="flex flex-wrap items-center gap-2">
          Diagnostic sentence spans
          <Badge variant="outline" className="capitalize">{status.toLowerCase()}</Badge>
        </AlertTitle>
        <AlertDescription>
          {isIncomplete
            ? "One or more required spans are missing or incomplete. Available judgements remain diagnostics under this original Evidence Passage; no parent judgement or final Claim–Paper status is produced."
            : "Each judgement is a diagnostic under this original Evidence Passage. Spans are never combined into a parent Evidence Judgement or final Claim–Paper status."}
        </AlertDescription>
      </Alert>
      <ol className="divide-y divide-border rounded-md border border-border" aria-label="Sentence span judgements">
        {spans.map((span) => (
          <li key={span.id} className="space-y-2 p-3">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-mono text-xs text-muted-foreground">Span {span.spanIndex + 1}</span>
              <Badge variant={span.status === "FAILED" || span.status === "INCOMPLETE" ? "destructive" : "outline"}>
                {spanStatusLabel(span.status)}
              </Badge>
              {span.evidenceJudgement && (
                <Badge variant="secondary">{span.evidenceJudgement.judgement.replaceAll("_", " ").toLowerCase()}</Badge>
              )}
            </div>
            <p className="m-0 break-words text-sm leading-relaxed"><strong>Core source offsets (0-based, end-exclusive):</strong> {span.coreStartOffset}–{span.coreEndOffset} · {span.coreText}</p>
            <p className="m-0 break-words text-xs leading-relaxed text-muted-foreground">
              <strong>Judged source window (0-based, end-exclusive):</strong> {span.contextStartOffset}–{span.contextEndOffset} · {span.contextText}
            </p>
            {span.failureReason && (
              <p className="m-0 text-xs text-destructive">Incomplete reason: {span.failureReason.replaceAll("_", " ").toLowerCase()}</p>
            )}
            {span.evidenceJudgement && (
              <p className="m-0 text-xs text-muted-foreground">
                Role {span.evidenceJudgement.evidenceRole.replaceAll("_", " ").toLowerCase()} · provisional rubric strength {span.evidenceJudgement.calibratedStrength.toFixed(3)}
              </p>
            )}
            <dl className="grid gap-x-4 gap-y-1 text-xs sm:grid-cols-2">
              <div>
                <dt className="font-mono text-muted-foreground">Provider · model · version</dt>
                <dd className="m-0 break-words">{span.providerId} · {span.modelId} · {span.providerVersion}</dd>
              </div>
              <div>
                <dt className="font-mono text-muted-foreground">Rubric · splitting policy</dt>
                <dd className="m-0 break-words">{span.judgementRubricVersion} · {span.splittingPolicyVersion}</dd>
              </div>
              <div className="sm:col-span-2">
                <dt className="font-mono text-muted-foreground">Six complete question sequences (token counts · 1,024 limit)</dt>
                <dd className="m-0 break-words font-mono">{span.tokenCounts.join(" · ")}</dd>
              </div>
            </dl>
          </li>
        ))}
      </ol>
    </section>
  );
}
