"use client";

import { useState } from "react";
import { AnalysisRunsWorkspace } from "@/features/analysis-runs/components/analysis-runs-workspace";
import { ProviderConfigurationCard } from "@/features/providers/components/provider-configuration-card";
import { ProviderConfigurationProvider } from "@/features/providers/provider-configuration-context";

export function UploadDashboard() {
  const [newestCreatedRunId, setNewestCreatedRunId] = useState<string | null>(null);

  return (
    <ProviderConfigurationProvider>
      <section className="space-y-6" aria-label="Source Document workspace">
        <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
          <ProviderConfigurationCard onRunCreated={setNewestCreatedRunId} />
          <AnalysisRunsWorkspace
            key={newestCreatedRunId ?? "recent-runs"}
            initialSelectedRunId={newestCreatedRunId}
          />
        </div>
      </section>
    </ProviderConfigurationProvider>
  );
}
