import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";

export function ReferenceResolutionBadge({ status }: { status: string }) {
  const className = status === "RESOLVED"
    ? "border-primary/20 bg-primary/5 text-primary"
    : status === "UNSUPPORTED_REFERENCE_TYPE" || status === "RESOLUTION_FAILED"
      ? "border-destructive/25 bg-destructive/10 text-destructive"
      : "border-warning/40 bg-warning/10 text-warning-foreground";

  return (
    <Badge variant="outline" className={cn("shrink-0 capitalize", className)}>
      {status.replaceAll("_", " ").toLowerCase()}
    </Badge>
  );
}
