"use client";

import { Fragment } from "react";
import {
  Breadcrumb,
  BreadcrumbItem,
  BreadcrumbList,
  BreadcrumbPage,
  BreadcrumbSeparator,
} from "@/components/ui/breadcrumb";
import { BackLink } from "@/features/workspace/components/back-link";
import { PageTransitionLink } from "@/features/workspace/components/page-transition";
import { cn } from "@/lib/utils";

type WorkspaceBreadcrumbItem = {
  label: string;
  href?: string;
  backButton?: boolean;
};

const BREADCRUMB_LABEL_LIMIT = 75;

function truncateBreadcrumbLabel(label: string): string {
  const characters = Array.from(label);
  if (characters.length <= BREADCRUMB_LABEL_LIMIT) return label;
  return `${characters.slice(0, BREADCRUMB_LABEL_LIMIT - 1).join("")}…`;
}

export function WorkspaceBreadcrumb({ items }: { items: readonly WorkspaceBreadcrumbItem[] }) {
  return (
    <Breadcrumb aria-label="Breadcrumb" className="flex h-11 min-h-11 items-center overflow-hidden">
      <BreadcrumbList className="min-w-0 flex-nowrap overflow-hidden">
        {items.map((item, index) => (
          <Fragment key={`${index}-${item.label}`}>
            {index > 0 && <BreadcrumbSeparator />}
            <BreadcrumbItem className={cn("min-w-0", index === items.length - 1 && "flex-1")}>
              {item.href ? (
                item.backButton ? (
                  <BackLink
                    href={item.href}
                    label={item.label}
                    className="size-11"
                  />
                ) : (
                  <PageTransitionLink
                    direction="back"
                    href={item.href}
                    className="rounded-sm transition-colors hover:text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                  >
                    {truncateBreadcrumbLabel(item.label)}
                  </PageTransitionLink>
                )
              ) : (
                <BreadcrumbPage aria-label={item.label} title={item.label} className="block max-w-full truncate">
                  {truncateBreadcrumbLabel(item.label)}
                </BreadcrumbPage>
              )}
            </BreadcrumbItem>
          </Fragment>
        ))}
      </BreadcrumbList>
    </Breadcrumb>
  );
}
