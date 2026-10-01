"use client";

import type { ReactNode } from "react";
import { ArrowRight, Check, CircleAlert, CircleHelp, Clock3, LoaderCircle, TriangleAlert } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import { PIPELINE_STAGES, PIPELINE_STAGE_STATE_LABELS, pipelineStageState, type PipelineStageId } from "@/features/analysis-runs/pipeline";
import type { AnalysisRun } from "@/features/analysis-runs/types";

export const PIPELINE_STAGE_STATE_CLASSES = {
  waiting: "border-border bg-card text-foreground",
  active: "border-info-foreground/35 bg-info text-info-foreground shadow-sm",
  pending: "border-border bg-muted/60 text-muted-foreground",
  complete: "border-success-foreground/35 bg-success text-success-foreground",
  ready: "border-info-foreground/25 bg-info/55 text-info-foreground",
  unavailable: "border-border bg-muted text-muted-foreground",
  failed: "border-destructive/35 bg-destructive/5 text-destructive",
  warning: "border-warning/55 bg-warning/30 text-warning-foreground",
} as const;

export const PIPELINE_STAGE_SELECTED_CLASSES = "border-2 border-info-foreground bg-info/70 text-info-foreground shadow-lg ring-2 ring-info-foreground ring-offset-2 ring-offset-background hover:border-info-foreground hover:bg-info/70 hover:text-info-foreground";

function StepStateIcon({ state }: { state: keyof typeof PIPELINE_STAGE_STATE_CLASSES }) {
  if (state === "complete") return <Check className="size-3.5" aria-hidden="true" />;
  if (state === "active") return <LoaderCircle className="size-3.5 motion-safe:animate-spin" aria-hidden="true" />;
  if (state === "failed") return <CircleAlert className="size-3.5" aria-hidden="true" />;
  if (state === "warning") return <TriangleAlert className="size-3.5" aria-hidden="true" />;
  return <Clock3 className="size-3.5" aria-hidden="true" />;
}

function ConditionalPathHelp() {
  return (
    <TooltipProvider delay={100}>
      <Tooltip disableHoverablePopup>
        <TooltipTrigger
          render={
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label="Conditional pipeline paths"
              className="size-8 rounded-full text-muted-foreground transition hover:bg-muted hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50"
            >
              <CircleHelp className="size-4" aria-hidden="true" />
            </Button>
          }
        />
        <TooltipContent
          side="bottom"
          align="end"
          className="w-[min(22rem,calc(100vw-2rem))] max-w-sm flex-col items-start gap-2 whitespace-normal p-3 text-left leading-relaxed"
        >
          <span>
            <span className="mr-1 font-mono text-warning-foreground">IF</span>
            A reference is unresolved or unsupported, access and verification skip it and the report records why.
          </span>
          <span>
            <span className="mr-1 font-mono text-warning-foreground">IF</span>
            Full text is unavailable or unsupported in language, evidence work stops there.
          </span>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  );
}

export function AnalysisPipelineChart({
  run,
  selectedStage,
  onSelectStage,
  afterIntro,
}: {
  run: AnalysisRun | null;
  selectedStage: PipelineStageId | null;
  onSelectStage: (stage: PipelineStageId) => void;
  afterIntro?: ReactNode;
}) {
  return (
    <section className="space-y-4" aria-labelledby="pipeline-heading">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="space-y-1">
          <h2 id="pipeline-heading" className="font-heading text-lg font-semibold tracking-tight">Analysis pipeline</h2>
          <p className="max-w-3xl text-sm leading-relaxed text-muted-foreground">
            Select a stage to inspect its saved result. Motion marks only a stage reported as active by the worker.
          </p>
        </div>
        <div className="flex items-center gap-2">
          {run?.status === "PARSED" && (
            <span className="rounded-full border border-primary/20 bg-primary/5 px-3 py-1 font-mono text-[0.65rem] text-primary uppercase">Parsed · verification not complete</span>
          )}
          <ConditionalPathHelp />
        </div>
      </div>

      {afterIntro}

      <nav id="analysis-pipeline-stages" className="scroll-mt-4 overflow-x-auto overflow-y-hidden p-2 [scrollbar-width:none] [&::-webkit-scrollbar]:hidden" aria-label="Analysis pipeline stages">
        <ol className="flex min-w-[920px] items-stretch gap-2 lg:min-w-0">
          {PIPELINE_STAGES.map((stage, index) => {
            const state = pipelineStageState(run, stage.id);
            const selected = selectedStage === stage.id;
            return (
              <li key={stage.id} className="flex min-w-0 flex-1 items-stretch gap-2">
                <Button
                  type="button"
                  variant="ghost"
                  aria-current={selected ? "step" : undefined}
                  aria-label={`${stage.number} ${stage.label}: ${PIPELINE_STAGE_STATE_LABELS[state]}${selected ? ", selected stage" : ""}`}
                  className={cn(
                    "group flex h-full min-h-[11rem] min-w-0 flex-1 flex-col items-start justify-start gap-2 rounded-xl border px-3 py-3 text-left whitespace-normal transition-all hover:-translate-y-0.5 hover:border-info-foreground hover:shadow-md motion-reduce:transition-none motion-reduce:hover:translate-y-0",
                    PIPELINE_STAGE_STATE_CLASSES[state],
                    selected && PIPELINE_STAGE_SELECTED_CLASSES,
                  )}
                  onClick={() => onSelectStage(stage.id)}
                >
                  <span className="flex min-h-5 w-full items-center">
                    <span className="font-mono text-xs">{stage.number}</span>
                  </span>
                  <span className="block min-h-10 break-words text-sm font-semibold leading-tight">{stage.label}</span>
                  <span className="block min-w-0 flex-1 text-xs font-normal leading-relaxed text-muted-foreground">{stage.description}</span>
                  <span className="mt-auto flex min-h-8 w-full min-w-0 items-end gap-1.5 text-[0.65rem] font-medium leading-tight">
                    <span className="mt-0.5 shrink-0">
                      <StepStateIcon state={state} />
                    </span>
                    <span className="min-w-0 flex-1 break-words">{PIPELINE_STAGE_STATE_LABELS[state]}</span>
                  </span>
                </Button>
                {index < PIPELINE_STAGES.length - 1 && (
                  <ArrowRight className="size-4 shrink-0 self-center text-muted-foreground" aria-hidden="true" />
                )}
              </li>
            );
          })}
        </ol>
      </nav>

    </section>
  );
}
