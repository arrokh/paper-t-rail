"use client";

import { CircleHelp, RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverDescription,
  PopoverTitle,
  PopoverTrigger,
} from "@/components/ui/popover";
import { cn } from "@/lib/utils";

export type PipelineResultFilterOption = {
  id: string;
  label: string;
  description: string;
  value: number;
};

export function PipelineResultMetricFilters({
  label,
  options,
  selectedValues,
  onToggle,
  onReset,
  className,
}: {
  label: string;
  options: readonly PipelineResultFilterOption[];
  selectedValues: ReadonlySet<string>;
  onToggle: (id: string) => void;
  onReset: () => void;
  className?: string;
}) {
  return (
    <div className="space-y-2">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs text-muted-foreground">Select cards to filter. Use a card’s help button to learn what it means. No selection or all cards selected shows everything.</p>
        <Button type="button" variant="ghost" size="sm" className="ml-auto shrink-0" disabled={selectedValues.size === 0} onClick={onReset}>
          <RotateCcw aria-hidden="true" />
          Reset filters
        </Button>
      </div>
      <div role="group" aria-label={label} className={cn("grid grid-cols-2 gap-3 sm:grid-cols-4", className)}>
        {options.map((option) => {
          const selected = selectedValues.has(option.id);
          return (
            <div key={option.id} className="relative min-w-0">
              <Button
                type="button"
                variant="outline"
                aria-pressed={selected}
                onClick={() => onToggle(option.id)}
                className={cn(
                  "h-[4.5rem] w-full min-w-0 justify-start rounded-xl border p-0 text-left whitespace-normal shadow-none",
                  selected ? "border-primary/70 bg-primary/10 hover:bg-primary/15" : "border-border bg-card hover:bg-muted/60",
                )}
              >
                <span className="flex h-full w-full min-w-0 flex-col justify-between p-2 pr-10">
                  <span className="min-h-8 min-w-0 line-clamp-2 text-xs leading-4 text-muted-foreground">
                    {option.label}
                  </span>
                  <span className="font-mono text-base font-semibold leading-5 text-foreground">{option.value}</span>
                </span>
              </Button>
              <Popover modal={false}>
                <PopoverTrigger
                  openOnHover
                  delay={100}
                  closeDelay={100}
                  render={
                    <Button
                      type="button"
                      variant="ghost"
                      size="icon-sm"
                      aria-label={`Explain ${option.label}`}
                      className="absolute top-1 right-1 z-10 rounded-full text-muted-foreground hover:text-foreground"
                    />
                  }
                >
                  <CircleHelp aria-hidden="true" />
                </PopoverTrigger>
                <PopoverContent align="end" className="w-64 max-w-[calc(100vw-2rem)]">
                  <PopoverTitle>{option.label}</PopoverTitle>
                  <PopoverDescription>{option.description}</PopoverDescription>
                </PopoverContent>
              </Popover>
            </div>
          );
        })}
      </div>
    </div>
  );
}
