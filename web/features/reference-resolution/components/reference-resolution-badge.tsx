import { cn } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";

export function ReferenceResolutionBadge({ status }: { status: string }) {
  const className = status === "RESOLVED"
    ? "border-success-foreground/20 bg-success text-success-foreground"
    : status === "UNSUPPORTED_REFERENCE_TYPE" || status === "RESOLUTION_FAILED"
      ? "border-destructive/25 bg-destructive/10 text-destructive"
      : "border-warning-foreground/20 bg-warning text-warning-foreground";

  return (
    <Badge variant="outline" className={cn("shrink-0 capitalize", className)}>
      {status.replaceAll("_", " ").toLowerCase()}
    </Badge>
  );
}
