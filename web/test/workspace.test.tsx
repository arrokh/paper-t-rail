import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { QueryProvider } from "@/app/query-provider";
import { UploadDashboard } from "@/features/workspace/components/upload-dashboard";
import { useRecentAnalysisRuns } from "@/features/analysis-runs/queries/analysis-run-queries";

vi.mock("next/navigation", () => {
  const router = { back: vi.fn(), forward: vi.fn(), push: vi.fn(), refresh: vi.fn(), replace: vi.fn() };
  const searchParams = new URLSearchParams();
  return {
    usePathname: () => "/",
    useRouter: () => router,
    useSearchParams: () => searchParams,
  };
});

const providerDirectory = {
  providers: {
    claimExtractor: [
      { role: "claimExtractor", providerId: "heuristic", displayName: "Heuristic", version: "v1", model: null, trustBoundary: "LOCAL", dataCategories: ["citation_context"], retentionDisclosure: null, retentionDisclosureFingerprint: null },
      { role: "claimExtractor", providerId: "openai-compatible-chat", displayName: "OpenAI-compatible Chat Completions", version: "v1", model: "google/gemma-4-e2b", trustBoundary: "LOCAL", dataCategories: ["citation_context", "bibliographic_metadata"], retentionDisclosure: null, retentionDisclosureFingerprint: null },
      { role: "claimExtractor", providerId: "hosted-ai", displayName: "Hosted AI", version: "v2", model: "model-2", trustBoundary: "EXTERNAL", dataCategories: ["citation_context"], retentionDisclosure: "Retention and deletion details are unknown; consult the provider's terms.", retentionDisclosureFingerprint: "a".repeat(64) },
      { role: "claimExtractor", providerId: "unclassified-ai", displayName: "Unclassified AI", version: "v1", model: null, trustBoundary: "UNREVIEWED", dataCategories: ["citation_context"], retentionDisclosure: null, retentionDisclosureFingerprint: null },
    ],
    embedding: [
      { role: "embedding", providerId: "local", displayName: "Local", version: "v1", model: null, trustBoundary: "LOCAL", dataCategories: ["atomic_claims", "cited_paper_chunks", "embedding_input"], retentionDisclosure: null, retentionDisclosureFingerprint: null },
      { role: "embedding", providerId: "ollama", displayName: "Ollama embeddings (nomic-embed-text:v1.5)", version: "v1", model: "nomic-embed-text:v1.5", trustBoundary: "LOCAL", dataCategories: ["atomic_claims", "cited_paper_chunks", "embedding_input"], retentionDisclosure: null, retentionDisclosureFingerprint: null },
      { role: "embedding", providerId: "hosted-ai", displayName: "Hosted AI", version: "v2", model: "embed-2", trustBoundary: "EXTERNAL", dataCategories: ["atomic_claims", "cited_paper_chunks", "embedding_input"], retentionDisclosure: "Retention and deletion details are unknown; consult the provider's terms.", retentionDisclosureFingerprint: "a".repeat(64) },
    ],
    systemOne: [{ role: "systemOne", providerId: "mock", displayName: "Mock", version: "v1", model: null, trustBoundary: "LOCAL", dataCategories: ["atomic_claims", "evidence_passages"], retentionDisclosure: null, retentionDisclosureFingerprint: null }],
    scholarlyMetadata: [{ role: "scholarlyMetadata", providerId: "recorded-fixtures", displayName: "Recorded fixtures", version: "v1", model: null, trustBoundary: "LOCAL", dataCategories: ["bibliographic_metadata"], retentionDisclosure: null, retentionDisclosureFingerprint: null }],
    openAccess: [
      { role: "openAccess", providerId: "recorded-fixtures", displayName: "Recorded OA fixtures", version: "v1", model: null, trustBoundary: "LOCAL", dataCategories: ["bibliographic_metadata", "cited_paper_location"], retentionDisclosure: null, retentionDisclosureFingerprint: null },
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

const providerDirectoryWithoutOpenAi = {
  ...providerDirectory,
  providers: {
    ...providerDirectory.providers,
    claimExtractor: providerDirectory.providers.claimExtractor.filter(({ providerId }) => providerId !== "openai-compatible-chat"),
  },
};

const unpaywallOnlyProviderDirectory = {
  ...providerDirectory,
  providers: {
    ...providerDirectory.providers,
    openAccess: [
      ...providerDirectory.providers.openAccess,
      { role: "openAccess", providerId: "unpaywall", displayName: "Unpaywall and discovered open-access hosts", version: "v2", model: null, trustBoundary: "EXTERNAL", dataCategories: ["bibliographic_metadata", "cited_paper_location", "provider_contact_email"], retentionDisclosure: "Unpaywall request details are disclosed; retention and deletion details are unknown.", retentionDisclosureFingerprint: "a".repeat(64) },
    ],
  },
};

const preferredProviderDirectory = {
  ...unpaywallOnlyProviderDirectory,
  providers: {
    ...unpaywallOnlyProviderDirectory.providers,
    systemOne: [
      ...providerDirectory.providers.systemOne,
      { role: "systemOne", providerId: "laya", displayName: "Laya System One (local evaluation)", version: "laya-serve-0.3.20@23a17522aa4942da6cce53a995a275760320b691/pt-ej-v1", model: "convaiinnovations/laya-typed-decisions@1a793eb568e6718f15941d08f85432581df534e3", trustBoundary: "LOCAL", dataCategories: ["atomic_claims", "evidence_passages"], retentionDisclosure: null, retentionDisclosureFingerprint: null },
      { role: "systemOne", providerId: "jev", displayName: "Jev hosted System One", version: "typesafe-system-one-v1/paper-trail-evidence-judgement-v1", model: "jev-latest", trustBoundary: "EXTERNAL", dataCategories: ["atomic_claims", "evidence_passages"], retentionDisclosure: "Retention and deletion details are unknown; consult the provider's terms.", retentionDisclosureFingerprint: "b".repeat(64) },
    ],
    scholarlyMetadata: [
      ...providerDirectory.providers.scholarlyMetadata,
      { role: "scholarlyMetadata", providerId: "crossref", displayName: "Crossref REST API", version: "v1", model: null, trustBoundary: "EXTERNAL", dataCategories: ["bibliographic_metadata"], retentionDisclosure: "Reviewed Crossref request and retention disclosure.", retentionDisclosureFingerprint: "a".repeat(64) },
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
  const rendered = render(
    <QueryProvider>
      <UploadDashboard />
    </QueryProvider>,
  );
  fireEvent.click(screen.getByRole("button", { name: "New Analysis Run" }));
  return rendered;
}

async function waitForProviderDirectory() {
  await waitFor(() => expect((screen.getByLabelText("Claim extraction") as HTMLSelectElement).disabled).toBe(false));
}

async function clickContinue() {
  const button = screen.getByRole("button", { name: "Continue" });
  await waitFor(() => expect(button.hasAttribute("disabled")).toBe(false));
  fireEvent.click(button);
}

async function continueToConsentStep() {
  await screen.findByRole("heading", { name: "Choose your services" });
  await clickContinue();
  await screen.findByRole("heading", { name: "Review data sharing" });
}

async function continueToPdfStepWithLocalServices() {
  await continueToConsentStep();
  expect(await screen.findByText("No external providers selected")).toBeTruthy();
  await clickContinue();
  await screen.findByRole("heading", { name: "Select your PDF" });
}

function RecentRunPollingProbe() {
  const runsQuery = useRecentAnalysisRuns(null);
  return <p>{runsQuery.data?.items.map((run) => run.status).join(",") ?? "Loading"}</p>;
}

afterEach(() => vi.unstubAllGlobals());

describe("interactive workspace remote state", () => {
  it("keeps an unavailable claim-analysis default visible instead of silently choosing heuristic", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      if (url === "/api/v1/providers") return jsonResponse(providerDirectoryWithoutOpenAi);
      if (url.startsWith("/api/v1/analysis-runs?")) return jsonResponse({ items: [], nextCursor: null });
      throw new Error(`Unexpected browser request: ${url}`);
    }));

    renderWorkspace();
    await screen.findByRole("heading", { name: "Choose your services" });
    await waitForProviderDirectory();

    const claimExtractor = screen.getByLabelText("Claim extraction") as HTMLSelectElement;
    expect(claimExtractor.value).toBe("openai-compatible-chat");
    expect(Array.from(claimExtractor.options).some((option) => option.textContent === "Selected provider unavailable")).toBe(true);
    expect(screen.getByText("Claim-analysis provider unavailable")).toBeTruthy();
    expect(Array.from(claimExtractor.options).some((option) => option.value === "heuristic")).toBe(true);

    fireEvent.change(claimExtractor, { target: { value: "heuristic" } });
    await waitFor(() => expect(claimExtractor.value).toBe("heuristic"));
    expect(screen.queryByText("Claim-analysis provider unavailable")).toBeNull();
  });

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
    expect(screen.getAllByText("Loading provider choices…").some((element) => element.tagName === "P")).toBe(true);

    finishProviderRequest(jsonResponse({ code: "PROVIDER_CATALOG_UNAVAILABLE", message: "Provider choices are temporarily unavailable." }, 503));
    expect(await screen.findByText("Provider choices are temporarily unavailable.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Continue" }).hasAttribute("disabled")).toBe(true);
  });

  it("preselects configured Jev, Crossref, and Unpaywall but requires explicit per-run consent", async () => {
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
        savedRun.configuration.systemOne.provider = "jev";
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

    renderWorkspace();
    await screen.findByRole("heading", { name: "Choose your services" });
    await waitForProviderDirectory();
    const evidenceAssessment = screen.getByLabelText("Evidence assessment") as HTMLSelectElement;
    expect(evidenceAssessment.value).toBe("jev");
    expect((screen.getByLabelText("Bibliography resolution") as HTMLSelectElement).value).toBe("crossref");
    expect((screen.getByLabelText("Cited full-text access") as HTMLSelectElement).value).toBe("unpaywall");
    await continueToConsentStep();
    await screen.findByText("Jev hosted System One data access");
    await screen.findByText("Crossref REST API data access");

    const externalApprovals = screen.getAllByRole("checkbox");
    expect(externalApprovals).toHaveLength(6);
    expect(externalApprovals.every((approval) => approval.getAttribute("aria-checked") === "false")).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Approve all 6 categories across external providers" }));
    expect(externalApprovals.every((approval) => approval.getAttribute("aria-checked") === "true")).toBe(true);
    expect(screen.getByText("6 of 6 categories approved")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Clear approvals for all 6 categories across external providers" }));
    expect(externalApprovals.every((approval) => approval.getAttribute("aria-checked") === "false")).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Approve all 6 categories across external providers" }));
    await clickContinue();
    await screen.findByRole("heading", { name: "Select your PDF" });

    const fileInput = document.querySelector<HTMLInputElement>('input[name="file"]');
    fireEvent.change(fileInput!, {
      target: { files: [new File(["pdf"], "source.pdf", { type: "application/pdf" })] },
    });
    fireEvent.click(screen.getByRole("button", { name: "Upload & start Analysis Run" }));

    expect(await screen.findByRole("link", { name: "Open Analysis Run for source.pdf" })).toBeTruthy();
    expect(submittedConfiguration).toMatchObject({
      systemOneProvider: "jev",
      scholarlyMetadataProvider: "crossref",
      openAccessProvider: "unpaywall",
      externalProviderConsents: [
        { providerId: "crossref", dataCategories: ["bibliographic_metadata"], retentionDisclosureFingerprint: "a".repeat(64) },
        { providerId: "jev", dataCategories: ["atomic_claims", "evidence_passages"], retentionDisclosureFingerprint: "b".repeat(64) },
        { providerId: "unpaywall", dataCategories: ["bibliographic_metadata", "cited_paper_location", "provider_contact_email"], retentionDisclosureFingerprint: "a".repeat(64) },
      ],
    });
  });

  it("refreshes the run table after uploading a new Analysis Run", async () => {
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
      throw new Error(`Unexpected browser request: ${method} ${path}`);
    }));

    renderWorkspace();
    await continueToPdfStepWithLocalServices();
    const fileInput = document.querySelector<HTMLInputElement>('input[name="file"]');
    expect(fileInput).toBeTruthy();
    fireEvent.change(fileInput!, {
      target: { files: [new File(["pdf"], "source.pdf", { type: "application/pdf" })] },
    });
    fireEvent.click(screen.getByRole("button", { name: "Upload & start Analysis Run" }));

    const uploadedRunLink = await screen.findByRole("link", { name: "Open Analysis Run for source.pdf" });
    expect(uploadedRunLink.getAttribute("href")).toContain("run-upload");
    expect(requests.filter(({ url, method }) => url.startsWith("/api/v1/analysis-runs?") && method === "GET").length).toBeGreaterThanOrEqual(2);
    expect(requests.every(({ url }) => url.startsWith("/api/v1/"))).toBe(true);
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

    renderWorkspace();
    await screen.findByRole("heading", { name: "Choose your services" });
    await waitForProviderDirectory();
    fireEvent.change(screen.getByLabelText("Embeddings"), { target: { value: "ollama" } });
    await continueToConsentStep();
    expect(screen.getByText("No external providers selected")).toBeTruthy();
    await clickContinue();
    await screen.findByRole("heading", { name: "Select your PDF" });

    const fileInput = document.querySelector<HTMLInputElement>('input[name="file"]');
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
    const submittedConfigurations: Array<{ claimExtractorProvider: string; externalProviderConsents: Array<{ providerId: string; dataCategories: string[]; retentionDisclosureFingerprint: string }> }> = [];
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
      throw new Error(`Unexpected browser request: ${method} ${path}`);
    }));

    renderWorkspace();
    await screen.findByRole("heading", { name: "Choose your services" });
    await waitForProviderDirectory();
    fireEvent.change(screen.getByLabelText("Claim extraction"), { target: { value: "hosted-ai" } });

    await continueToConsentStep();
    const citationContextConsent = await screen.findByRole("checkbox", { name: "Citation Context" });
    expect(citationContextConsent.getAttribute("aria-checked")).toBe("false");
    fireEvent.click(citationContextConsent);
    expect(screen.getByRole("button", { name: "Continue" }).hasAttribute("disabled")).toBe(false);

    fireEvent.click(screen.getByRole("button", { name: "Back" }));
    await screen.findByRole("heading", { name: "Choose your services" });
    fireEvent.change(screen.getByLabelText("Claim extraction"), { target: { value: "heuristic" } });
    await clickContinue();
    await screen.findByRole("heading", { name: "Review data sharing" });
    expect(screen.queryByText("Hosted AI data access")).toBeNull();
    expect(screen.getByText("No external providers selected")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Back" }));
    await screen.findByRole("heading", { name: "Choose your services" });
    fireEvent.change(screen.getByLabelText("Claim extraction"), { target: { value: "hosted-ai" } });
    await clickContinue();
    await screen.findByRole("heading", { name: "Review data sharing" });
    expect((await screen.findByRole("checkbox", { name: "Citation Context" })).getAttribute("aria-checked")).toBe("false");

    fireEvent.click(screen.getByRole("checkbox", { name: "Citation Context" }));
    await clickContinue();
    await screen.findByRole("heading", { name: "Select your PDF" });
    const fileInput = document.querySelector<HTMLInputElement>('input[name="file"]');
    fireEvent.change(fileInput!, {
      target: { files: [new File(["pdf"], "source.pdf", { type: "application/pdf" })] },
    });
    fireEvent.click(screen.getByRole("button", { name: "Upload & start Analysis Run" }));

    expect(await screen.findByRole("link", { name: "Open Analysis Run for source.pdf" })).toBeTruthy();
    expect(submittedConfigurations[0].externalProviderConsents).toEqual([{
      providerId: "hosted-ai",
      dataCategories: ["citation_context"],
      retentionDisclosureFingerprint: "a".repeat(64),
    }]);
    fireEvent.click(screen.getByRole("button", { name: "New Analysis Run" }));
    await screen.findByRole("heading", { name: "Choose your services" });
    await waitForProviderDirectory();
    fireEvent.change(screen.getByLabelText("Claim extraction"), { target: { value: "heuristic" } });
    await waitFor(() => expect((screen.getByLabelText("Claim extraction") as HTMLSelectElement).value).toBe("heuristic"));
    fireEvent.change(screen.getByLabelText("Claim extraction"), { target: { value: "hosted-ai" } });
    await waitFor(() => expect((screen.getByLabelText("Claim extraction") as HTMLSelectElement).value).toBe("hosted-ai"));
    await continueToConsentStep();
    expect((await screen.findByRole("checkbox", { name: "Citation Context" })).getAttribute("aria-checked")).toBe("false");
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

    renderWorkspace();
    await screen.findByRole("heading", { name: "Choose your services" });
    await waitForProviderDirectory();
    fireEvent.change(screen.getByLabelText("Cited full-text access"), { target: { value: "unpaywall" } });
    await continueToConsentStep();
    await screen.findByText("Unpaywall and discovered open-access hosts data access");

    const categories = [
      await screen.findByRole("checkbox", { name: "Bibliographic metadata" }),
      screen.getByRole("checkbox", { name: "Cited Paper location" }),
      screen.getByRole("checkbox", { name: "Provider contact email" }),
    ];
    expect(categories.every((category) => category.getAttribute("aria-checked") === "false")).toBe(true);

    const continueButton = screen.getByRole("button", { name: "Continue" });
    expect(continueButton.hasAttribute("disabled")).toBe(true);
    fireEvent.click(continueButton);
    expect(requests.some(({ url, method }) => url === "/api/v1/analysis-runs" && method === "POST")).toBe(false);

    categories.forEach((category) => fireEvent.click(category));
    expect(continueButton.hasAttribute("disabled")).toBe(false);
    await clickContinue();
    await screen.findByRole("heading", { name: "Select your PDF" });
    const fileInput = document.querySelector<HTMLInputElement>('input[name="file"]');
    fireEvent.change(fileInput!, {
      target: { files: [new File(["pdf"], "source.pdf", { type: "application/pdf" })] },
    });
    fireEvent.click(screen.getByRole("button", { name: "Upload & start Analysis Run" }));
    await waitFor(() => expect(submittedConfiguration).not.toBeNull());
    expect(submittedConfiguration).toMatchObject({
      openAccessProvider: "unpaywall",
      externalProviderConsents: [{
        providerId: "unpaywall",
        dataCategories: ["bibliographic_metadata", "cited_paper_location", "provider_contact_email"],
        retentionDisclosureFingerprint: "a".repeat(64),
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
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    fireEvent.click(await screen.findByRole("button", { name: "Delete Source Document for source.pdf" }));

    expect(screen.getByRole("heading", { name: "Delete this Source Document?" })).toBeTruthy();
    expect(screen.getByText(/every Analysis Run created from it will be permanently removed/)).toBeTruthy();
    expect(screen.getByText(/Content already sent to external providers cannot be retracted by Paper T-Rail/)).toBeTruthy();
    expect(requests.some(({ method }) => method === "DELETE")).toBe(false);

    fireEvent.click(screen.getByRole("button", { name: "Permanently delete" }));
    expect(await screen.findByText("No Analysis Runs yet.")).toBeTruthy();
    expect(requests.some(({ url, method }) => url === "/api/v1/documents/document-1" && method === "DELETE")).toBe(true);
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
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    fireEvent.click(await screen.findByRole("button", { name: "Delete Source Document for source.pdf" }));
    fireEvent.click(screen.getByRole("button", { name: "Permanently delete" }));

    expect(await screen.findByText("Retry deletion to finish removing local data.")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Permanently delete" })).toBeTruthy();
  });

  it("polls the run list while any displayed run is active", async () => {
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
    await waitFor(() => expect(listRequests).toBe(2), { timeout: 5000 });
    expect(await screen.findByText("COMPLETED,FAILED")).toBeTruthy();
  }, 9000);

  it("keeps safe upload API errors visible in the final setup step", async () => {
    const existingRun = analysisRun("run-existing", "Existing run progress.");
    vi.stubGlobal("fetch", vi.fn(async (url: string, options: RequestInit = {}) => {
      const path = String(url);
      const method = options.method ?? "GET";
      if (path === "/api/v1/providers") return jsonResponse(providerDirectory);
      if (path.startsWith("/api/v1/analysis-runs?") && method === "GET") {
        return jsonResponse({ items: [existingRun], nextCursor: null });
      }
      if (path === "/api/v1/analysis-runs" && method === "POST") {
        return jsonResponse({ code: "PDF_LIMIT_EXCEEDED", message: "The PDF is over the configured upload limit." }, 413);
      }
      throw new Error(`Unexpected browser request: ${method} ${path}`);
    }));

    renderWorkspace();
    await continueToPdfStepWithLocalServices();

    const fileInput = document.querySelector<HTMLInputElement>('input[name="file"]');
    fireEvent.change(fileInput!, {
      target: { files: [new File(["pdf"], "source.pdf", { type: "application/pdf" })] },
    });
    fireEvent.click(screen.getByRole("button", { name: "Upload & start Analysis Run" }));
    expect(await screen.findByText("The PDF is over the configured upload limit.")).toBeTruthy();
  });
});
