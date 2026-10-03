"use client";

import {
  createContext,
  useContext,
  useState,
  type Dispatch,
  type ReactNode,
  type SetStateAction,
} from "react";
import { PageTransitionLink } from "@/features/workspace/components/page-transition";
import { cn } from "@/lib/utils";

type WorkspaceShellState = {
  isPaperReviewActive: boolean;
  setPaperReviewActive: Dispatch<SetStateAction<boolean>>;
};

const WorkspaceShellContext = createContext<WorkspaceShellState | null>(null);

export function WorkspaceShellStateProvider({ children }: { children: ReactNode }) {
  const [isPaperReviewActive, setPaperReviewActive] = useState(false);

  return (
    <WorkspaceShellContext.Provider value={{ isPaperReviewActive, setPaperReviewActive }}>
      {children}
    </WorkspaceShellContext.Provider>
  );
}

export function useWorkspaceShellState() {
  const state = useContext(WorkspaceShellContext);
  if (!state) throw new Error("useWorkspaceShellState must be used within WorkspaceShellStateProvider.");
  return state;
}

export function WorkspaceMain({ children }: { children: ReactNode }) {
  const { isPaperReviewActive } = useWorkspaceShellState();

  return (
    <main className={cn(
      "workspace-content mx-auto flex w-full max-w-6xl flex-1 flex-col px-4 pt-4 sm:px-6 sm:pt-5",
      isPaperReviewActive ? "pb-2" : "pb-6 sm:pb-8",
    )}>
      {children}
    </main>
  );
}

export function WorkspaceFooter() {
  const { isPaperReviewActive } = useWorkspaceShellState();
  if (isPaperReviewActive) return null;

  return (
    <footer className="mt-auto flex min-h-[6.5rem] w-full items-center justify-center border-t border-border bg-card px-4 py-5 text-center sm:px-6">
      <div className="mx-auto flex max-w-6xl flex-row items-center justify-center gap-2 whitespace-nowrap">
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
          className="rounded-sm text-xs text-muted-foreground no-underline hover:text-primary hover:no-underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
        >
          by nooroctavian.id
        </a>
      </div>
    </footer>
  );
}
