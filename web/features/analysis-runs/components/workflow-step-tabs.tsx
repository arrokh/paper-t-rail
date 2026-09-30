"use client";

import { Fragment, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { ArrowRight, ArrowUp, Check, CircleAlert, Clock3, LoaderCircle } from "lucide-react";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";

export type WorkflowStepState = "in-progress" | "complete" | "ready" | "loading" | "waiting" | "failed";

export type WorkflowStep = {
  value: string;
  number: string;
  label: string;
  compactLabel: string;
  statusLabel: string;
  state: WorkflowStepState;
  disabled?: boolean;
};

const STEP_STATE_CLASS_NAMES: Record<WorkflowStepState, string> = {
  "in-progress": "text-info-foreground",
  complete: "text-success-foreground",
  ready: "text-success-foreground",
  loading: "text-muted-foreground",
  waiting: "text-muted-foreground",
  failed: "text-destructive",
};

export function WorkflowStepTabs({
  value,
  onValueChange,
  steps,
  onScrollToTop,
  scrollToTopLabel = "Back to the top of this section",
  children,
}: {
  value: string;
  onValueChange: (value: string) => void;
  steps: WorkflowStep[];
  onScrollToTop: () => void;
  scrollToTopLabel?: string;
  children: ReactNode;
}) {
  const navigationScopeRef = useRef<HTMLDivElement>(null);
  const tabListWrapperRef = useRef<HTMLDivElement>(null);
  const [isSticky, setIsSticky] = useState(false);

  useEffect(() => {
    let frame = 0;

    const updateStickyState = () => {
      const scope = navigationScopeRef.current;
      const tabList = tabListWrapperRef.current;
      if (!scope || !tabList) {
        setIsSticky(false);
        return;
      }

      const tabListBounds = tabList.getBoundingClientRect();
      const scopeBounds = scope.getBoundingClientRect();
      const sticky = tabListBounds.top <= 1
        && scopeBounds.top < 0
        && scopeBounds.bottom > tabListBounds.height + 1;
      setIsSticky((current) => current === sticky ? current : sticky);
    };

    const scheduleStickyStateUpdate = () => {
      if (frame !== 0) return;
      frame = window.requestAnimationFrame(() => {
        frame = 0;
        updateStickyState();
      });
    };

    const resizeObserver = new ResizeObserver(scheduleStickyStateUpdate);
    if (navigationScopeRef.current) resizeObserver.observe(navigationScopeRef.current);
    if (tabListWrapperRef.current) resizeObserver.observe(tabListWrapperRef.current);

    window.addEventListener("scroll", scheduleStickyStateUpdate, { passive: true });
    window.addEventListener("resize", scheduleStickyStateUpdate);
    updateStickyState();

    return () => {
      window.removeEventListener("scroll", scheduleStickyStateUpdate);
      window.removeEventListener("resize", scheduleStickyStateUpdate);
      resizeObserver.disconnect();
      if (frame !== 0) window.cancelAnimationFrame(frame);
    };
  }, []);

  return (
    <div ref={navigationScopeRef} className="relative">
      <Tabs
        value={value}
        onValueChange={(nextValue) => {
          if (typeof nextValue === "string") onValueChange(nextValue);
        }}
        className="gap-4"
      >
        <div
          ref={tabListWrapperRef}
          data-sticky-step-navigation
          data-stuck={isSticky ? "true" : "false"}
          className={cn(
            "sticky top-0 z-30 -mx-4 rounded-b-lg border-b border-border/70 bg-card px-4 py-2 transition-shadow",
            isSticky && "shadow-md",
          )}
        >
          <TabsList
            variant="line"
            aria-label="Analysis Run steps"
            className="h-auto w-full min-w-0 justify-start gap-1 rounded-none bg-transparent p-0 sm:gap-3"
          >
            {steps.map((step, index) => (
              <Fragment key={step.value}>
                <TabsTrigger
                  value={step.value}
                  aria-label={`${step.number} ${step.label}: ${step.statusLabel}`}
                  disabled={step.disabled}
                  data-step-state={step.state}
                  className="min-h-14 min-w-0 flex-1 items-start justify-start gap-2 rounded-none px-1.5 py-2 text-left text-muted-foreground transition-colors data-active:text-primary disabled:cursor-not-allowed disabled:opacity-70 aria-disabled:opacity-70 sm:px-2"
                >
                  <span className="pt-0.5 font-mono text-[0.65rem] text-warning-foreground sm:text-xs">{step.number}</span>
                  <span className="min-w-0 space-y-1">
                    <span className="block truncate text-[0.65rem] uppercase tracking-normal sm:text-xs sm:tracking-[0.06em]">
                      <span className="sm:hidden">{step.compactLabel}</span>
                      <span className="hidden sm:inline">{step.label}</span>
                    </span>
                    <span className={cn("flex items-center gap-1 text-[0.625rem] font-medium leading-none", STEP_STATE_CLASS_NAMES[step.state])}>
                      {step.state === "complete" || step.state === "ready" ? (
                        <Check className="size-3" aria-hidden="true" />
                      ) : step.state === "failed" ? (
                        <CircleAlert className="size-3" aria-hidden="true" />
                      ) : step.state === "in-progress" || step.state === "loading" ? (
                        <LoaderCircle className="size-3 motion-safe:animate-spin" aria-hidden="true" />
                      ) : (
                        <Clock3 className="size-3" aria-hidden="true" />
                      )}
                      <span>{step.statusLabel}</span>
                    </span>
                  </span>
                </TabsTrigger>
                {index < steps.length - 1 && (
                  <ArrowRight className="hidden size-4 shrink-0 self-center text-muted-foreground sm:block" aria-hidden="true" />
                )}
              </Fragment>
            ))}
          </TabsList>
        </div>
        {children}
      </Tabs>
      {isSticky && (
        <Button
          type="button"
          variant="secondary"
          size="sm"
          aria-label={scrollToTopLabel}
          className="fixed right-4 bottom-4 z-50 min-h-11 rounded-full border border-border bg-card/95 px-4 shadow-lg backdrop-blur sm:right-6 sm:bottom-6"
          onClick={onScrollToTop}
        >
          <ArrowUp aria-hidden="true" />
          Back to top
        </Button>
      )}
    </div>
  );
}
