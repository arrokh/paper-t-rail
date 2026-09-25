import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { QueryProvider } from "@/app/query-provider";
import { UploadDashboard } from "@/features/workspace/components/upload-dashboard";
import { useRecentAnalysisRuns } from "@/features/analysis-runs/queries/analysis-run-queries";

const providerDirectory = {
  providers: {
    claimExtractor: [{ role: "claimExtractor", providerId: "heuristic", displayName: "Heuristic", version: "v1", model: null, trustBoundary: "LOCAL", dataCategories: [], retentionDisclosure: null }],
    embedding: [{ role: "embedding", providerId: "local", displayName: "Local", version: "v1", model: null, trustBoundary: "LOCAL", dataCategories: [], retentionDisclosure: null }],
    systemOne: [{ role: "systemOne", providerId: "mock", displayName: "Mock", version: "v1", model: null, trustBoundary: "LOCAL", dataCategories: [], retentionDisclosure: null }],
    scholarlyMetadata: [{ role: "scholarlyMetadata", providerId: "recorded-fixtures", displayName: "Recorded fixtures", version: "v1", model: null, trustBoundary: "LOCAL", dataCategories: [], retentionDisclosure: null }],
  },
  dataCategories: [],
};

function analysisRun(id: string, message: string, status: "QUEUED" | "PROCESSING" | "COMPLETED" | "FAILED" = "QUEUED") {
  return {
    id,
    documentId: "document-1",
    filename: "source.pdf",
    sourceContentSha256: "a".repeat(64),
    status,
    progress: { message },
    configuration: {
      claimExtractor: { provider: "heuristic", version: "v1" },
      embedding: { provider: "local", version: "v1" },
      systemOne: { provider: "mock", version: "v1" },
      sourceParser: { provider: "grobid", version: "v1" },
      languageDetector: { provider: "local", version: "v1" },
      externalProviderConsents: [],
    },
    createdAt: "2025-01-01T00:00:00Z",
    startedAt: null,
    failureReason: null,
  };
}

function jsonResponse(body: unknown, status = 200) {
  return { ok: status >= 200 && status < 300, status, json: async () => body };
}

function renderWorkspace() {
  return render(
    <QueryProvider>
      <UploadDashboard />
    </QueryProvider>,
  );
}

function RecentRunPollingProbe() {
  const runsQuery = useRecentAnalysisRuns(null);
  return <p>{runsQuery.data?.items.map((run) => run.status).join(",") ?? "Loading"}</p>;
}

afterEach(() => vi.unstubAllGlobals());

describe("interactive workspace remote state", () => {
  it("shows provider loading and the safe API error when provider loading fails", async () => {
    let finishProviderRequest!: (response: ReturnType<typeof jsonResponse>) => void;
    vi.stubGlobal("fetch", vi.fn((url: string) => {
      if (url === "/api/v1/providers") {
        return new Promise((resolve) => { finishProviderRequest = resolve; });
      }
      if (url.startsWith("/api/v1/analysis-runs?")) {
        return Promise.resolve(jsonResponse({ items: [], nextCursor: null }));
      }
      throw new Error(`Unexpected browser request: ${url}`);
    }));

    renderWorkspace();
    expect(screen.getByText("Loading provider disclosures…")).toBeTruthy();

    finishProviderRequest(jsonResponse({ code: "PROVIDER_CATALOG_UNAVAILABLE", message: "Provider choices are temporarily unavailable." }, 503));
    expect(await screen.findByText("Provider choices are temporarily unavailable.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Upload & start Analysis Run" }).hasAttribute("disabled")).toBe(true);
  });

  it("refreshes and selects the newly created run after upload and re-analysis", async () => {
    const requests: Array<{ url: string; method: string }> = [];
    let runs: ReturnType<typeof analysisRun>[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, options: RequestInit = {}) => {
      const path = String(url);
      const method = options.method ?? "GET";
      requests.push({ url: path, method });
      if (path === "/api/v1/providers") return jsonResponse(providerDirectory);
      if (path.startsWith("/api/v1/analysis-runs?") && method === "GET") {
        return jsonResponse({ items: runs, nextCursor: null });
      }
      if (path === "/api/v1/analysis-runs" && method === "POST") {
        expect(options.body instanceof FormData).toBe(true);
        runs = [analysisRun("run-upload", "The uploaded Analysis Run is selected.")];
        return jsonResponse({
          documentId: "document-1",
          analysisRunId: "run-upload",
          filename: "source.pdf",
          sourceContentSha256: "a".repeat(64),
          status: "QUEUED",
          createdAt: "2025-01-01T00:00:00Z",
        });
      }
      if (path === "/api/v1/documents/document-1/analysis-runs" && method === "POST") {
        runs = [analysisRun("run-reanalyzed", "The re-analysis is selected.", "PROCESSING"), ...runs];
        return jsonResponse({
          documentId: "document-1",
          analysisRunId: "run-reanalyzed",
          filename: "source.pdf",
          sourceContentSha256: "a".repeat(64),
          status: "QUEUED",
          createdAt: "2025-01-02T00:00:00Z",
        });
      }
      throw new Error(`Unexpected browser request: ${method} ${path}`);
    }));

    const { container } = renderWorkspace();
    await screen.findByText("Local/mock providers selected");
    const fileInput = container.querySelector<HTMLInputElement>('input[name="file"]');
    expect(fileInput).toBeTruthy();
    fireEvent.change(fileInput!, {
      target: { files: [new File(["pdf"], "source.pdf", { type: "application/pdf" })] },
    });
    fireEvent.click(screen.getByRole("button", { name: "Upload & start Analysis Run" }));

    expect(await screen.findByText("The uploaded Analysis Run is selected.")).toBeTruthy();
    const uploadedRunRow = screen.getByRole("button", { name: /source\.pdf/ });
    expect(uploadedRunRow.getAttribute("aria-current")).toBe("true");

    fireEvent.click(screen.getByRole("button", { name: /Create a new run from this document/ }));
    expect(await screen.findByText("The re-analysis is selected.")).toBeTruthy();
    const runRows = screen.getAllByRole("button", { name: /source\.pdf/ });
    expect(runRows).toHaveLength(2);
    expect(runRows.find((row) => row.getAttribute("aria-current") === "true")?.textContent).toContain("processing");
    expect(requests.filter(({ url, method }) => url.startsWith("/api/v1/analysis-runs?") && method === "GET").length).toBeGreaterThanOrEqual(3);
    expect(requests.every(({ url }) => url.startsWith("/api/v1/"))).toBe(true);

    await waitFor(() => expect(screen.getByRole("heading", { name: "source.pdf" })).toBeTruthy());
  });

  it("polls while a displayed run is active and stops after all displayed runs become terminal", async () => {
    let listRequests = 0;
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      if (!url.startsWith("/api/v1/analysis-runs?")) throw new Error(`Unexpected browser request: ${url}`);
      listRequests += 1;
      return jsonResponse({
        items: listRequests === 1
          ? [analysisRun("run-active", "Working.", "PROCESSING"), analysisRun("run-failed", "Failed.", "FAILED")]
          : [analysisRun("run-complete", "Done.", "COMPLETED"), analysisRun("run-failed", "Failed.", "FAILED")],
        nextCursor: null,
      });
    }));

    render(
      <QueryProvider>
        <RecentRunPollingProbe />
      </QueryProvider>,
    );
    expect(await screen.findByText("PROCESSING,FAILED")).toBeTruthy();
    expect(await screen.findByText("COMPLETED,FAILED", {}, { timeout: 4000 })).toBeTruthy();
    await new Promise((resolve) => setTimeout(resolve, 2800));
    expect(listRequests).toBe(2);
  }, 9000);

  it("keeps safe upload and re-analysis API errors visible", async () => {
    const existingRun = analysisRun("run-existing", "Existing run progress.");
    vi.stubGlobal("fetch", vi.fn(async (url: string, options: RequestInit = {}) => {
      const path = String(url);
      const method = options.method ?? "GET";
      if (path === "/api/v1/providers") return jsonResponse(providerDirectory);
      if (path.startsWith("/api/v1/analysis-runs?") && method === "GET") {
        return jsonResponse({ items: [existingRun], nextCursor: null });
      }
      if (path === "/api/v1/documents/document-1/analysis-runs" && method === "POST") {
        return jsonResponse({ code: "INVALID_CONFIGURATION", message: "The selected provider configuration is not allowed." }, 400);
      }
      if (path === "/api/v1/analysis-runs" && method === "POST") {
        return jsonResponse({ code: "PDF_LIMIT_EXCEEDED", message: "The PDF is over the configured upload limit." }, 413);
      }
      throw new Error(`Unexpected browser request: ${method} ${path}`);
    }));

    const { container } = renderWorkspace();
    await screen.findByText("Local/mock providers selected");
    fireEvent.click(await screen.findByRole("button", { name: /source\.pdf/ }));
    fireEvent.click(screen.getByRole("button", { name: /Create a new run from this document/ }));
    expect(await screen.findByText("The selected provider configuration is not allowed.")).toBeTruthy();

    const fileInput = container.querySelector<HTMLInputElement>('input[name="file"]');
    fireEvent.change(fileInput!, {
      target: { files: [new File(["pdf"], "source.pdf", { type: "application/pdf" })] },
    });
    fireEvent.click(screen.getByRole("button", { name: "Upload & start Analysis Run" }));
    expect(await screen.findByText("The PDF is over the configured upload limit.")).toBeTruthy();
  });
});
