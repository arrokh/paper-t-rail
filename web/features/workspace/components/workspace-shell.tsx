import type { ReactNode } from "react";
import { BackToTopFab } from "@/features/workspace/components/back-to-top-fab";
import { PageTransitionLink } from "@/features/workspace/components/page-transition";

export function WorkspaceShell({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col">
      <header className="mx-auto flex w-full max-w-6xl items-center justify-between gap-4 border-b border-border/80 px-4 py-4 sm:px-6">
        <PageTransitionLink direction="back" href="/" className="inline-flex items-center gap-3 rounded-md font-semibold tracking-tight text-foreground focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50" aria-label="Paper T-Rail home">
          <span className="grid size-9 place-items-center rounded-xl rounded-bl-sm bg-primary font-serif text-lg text-primary-foreground" aria-hidden="true">P</span>
          <span>Paper T-Rail</span>
        </PageTransitionLink>
      </header>

      <main className="workspace-content mx-auto flex w-full max-w-6xl flex-1 flex-col px-4 py-6 sm:px-6 sm:py-8">
        {children}
      </main>

      <BackToTopFab />

      <footer className="mx-auto mt-auto flex w-full max-w-6xl items-center justify-center border-t border-border/80 px-4 py-5 text-center text-xs leading-relaxed text-muted-foreground sm:px-6">
        <span>
          Paper T-Rail by{" "}
          <a
            href="https://nooroctavian.id/"
            target="_blank"
            rel="noopener noreferrer"
            className="rounded-sm underline-offset-4 hover:text-foreground hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
          >
            nooroctavian.id
          </a>
        </span>
      </footer>
    </div>
  );
}
