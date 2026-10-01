"use client";

import { useState } from "react";
import { QueryClient, QueryClientProvider, useIsFetching, useIsMutating } from "@tanstack/react-query";

function ApiRequestIndicator() {
  const isBusy = useIsFetching() + useIsMutating() > 0;

  return (
    <>
      <div
        className={`pointer-events-none fixed inset-x-0 top-0 z-[100] h-1 overflow-hidden bg-primary/10 transition-opacity duration-150 ${isBusy ? "opacity-100" : "opacity-0"}`}
        aria-hidden="true"
      >
        <span className={`api-fetch-progress block h-full w-1/3 rounded-r-full bg-primary ${isBusy ? "" : "invisible"}`} />
      </div>
      <span className="sr-only" role="status" aria-live="polite" aria-atomic="true">
        {isBusy ? "Loading data from Paper T-Rail…" : ""}
      </span>
    </>
  );
}

export function QueryProvider({ children }: Readonly<{ children: React.ReactNode }>) {
  const [queryClient] = useState(() => new QueryClient());
  return (
    <QueryClientProvider client={queryClient}>
      <ApiRequestIndicator />
      {children}
    </QueryClientProvider>
  );
}
