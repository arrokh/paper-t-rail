import Link from "next/link";
import { Badge } from "@/components/ui/badge";
import { UploadDashboard } from "@/features/workspace/components/upload-dashboard";

export default function Home() {
  return (
    <main className="mx-auto flex min-h-screen w-full max-w-6xl flex-col px-4 py-5 sm:px-6 sm:py-8">
      <header className="flex items-center justify-between gap-4 border-b border-border/80 pb-4">
        <Link href="/" className="inline-flex items-center gap-3 rounded-md font-semibold tracking-tight text-foreground focus-visible:outline-none focus-visible:ring-3 focus-visible:ring-ring/50" aria-label="Paper T-Rail home">
          <span className="grid size-9 place-items-center rounded-xl rounded-bl-sm bg-primary font-serif text-lg text-primary-foreground" aria-hidden="true">
            P
          </span>
          <span>Paper T-Rail</span>
        </Link>
        <Badge variant="outline" className="h-auto gap-2 rounded-full px-3 py-1.5 font-mono text-[0.65rem] tracking-wide text-muted-foreground uppercase">
          <span className="size-2 rounded-full bg-primary ring-4 ring-primary/10" aria-hidden="true" />
          Local workspace
        </Badge>
      </header>

      <section className="py-5 sm:py-6" aria-labelledby="page-heading">
        <h1 id="page-heading" className="font-serif text-2xl font-semibold tracking-tight sm:text-3xl">
          Trace a research paper.
        </h1>
      </section>

      <UploadDashboard />

      <footer className="mt-auto flex flex-col gap-2 border-t border-border/80 pt-5 text-xs leading-relaxed text-muted-foreground sm:flex-row sm:items-center sm:justify-between">
        <span>Private by default · Provider sharing requires per-run consent</span>
        <span>Paper T-Rail reports are research triage, not certification or grading.</span>
      </footer>
    </main>
  );
}
