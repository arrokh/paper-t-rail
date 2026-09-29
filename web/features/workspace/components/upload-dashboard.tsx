"use client";

import { useState } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ProviderConfigurationCard } from "@/features/providers/components/provider-configuration-card";
import { useProviderConfiguration, ProviderConfigurationProvider } from "@/features/providers/provider-configuration-context";
import { AnalysisRunsTable } from "@/features/analysis-runs/components/analysis-runs-table";

function UploadDashboardContent() {
  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogSession, setDialogSession] = useState(0);
  const [newestCreatedRunId, setNewestCreatedRunId] = useState<string | null>(null);
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const providerConfiguration = useProviderConfiguration();

  function closeDialog(open: boolean) {
    setDialogOpen(open);
  }

  function handleDialogOpenChangeComplete(open: boolean) {
    if (open) return;

    providerConfiguration.resetApprovals();
    setDialogSession((session) => session + 1);
  }

  function handleRunCreated(analysisRunId: string) {
    setNewestCreatedRunId(analysisRunId);
    setDialogOpen(false);
    const retainedParams = new URLSearchParams(searchParams.toString());
    retainedParams.delete("q");
    retainedParams.delete("status");
    retainedParams.delete("page");
    const suffix = retainedParams.size > 0 ? `?${retainedParams.toString()}` : "";
    router.replace(`${pathname}${suffix}`, { scroll: false });
  }

  return (
    <>
      <AnalysisRunsTable newestCreatedRunId={newestCreatedRunId} onAddRun={() => setDialogOpen(true)} />
      <Dialog
        open={dialogOpen}
        onOpenChange={closeDialog}
        onOpenChangeComplete={handleDialogOpenChangeComplete}
      >
        <DialogContent className="analysis-upload-dialog min-w-0 max-h-[92dvh] max-w-[calc(100%-2rem)] grid-cols-[minmax(0,1fr)] grid-rows-[auto_minmax(0,1fr)] overflow-hidden p-5 sm:max-w-[calc(100%-2rem)] lg:max-w-3xl sm:p-7" showCloseButton>
          <DialogHeader className="min-w-0 pr-10">
            <DialogTitle className="font-serif text-2xl font-semibold tracking-tight sm:text-3xl">Start with your PDF</DialogTitle>
            <DialogDescription className="max-w-2xl leading-relaxed">
              Upload an English, text-based academic PDF to create an immutable Analysis Run. You can follow each saved result from the run details page.
            </DialogDescription>
          </DialogHeader>
          <div className="flex h-full min-h-0 min-w-0">
            <ProviderConfigurationCard
              key={dialogSession}
              onRunCreated={handleRunCreated}
            />
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}

export function UploadDashboard() {
  return (
    <ProviderConfigurationProvider>
      <UploadDashboardContent />
    </ProviderConfigurationProvider>
  );
}
