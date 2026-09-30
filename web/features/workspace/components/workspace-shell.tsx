import type { ReactNode } from "react";
import { BackToTopFab } from "@/features/workspace/components/back-to-top-fab";
import { PageTransitionLink } from "@/features/workspace/components/page-transition";

export function WorkspaceShell({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col">
      <header className="w-full border-b border-border bg-card">
        <div className="mx-auto flex w-full max-w-6xl items-center px-4 py-3 sm:px-6">
          <PageTransitionLink
            direction="back"
            href="/"
            className="inline-flex items-center rounded-md font-heading text-2xl font-[760] tracking-[-0.04em] text-foreground focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
            aria-label="Paper T-Rail home"
          >
            Paper T-Rail
          </PageTransitionLink>
        </div>
      </header>

      <main className="workspace-content mx-auto flex w-full max-w-6xl flex-1 flex-col px-4 py-6 sm:px-6 sm:py-8">
        {children}
      </main>

      <BackToTopFab />

      <footer className="mt-auto w-full border-t border-border bg-card px-4 py-5 text-center sm:px-6">
        <div className="mx-auto flex max-w-6xl flex-col items-center gap-1">
          <PageTransitionLink
            direction="back"
            href="/"
            className="rounded-sm font-heading text-lg font-[760] tracking-[-0.04em] text-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
            aria-label="Paper T-Rail home"
          >
            Paper T-Rail
          </PageTransitionLink>
          <a
            href="https://nooroctavian.id/"
            target="_blank"
            rel="noopener noreferrer"
            className="rounded-sm text-xs text-muted-foreground underline-offset-4 hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
          >
            by nooroctavian.id
          </a>
        </div>
      </footer>
    </div>
  );
}
