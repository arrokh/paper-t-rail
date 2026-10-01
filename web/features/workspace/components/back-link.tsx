import { ArrowLeft } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { PageTransitionLink } from "@/features/workspace/components/page-transition";
import { cn } from "@/lib/utils";

export function BackLink({
  href,
  label,
  className,
}: {
  href: string;
  label: string;
  className?: string;
}) {
  return (
    <PageTransitionLink
      direction="back"
      href={href}
      aria-label={label}
      title={label}
      className={cn(buttonVariants({ variant: "ghost", size: "icon" }), "text-muted-foreground hover:text-foreground", className)}
    >
      <ArrowLeft aria-hidden="true" />
    </PageTransitionLink>
  );
}
