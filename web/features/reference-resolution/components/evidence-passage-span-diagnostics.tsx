import { ChevronDown } from "lucide-react";
import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";
import { Badge } from "@/components/ui/badge";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import type { EvidencePassageSpanDiagnostic } from "@/features/analysis-runs/types";

type SpanWorkStatus = "PENDING" | "COMPLETED" | "INCOMPLETE";

function workStatus(spans: EvidencePassageSpanDiagnostic[]): SpanWorkStatus {
  if (spans.some((span) => span.status === "FAILED" || span.status === "INCOMPLETE")) return "INCOMPLETE";
  if (spans.some((span) => span.status === "PENDING")) return "PENDING";
  return "COMPLETED";
}

function spanStatusLabel(status: EvidencePassageSpanDiagnostic["status"]): string {
  if (status === "COMPLETED") return "judgement available";
  if (status === "PENDING") return "evaluation pending";
  return "incomplete span";
}

export function EvidencePassageSpanDiagnostics({
  passageId,
  spans,
  showSourceOffsets = false,
}: {
  passageId: string;
  spans: EvidencePassageSpanDiagnostic[];
  showSourceOffsets?: boolean;
}) {
  if (spans.length === 0) return null;

  const status = workStatus(spans);
  const isIncomplete = status === "INCOMPLETE";
  const label = `${spans.length} diagnostic sentence span${spans.length === 1 ? "" : "s"}`;

  return (
    <section className="space-y-2 border-t border-border pt-3" aria-label={`Diagnostic sentence spans for Evidence Passage ${passageId}`}>
      <Alert variant={isIncomplete ? "destructive" : "default"}>
        <AlertTitle className="flex flex-wrap items-center gap-2">
          {label}
          <Badge variant="outline" className="capitalize">{status.toLowerCase()}</Badge>
        </AlertTitle>
        <AlertDescription>
          {isIncomplete
            ? "One or more required spans are missing or incomplete. Available judgements remain diagnostics under this original Evidence Passage; no parent judgement or final Claim–Paper status is produced."
            : "These are separate span diagnostics; they are not combined into a parent Evidence Judgement or final Claim–Paper status."}
        </AlertDescription>
      </Alert>
      <Collapsible className="group/span-details rounded-md border border-border">
        <CollapsibleTrigger className="flex min-h-11 w-full items-center justify-between gap-2 rounded-md px-3 py-2 text-left text-xs font-medium focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
          View diagnostic span details
          <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[open]/span-details:rotate-180 motion-reduce:transition-none" aria-hidden="true" />
        </CollapsibleTrigger>
        <CollapsibleContent className="border-t border-border">
          <ol className="divide-y divide-border">
            {spans.map((span) => (
              <li key={span.id} className="space-y-2 p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-mono text-xs text-muted-foreground">Span {span.spanIndex + 1}</span>
                  <Badge variant={span.status === "FAILED" || span.status === "INCOMPLETE" ? "destructive" : "outline"}>
                    {spanStatusLabel(span.status)}
                  </Badge>
                  {span.evidenceJudgement && (
                    <Badge variant="secondary" className="capitalize">{span.evidenceJudgement.judgement.replaceAll("_", " ").toLowerCase()}</Badge>
                  )}
                </div>
                <p className="m-0 break-words text-sm leading-relaxed"><strong>Span text:</strong> {span.coreText}</p>
                {span.contextText !== span.coreText && (
                  <p className="m-0 break-words text-xs leading-relaxed text-muted-foreground"><strong>Judged context:</strong> {span.contextText}</p>
                )}
                {span.failureReason && (
                  <p className="m-0 text-xs text-destructive">Incomplete reason: {span.failureReason.replaceAll("_", " ").toLowerCase()}</p>
                )}
                {showSourceOffsets && (
                  <p className="m-0 font-mono text-xs text-muted-foreground">
                    Source offsets (0-based, end-exclusive): {span.coreStartOffset}–{span.coreEndOffset} · context {span.contextStartOffset}–{span.contextEndOffset}
                  </p>
                )}
              </li>
            ))}
          </ol>
        </CollapsibleContent>
      </Collapsible>
    </section>
  );
}
