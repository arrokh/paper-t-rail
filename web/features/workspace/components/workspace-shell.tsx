import type { ReactNode } from "react";
import {
  WorkspaceFooter,
  WorkspaceMain,
  WorkspaceShellStateProvider,
} from "@/features/workspace/components/workspace-shell-state";

export function WorkspaceShell({ children }: { children: ReactNode }) {
  return (
    <WorkspaceShellStateProvider>
      <div className="flex min-h-screen flex-col">
        <header className="workspace-site-header w-full bg-background">
          <div className="mx-auto flex w-full max-w-6xl items-center px-4 py-3 sm:px-6">
            <a
              href="https://paper-t-rail.nooroctavian.id/"
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center rounded-md font-heading text-2xl font-[760] tracking-[-0.04em] text-foreground focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
              aria-label="Paper T-Rail home (opens in a new tab)"
            >
              Paper T-Rail
            </a>
          </div>
        </header>

        <WorkspaceMain>{children}</WorkspaceMain>

        <WorkspaceFooter />
      </div>
    </WorkspaceShellStateProvider>
  );
}
