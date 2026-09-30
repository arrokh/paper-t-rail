import { Check, RotateCcw } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export type PipelineResultFilterOption = {
  id: string;
  label: string;
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
        <p className="text-xs text-muted-foreground">Select cards to filter. No selection or all cards selected shows everything.</p>
        <Button type="button" variant="ghost" size="sm" disabled={selectedValues.size === 0} onClick={onReset}>
          <RotateCcw aria-hidden="true" />
          Reset filters
        </Button>
      </div>
      <div role="group" aria-label={label} className={cn("grid grid-cols-2 gap-3 sm:grid-cols-4", className)}>
        {options.map((option) => {
          const selected = selectedValues.has(option.id);
          return (
            <Button
              key={option.id}
              type="button"
              variant="outline"
              aria-pressed={selected}
              onClick={() => onToggle(option.id)}
              className={cn(
                "h-auto min-h-16 w-full min-w-0 justify-start rounded-xl border p-0 text-left whitespace-normal shadow-none",
                selected ? "border-primary/50 bg-primary/5 hover:bg-primary/10" : "border-border bg-card hover:bg-muted/60",
              )}
            >
              <span className="flex w-full min-w-0 flex-col gap-1 p-3">
                <span className="flex w-full min-w-0 items-start justify-between gap-2">
                  <span className="min-w-0 text-xs leading-relaxed text-muted-foreground">{option.label}</span>
                  <span className="flex size-4 shrink-0 items-center justify-center text-primary" aria-hidden="true">
                    {selected && <Check className="size-3.5" />}
                  </span>
                </span>
                <span className="font-mono text-lg font-semibold text-foreground">{option.value}</span>
              </span>
            </Button>
          );
        })}
      </div>
    </div>
  );
}
