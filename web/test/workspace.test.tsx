import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { QueryProvider } from "@/app/query-provider";
import { UploadDashboard } from "@/features/workspace/components/upload-dashboard";
import { useRecentAnalysisRuns } from "@/features/analysis-runs/queries/analysis-run-queries";

const providerDirectory = {
  providers: {
    claimExtractor: [
      { role: "claimExtractor", providerId: "heuristic", displayName: "Heuristic", version: "v1", model: null, trustBoundary: "LOCAL", dataCategories: ["citation_context"], retentionDisclosure: null },
      { role: "claimExtractor", providerId: "hosted-ai", displayName: "Hosted AI", version: "v2", model: "model-2", trustBoundary: "EXTERNAL", dataCategories: ["citation_context"], retentionDisclosure: "Provider retention terms reviewed for this deployment." },
      { role: "claimExtractor", providerId: "unclassified-ai", displayName: "Unclassified AI", version: "v1", model: null, trustBoundary: "UNREVIEWED", dataCategories: ["citation_context"], retentionDisclosure: null },
    ],
    embedding: [
      { role: "embedding", providerId: "local", displayName: "Local", version: "v1", model: null, trustBoundary: "LOCAL", dataCategories: ["atomic_claims", "cited_paper_chunks", "embedding_input"], retentionDisclosure: null },
      { role: "embedding", providerId: "ollama", displayName: "Ollama embeddings (nomic-embed-text:v1.5)", version: "v1", model: "nomic-embed-text:v1.5", trustBoundary: "LOCAL", dataCategories: ["atomic_claims", "cited_paper_chunks", "embedding_input"], retentionDisclosure: null },
      { role: "embedding", providerId: "hosted-ai", displayName: "Hosted AI", version: "v2", model: "embed-2", trustBoundary: "EXTERNAL", dataCategories: ["atomic_claims", "cited_paper_chunks", "embedding_input"], retentionDisclosure: "Provider retention terms reviewed for this deployment." },
    ],
    systemOne: [{ role: "systemOne", providerId: "mock", displayName: "Mock", version: "v1", model: null, trustBoundary: "LOCAL", dataCategories: ["atomic_claims", "evidence_passages"], retentionDisclosure: null }],
    scholarlyMetadata: [{ role: "scholarlyMetadata", providerId: "recorded-fixtures", displayName: "Recorded fixtures", version: "v1", model: null, trustBoundary: "LOCAL", dataCategories: ["bibliographic_metadata"], retentionDisclosure: null }],
    openAccess: [
      { role: "openAccess", providerId: "recorded-fixtures", displayName: "Recorded OA fixtures", version: "v1", model: null, trustBoundary: "LOCAL", dataCategories: ["bibliographic_metadata", "cited_paper_location"], retentionDisclosure: null },
    ],
  },
  dataCategories: [
    { id: "citation_context", label: "Citation Context", description: "The citation-bearing clause or sentence." },
    { id: "atomic_claims", label: "Atomic Claims", description: "Individual propositions submitted for assessment." },
    { id: "cited_paper_chunks", label: "Cited Paper chunks", description: "Text chunks from an acquired Cited Paper." },
    { id: "embedding_input", label: "Embedding input", description: "Text submitted to calculate embeddings." },
    { id: "bibliographic_metadata", label: "Bibliographic metadata", description: "DOIs and minimum lookup fields." },
    { id: "cited_paper_location", label: "Cited Paper location", description: "A discovered full-text URL." },
    { id: "provider_contact_email", label: "Provider contact email", description: "Contact email sent to a provider." },
  ],
};

const unpaywallOnlyProviderDirectory = {
  ...providerDirectory,
  providers: {
    ...providerDirectory.providers,
    openAccess: [
      ...providerDirectory.providers.openAccess,
      { role: "openAccess", providerId: "unpaywall", displayName: "Unpaywall and discovered open-access hosts", version: "v2", model: null, trustBoundary: "EXTERNAL", dataCategories: ["bibliographic_metadata", "cited_paper_location", "provider_contact_email"], retentionDisclosure: "Reviewed Unpaywall request and retention disclosure." },
    ],
  },
};

const preferredProviderDirectory = {
  ...unpaywallOnlyProviderDirectory,
  providers: {
    ...unpaywallOnlyProviderDirectory.providers,
    systemOne: [
      ...providerDirectory.providers.systemOne,
      { role: "systemOne", providerId: "laya", displayName: "Laya local System One", version: "v1", model: "laya-calibrated", trustBoundary: "LOCAL", dataCategories: ["atomic_claims", "evidence_passages"], retentionDisclosure: null },
    ],
    scholarlyMetadata: [
      ...providerDirectory.providers.scholarlyMetadata,
      { role: "scholarlyMetadata", providerId: "crossref", displayName: "Crossref REST API", version: "v1", model: null, trustBoundary: "EXTERNAL", dataCategories: ["bibliographic_metadata"], retentionDisclosure: "Reviewed Crossref request and retention disclosure." },
    ],
  },
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
      embedding: { provider: "local", model: "feature-hash-384-v1", version: "v1" },
      retrieval: {
        profileId: "postgres-hybrid-rrf-v1",
        vectorCandidateLimit: 10,
        lexicalCandidateLimit: 10,
        finalCandidateLimit: 5,
        reciprocalRankFusionConstant: 60,
        embeddingProfileHash: "b".repeat(64),
      },
      systemOne: { provider: "mock", version: "v1" },
      openAccess: { provider: "recorded-fixtures", version: "v1" },
      referenceResolution: { provider: { provider: "recorded-fixtures", version: "v1" } },
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

  it("selects preferred providers when available without treating defaults as consent", async () => {
    let submittedConfiguration: Record<string, unknown> | null = null;
    const runs: ReturnType<typeof analysisRun>[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, options: RequestInit = {}) => {
      const path = String(url);
      const method = options.method ?? "GET";
      if (path === "/api/v1/providers") return jsonResponse(preferredProviderDirectory);
      if (path.startsWith("/api/v1/analysis-runs?") && method === "GET") {
        return jsonResponse({ items: runs, nextCursor: null });
      }
      if (path === "/api/v1/analysis-runs" && method === "POST") {
        const form = options.body as FormData;
        submittedConfiguration = JSON.parse(String(form.get("configuration"))) as Record<string, unknown>;
        const savedRun = analysisRun("run-preferred", "The preferred-provider Analysis Run is selected.");
        savedRun.configuration.systemOne.provider = "laya";
        savedRun.configuration.openAccess.provider = "unpaywall";
        savedRun.configuration.referenceResolution!.provider!.provider = "crossref";
        runs.push(savedRun);
        return jsonResponse({
          documentId: "document-1",
          analysisRunId: "run-preferred",
          filename: "source.pdf",
          sourceContentSha256: "a".repeat(64),
          status: "QUEUED",
          createdAt: "2025-01-01T00:00:00Z",
        });
      }
      throw new Error(`Unexpected browser request: ${method} ${path}`);
    }));

    const { container } = renderWorkspace();
    await screen.findByText("Crossref REST API data access");
    expect((screen.getByLabelText("Evidence assessment") as HTMLSelectElement).value).toBe("laya");
    expect((screen.getByLabelText("Bibliography resolution") as HTMLSelectElement).value).toBe("crossref");
    expect((screen.getByLabelText("Cited full-text access") as HTMLSelectElement).value).toBe("unpaywall");

    const externalApprovals = screen.getAllByRole("checkbox");
    expect(externalApprovals).toHaveLength(4);
    expect(externalApprovals.every((approval) => approval.getAttribute("aria-checked") === "false")).toBe(true);
    const uploadButton = screen.getByRole("button", { name: "Upload & start Analysis Run" });
    expect(uploadButton.hasAttribute("disabled")).toBe(true);
    externalApprovals.forEach((approval) => fireEvent.click(approval));
    expect(uploadButton.hasAttribute("disabled")).toBe(false);

    const fileInput = container.querySelector<HTMLInputElement>('input[name="file"]');
    fireEvent.change(fileInput!, {
      target: { files: [new File(["pdf"], "source.pdf", { type: "application/pdf" })] },
    });
    fireEvent.click(uploadButton);

    expect(await screen.findByText("The preferred-provider Analysis Run is selected.")).toBeTruthy();
    expect(submittedConfiguration).toMatchObject({
      systemOneProvider: "laya",
      scholarlyMetadataProvider: "crossref",
      openAccessProvider: "unpaywall",
      externalProviderConsents: [
        { providerId: "crossref", dataCategories: ["bibliographic_metadata"] },
        { providerId: "unpaywall", dataCategories: ["bibliographic_metadata", "cited_paper_location", "provider_contact_email"] },
      ],
    });
    expect(screen.getByText(/Evidence laya · Bibliography crossref · Cited full text unpaywall/)).toBeTruthy();
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
    await screen.findByText("Local providers selected");
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

  it("offers trusted Ollama embeddings without external-provider consent", async () => {
    let submittedConfiguration: Record<string, unknown> | null = null;
    const runs: ReturnType<typeof analysisRun>[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, options: RequestInit = {}) => {
      const path = String(url);
      if (path === "/api/v1/providers") return jsonResponse(providerDirectory);
      if (path.startsWith("/api/v1/analysis-runs?") && (options.method ?? "GET") === "GET") {
        return jsonResponse({ items: runs, nextCursor: null });
      }
      if (path === "/api/v1/analysis-runs" && options.method === "POST") {
        const form = options.body as FormData;
        submittedConfiguration = JSON.parse(String(form.get("configuration"))) as Record<string, unknown>;
        runs.push(analysisRun("run-ollama", "The Ollama-backed Analysis Run is selected."));
        return jsonResponse({
          documentId: "document-1",
          analysisRunId: "run-ollama",
          filename: "source.pdf",
          sourceContentSha256: "a".repeat(64),
          status: "QUEUED",
          createdAt: "2025-01-01T00:00:00Z",
        });
      }
      throw new Error(`Unexpected browser request: ${options.method ?? "GET"} ${path}`);
    }));

    const { container } = renderWorkspace();
    await screen.findByText("Local providers selected");
    fireEvent.change(screen.getByLabelText("Embeddings"), { target: { value: "ollama" } });
    expect(screen.getByText("No external provider receives document content for this run.")).toBeTruthy();

    const fileInput = container.querySelector<HTMLInputElement>('input[name="file"]');
    expect(fileInput).toBeTruthy();
    fireEvent.change(fileInput!, {
      target: { files: [new File(["pdf"], "source.pdf", { type: "application/pdf" })] },
    });
    fireEvent.click(screen.getByRole("button", { name: "Upload & start Analysis Run" }));

    await waitFor(() => expect(submittedConfiguration).toMatchObject({
      embeddingProvider: "ollama",
      externalProviderConsents: [],
    }));
  });

  it("recalculates provider consent on selection changes and requires fresh consent for each Analysis Run", async () => {
    const submittedConfigurations: Array<{ claimExtractorProvider: string; externalProviderConsents: Array<{ providerId: string; dataCategories: string[] }> }> = [];
    let runs: ReturnType<typeof analysisRun>[] = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, options: RequestInit = {}) => {
      const path = String(url);
      const method = options.method ?? "GET";
      if (path === "/api/v1/providers") return jsonResponse(providerDirectory);
      if (path.startsWith("/api/v1/analysis-runs?") && method === "GET") {
        return jsonResponse({ items: runs, nextCursor: null });
      }
      if (path === "/api/v1/analysis-runs" && method === "POST") {
        const formData = options.body as FormData;
        submittedConfigurations.push(JSON.parse(String(formData.get("configuration"))));
        runs = [analysisRun("run-first", "The first Analysis Run is selected.")];
        return jsonResponse({
          documentId: "document-1",
          analysisRunId: "run-first",
          filename: "source.pdf",
          sourceContentSha256: "a".repeat(64),
          status: "QUEUED",
          createdAt: "2025-01-01T00:00:00Z",
        });
      }
      if (path === "/api/v1/documents/document-1/analysis-runs" && method === "POST") {
        submittedConfigurations.push(JSON.parse(String(options.body)));
        runs = [analysisRun("run-second", "The second Analysis Run is selected.", "PROCESSING"), ...runs];
        return jsonResponse({
          documentId: "document-1",
          analysisRunId: "run-second",
          filename: "source.pdf",
          sourceContentSha256: "a".repeat(64),
          status: "QUEUED",
          createdAt: "2025-01-02T00:00:00Z",
        });
      }
      throw new Error(`Unexpected browser request: ${method} ${path}`);
    }));

    const { container } = renderWorkspace();
    await screen.findByText("Local providers selected");
    const claimExtractor = screen.getByLabelText("Claim extraction");
    fireEvent.change(claimExtractor, { target: { value: "hosted-ai" } });

    const citationContextConsent = await screen.findByRole("checkbox", { name: "Citation Context" });
    expect(citationContextConsent.getAttribute("aria-checked")).toBe("false");
    fireEvent.click(citationContextConsent);
    expect(screen.getByRole("button", { name: "Upload & start Analysis Run" }).hasAttribute("disabled")).toBe(false);

    fireEvent.change(claimExtractor, { target: { value: "heuristic" } });
    expect(screen.queryByText("Hosted AI data access")).toBeNull();
    fireEvent.change(claimExtractor, { target: { value: "hosted-ai" } });
    expect((await screen.findByRole("checkbox", { name: "Citation Context" })).getAttribute("aria-checked")).toBe("false");

    fireEvent.click(screen.getByRole("checkbox", { name: "Citation Context" }));
    const fileInput = container.querySelector<HTMLInputElement>('input[name="file"]');
    fireEvent.change(fileInput!, {
      target: { files: [new File(["pdf"], "source.pdf", { type: "application/pdf" })] },
    });
    fireEvent.click(screen.getByRole("button", { name: "Upload & start Analysis Run" }));

    expect(await screen.findByText("The first Analysis Run is selected.")).toBeTruthy();
    expect(submittedConfigurations[0].externalProviderConsents).toEqual([{
      providerId: "hosted-ai",
      dataCategories: ["citation_context"],
    }]);
    expect((await screen.findByRole("checkbox", { name: "Citation Context" })).getAttribute("aria-checked")).toBe("false");

    const reanalyzeButton = await screen.findByRole("button", { name: /Create a new run from this document/ });
    expect(reanalyzeButton.hasAttribute("disabled")).toBe(true);
    fireEvent.click(screen.getByRole("checkbox", { name: "Citation Context" }));
    expect(reanalyzeButton.hasAttribute("disabled")).toBe(false);
    fireEvent.click(reanalyzeButton);

    expect(await screen.findByText("The second Analysis Run is selected.")).toBeTruthy();
    expect(submittedConfigurations[1].externalProviderConsents).toEqual([{
      providerId: "hosted-ai",
      dataCategories: ["citation_context"],
    }]);
  });

  it("blocks external cited full-text acquisition until every disclosed category is approved", async () => {
    const requests: Array<{ url: string; method: string }> = [];
    let submittedConfiguration: Record<string, unknown> | null = null;
    vi.stubGlobal("fetch", vi.fn(async (url: string, options: RequestInit = {}) => {
      const path = String(url);
      const method = options.method ?? "GET";
      requests.push({ url: path, method });
      if (path === "/api/v1/providers") return jsonResponse(unpaywallOnlyProviderDirectory);
      if (path.startsWith("/api/v1/analysis-runs?") && method === "GET") {
        return jsonResponse({ items: [], nextCursor: null });
      }
      if (path === "/api/v1/analysis-runs" && method === "POST") {
        const formData = options.body as FormData;
        submittedConfiguration = JSON.parse(String(formData.get("configuration")));
        return jsonResponse({
          documentId: "document-1",
          analysisRunId: "run-open-access",
          filename: "source.pdf",
          sourceContentSha256: "a".repeat(64),
          status: "QUEUED",
          createdAt: "2025-01-01T00:00:00Z",
        });
      }
      throw new Error(`Unexpected browser request: ${method} ${path}`);
    }));

    const { container } = renderWorkspace();
    await screen.findByText("Unpaywall and discovered open-access hosts data access");

    const categories = [
      await screen.findByRole("checkbox", { name: "Bibliographic metadata" }),
      screen.getByRole("checkbox", { name: "Cited Paper location" }),
      screen.getByRole("checkbox", { name: "Provider contact email" }),
    ];
    expect(categories.every((category) => category.getAttribute("aria-checked") === "false")).toBe(true);

    const fileInput = container.querySelector<HTMLInputElement>('input[name="file"]');
    fireEvent.change(fileInput!, {
      target: { files: [new File(["pdf"], "source.pdf", { type: "application/pdf" })] },
    });
    const uploadButton = screen.getByRole("button", { name: "Upload & start Analysis Run" });
    expect(uploadButton.hasAttribute("disabled")).toBe(true);
    fireEvent.click(uploadButton);
    expect(requests.some(({ url, method }) => url === "/api/v1/analysis-runs" && method === "POST")).toBe(false);

    categories.forEach((category) => fireEvent.click(category));
    expect(uploadButton.hasAttribute("disabled")).toBe(false);
    fireEvent.click(uploadButton);
    await waitFor(() => expect(submittedConfiguration).not.toBeNull());
    expect(submittedConfiguration).toMatchObject({
      openAccessProvider: "unpaywall",
      externalProviderConsents: [{
        providerId: "unpaywall",
        dataCategories: ["bibliographic_metadata", "cited_paper_location", "provider_contact_email"],
      }],
    });
    expect(requests.every(({ url }) => url.startsWith("/api/v1/"))).toBe(true);
  });

  it("explains and confirms local document deletion before removing the document's runs", async () => {
    const requests: Array<{ url: string; method: string }> = [];
    let runs: ReturnType<typeof analysisRun>[] = [analysisRun("run-delete", "Waiting for worker.", "PROCESSING")];
    vi.stubGlobal("fetch", vi.fn(async (url: string, options: RequestInit = {}) => {
      const path = String(url);
      const method = options.method ?? "GET";
      requests.push({ url: path, method });
      if (path === "/api/v1/providers") return jsonResponse(providerDirectory);
      if (path.startsWith("/api/v1/analysis-runs?") && method === "GET") {
        return jsonResponse({ items: runs, nextCursor: null });
      }
      if (path === "/api/v1/documents/document-1" && method === "DELETE") {
        runs = [];
        return { ok: true, status: 204 };
      }
      throw new Error(`Unexpected browser request: ${method} ${path}`);
    }));

    renderWorkspace();
    await screen.findByText("Local providers selected");
    fireEvent.click(await screen.findByRole("button", { name: /source\.pdf/ }));
    fireEvent.click(screen.getByRole("button", { name: /Delete Source Document and all Analysis Runs/ }));

    expect(screen.getByRole("heading", { name: "Confirm permanent deletion" })).toBeTruthy();
    expect(screen.getByText(/uploaded PDF, parsed content, every Analysis Run/)).toBeTruthy();
    expect(screen.getByText(/cannot be retracted by Paper T-Rail/)).toBeTruthy();
    expect(requests.some(({ method }) => method === "DELETE")).toBe(false);

    fireEvent.click(screen.getByRole("button", { name: /Permanently delete Source Document/ }));
    expect(await screen.findByText("Your first Analysis Run will appear here.")).toBeTruthy();
    expect(requests.some(({ url, method }) => url === "/api/v1/documents/document-1" && method === "DELETE")).toBe(true);
    expect(screen.getByText("Select an Analysis Run to inspect its progress and parsed document.")).toBeTruthy();
  });

  it("keeps deletion confirmation available and reports a safe error when deletion fails", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string, options: RequestInit = {}) => {
      const path = String(url);
      const method = options.method ?? "GET";
      if (path === "/api/v1/providers") return jsonResponse(providerDirectory);
      if (path.startsWith("/api/v1/analysis-runs?") && method === "GET") {
        return jsonResponse({ items: [analysisRun("run-delete-error", "Waiting for worker.")], nextCursor: null });
      }
      if (path === "/api/v1/documents/document-1" && method === "DELETE") {
        return jsonResponse({ code: "DELETION_INCOMPLETE", message: "Retry deletion to finish removing local data." }, 503);
      }
      throw new Error(`Unexpected browser request: ${method} ${path}`);
    }));

    renderWorkspace();
    await screen.findByText("Local providers selected");
    fireEvent.click(await screen.findByRole("button", { name: /source\.pdf/ }));
    fireEvent.click(screen.getByRole("button", { name: /Delete Source Document and all Analysis Runs/ }));
    fireEvent.click(screen.getByRole("button", { name: /Permanently delete Source Document/ }));

    expect(await screen.findByText("Retry deletion to finish removing local data.")).toBeTruthy();
    expect(screen.getByRole("button", { name: /Permanently delete Source Document/ })).toBeTruthy();
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
    await screen.findByText("Local providers selected");
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
