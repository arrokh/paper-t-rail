import type { ReactNode } from "react";
import { ChevronDown } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible";
import type { ClaimReferenceVerificationOutcome } from "@/features/analysis-runs/types";
import { EvidencePassageSpanDiagnostics } from "@/features/reference-resolution/components/evidence-passage-span-diagnostics";

type EvidencePassage = ClaimReferenceVerificationOutcome["evidencePassages"][number];
export type EvidencePresentation = "pipeline" | "paper-review";

function excerpt(text: string, maximumLength = 220): string {
  const normalized = text.replace(/\s+/g, " ").trim();
  if (normalized.length <= maximumLength) return normalized;
  return `${normalized.slice(0, maximumLength).trimEnd()}…`;
}

function sourceLocation(passage: EvidencePassage): string {
  const section = `Section ${passage.sectionOrder + 1} · ${passage.sectionHeading ?? "No heading recorded"}`;
  const paragraphs = passage.paragraphStart === passage.paragraphEnd
    ? `paragraph ${passage.paragraphStart}`
    : `paragraphs ${passage.paragraphStart}–${passage.paragraphEnd}`;
  return `${section} · ${paragraphs}${passage.pageNumber ? ` · page ${passage.pageNumber}` : ""}`;
}

type TextRange = { start: number; end: number; text: string };

function exactDiagnosticRanges(text: string, spans: EvidencePassage["diagnosticSpans"]): TextRange[] {
  return spans.flatMap((span) => {
    const { coreStartOffset: start, coreEndOffset: end, coreText } = span;
    if (!Number.isInteger(start) || !Number.isInteger(end) || start < 0 || end <= start || end > text.length) return [];

    const persistedText = text.slice(start, end);
    return persistedText === coreText ? [{ start, end, text: persistedText }] : [];
  }).sort((first, second) => first.start - second.start);
}

function PassageText({ passage }: { passage: EvidencePassage }) {
  const ranges = exactDiagnosticRanges(passage.text, passage.diagnosticSpans);
  if (ranges.length === 0) return <>{passage.text}</>;

  const segments: ReactNode[] = [];
  let cursor = 0;
  for (const range of ranges) {
    if (range.start < cursor) continue;
    segments.push(passage.text.slice(cursor, range.start));
    segments.push(
      <mark key={`${range.start}-${range.end}`} title="Persisted diagnostic source span" className="rounded-sm bg-warning text-warning-foreground">
        {range.text}
      </mark>,
    );
    cursor = range.end;
  }
  segments.push(passage.text.slice(cursor));
  return <>{segments}</>;
}

function judgementLabel(passage: EvidencePassage): string {
  if (passage.evidenceJudgement) return passage.evidenceJudgement.judgement.replaceAll("_", " ").toLowerCase();
  if (passage.diagnosticSpans.length > 0) return "span diagnostics · no parent judgement";
  return "not semantically assessed";
}

function PassageAuditDetails({ passage }: { passage: EvidencePassage }) {
  return (
    <Collapsible className="group/passage-details rounded-md border border-border bg-background">
      <CollapsibleTrigger className="flex min-h-11 w-full items-center justify-between gap-2 rounded-md px-3 py-2 text-left text-xs font-medium focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
        Passage retrieval details
        <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[open]/passage-details:rotate-180 motion-reduce:transition-none" aria-hidden="true" />
      </CollapsibleTrigger>
      <CollapsibleContent className="border-t border-border p-3">
        <dl className="grid gap-x-4 gap-y-3 text-xs sm:grid-cols-2">
          <div>
            <dt className="font-mono text-muted-foreground">Retrieval ranks</dt>
            <dd className="m-0">Vector {passage.vectorRank ?? "not recorded"} · lexical {passage.lexicalRank ?? "not recorded"} · fused {passage.fusedRank}</dd>
          </div>
          <div>
            <dt className="font-mono text-muted-foreground">Reciprocal-rank fusion score</dt>
            <dd className="m-0 font-mono">{passage.fusionScore.toFixed(6)}</dd>
          </div>
          <div>
            <dt className="font-mono text-muted-foreground">Source asset</dt>
            <dd className="m-0 break-all font-mono">{passage.sourceAssetId}</dd>
          </div>
          <div>
            <dt className="font-mono text-muted-foreground">Content SHA-256</dt>
            <dd className="m-0 break-all font-mono">{passage.contentSha256}</dd>
          </div>
          <div>
            <dt className="font-mono text-muted-foreground">Parser</dt>
            <dd className="m-0 break-words">{passage.parserProvider} · {passage.parserVersion}</dd>
          </div>
          <div>
            <dt className="font-mono text-muted-foreground">Language detection</dt>
            <dd className="m-0 break-words">{passage.language} · {passage.languageDetectorVersion}</dd>
          </div>
        </dl>
      </CollapsibleContent>
    </Collapsible>
  );
}

export function EvidencePassageCard({
  passage,
  presentation,
}: {
  passage: EvidencePassage;
  presentation: EvidencePresentation;
}) {
  const judgement = passage.evidenceJudgement;

  return (
    <li className="rounded-lg border border-border/80 bg-muted/10">
      <Collapsible className="group/passage">
        <CollapsibleTrigger className="flex min-h-11 w-full flex-col items-stretch gap-2 rounded-lg px-3 py-3 text-left focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50">
          <span className="flex flex-wrap items-center gap-2">
            <span className="font-medium">Evidence Passage · fused rank {passage.fusedRank}</span>
            {judgement ? (
              <>
                <Badge variant="outline" className="capitalize">{judgement.judgement.replaceAll("_", " ").toLowerCase()}</Badge>
                <Badge variant="secondary" className="capitalize">{judgement.evidenceRole.replaceAll("_", " ").toLowerCase()}</Badge>
              </>
            ) : (
              <Badge variant="outline" className="capitalize">{judgementLabel(passage)}</Badge>
            )}
            <ChevronDown className="ml-auto size-4 shrink-0 text-muted-foreground transition-transform group-data-[open]/passage:rotate-180 motion-reduce:transition-none" aria-hidden="true" />
          </span>
          <span className="block break-words text-sm leading-relaxed text-foreground">{excerpt(passage.text)}</span>
          <span className="block break-words text-xs text-muted-foreground">{sourceLocation(passage)}</span>
        </CollapsibleTrigger>
        <CollapsibleContent className="space-y-3 border-t border-border/70 p-3">
          {passage.diagnosticSpans.length > 0 && (
            <p className="m-0 text-xs text-muted-foreground">
              Marked text identifies persisted diagnostic spans; span judgements do not change the parent result.
            </p>
          )}
          <blockquote className="m-0 whitespace-pre-wrap break-words text-sm leading-relaxed text-foreground">
            <PassageText passage={passage} />
          </blockquote>
          {presentation === "paper-review" && <PassageAuditDetails passage={passage} />}
          <EvidencePassageSpanDiagnostics
            passageId={passage.id}
            spans={passage.diagnosticSpans}
            showSourceOffsets={presentation === "paper-review"}
          />
        </CollapsibleContent>
      </Collapsible>
    </li>
  );
}
