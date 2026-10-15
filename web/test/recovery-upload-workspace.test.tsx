import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RecoveryUploadWorkspace } from "@/features/recovery-uploads/components/recovery-upload-workspace";
import type { AnalysisRun, ParsedDocument } from "@/features/analysis-runs/types";
import type { RecoveryBatch, RecoveryHistoryEvent, RecoveryRightsDeclaration, RecoveryUploadValidation } from "@/features/recovery-uploads/types";

const rights: RecoveryRightsDeclaration = {
  version: "recovery-rights-v1",
  text: "I have the right to stage this PDF locally.",
  submissionsEnabled: true,
  maxFileBytes: 52_428_800,
  maxFilesPerBatch: 10,
  maxBatchBytes: 262_144_000,
  uploadUrlTtlSeconds: 300,
  inactivityTtlSeconds: 604_800,
};

const bibliographyEntries: ParsedDocument["bibliographyEntries"] = [{
  entryOrder: 0,
  localReferenceKey: "b0",
  rawText: "Doe, Jane. A study of evidence.",
  title: "A study of evidence",
  authors: ["Jane Doe"],
  year: 2024,
  doi: "10.1234/example",
  referenceType: "JOURNAL_ARTICLE",
  resolutionStatus: "UNRESOLVED",
  sourceTextContent: null,
  sourceElement: null,
  sourceLocalReferenceKey: null,
  localReferenceKeyOrigin: "UNKNOWN",
  identifiers: [],
  sourceLocations: [],
  provisionalArtifactSignals: [],
  extractionLimitations: ["BIBLIOGRAPHY_PROVENANCE_UNAVAILABLE"],
  provenanceCaptureStatus: "UNAVAILABLE",
}];

function makeBatch(uploads: RecoveryBatch["uploads"] = [], overrides: Partial<RecoveryBatch> = {}): RecoveryBatch {
  return {
    id: "batch-1",
    analysisRunId: "run-1",
    status: "OPEN",
    rightsDeclarationVersion: rights.version,
    rightsDeclarationText: rights.text,
    rightsDeclaredAt: "2026-10-08T10:00:00Z",
    createdAt: "2026-10-08T10:00:00Z",
    lastActivityAt: "2026-10-08T10:00:00Z",
    expiresAt: "2026-10-15T10:00:00Z",
    revision: 0,
    uploads,
    selectedAssets: [],
    identifierAssistance: [],
    ...overrides,
  };
}

function stagedUpload(uploadId = "upload-1"): RecoveryBatch["uploads"][number] {
  return {
    id: uploadId,
    localReferenceKey: "b0",
    idempotencyKey: `upload-key-${uploadId}`,
    filename: "candidate.pdf",
    expectedSize: 1_024,
    expectedSha256: "a".repeat(64),
    status: "STAGED",
    failureCode: null,
    actualSize: 1_024,
    actualSha256: "b".repeat(64),
    createdAt: "2026-10-08T10:00:00Z",
    finalizedAt: "2026-10-08T10:01:00Z",
  };
}

function pendingUpload(uploadId: string): RecoveryBatch["uploads"][number] {
  return { ...stagedUpload(uploadId), status: "PENDING_UPLOAD", actualSize: null, actualSha256: null, finalizedAt: null };
}

function recoveryValidation(uploadId: string, identityOutcome: RecoveryUploadValidation["identityOutcome"], identityReasonCode: string | null): RecoveryUploadValidation {
  return {
    id: `validation-${uploadId}`,
    batchId: "batch-1",
    uploadId,
    analysisRunId: "run-1",
    contentSha256: "b".repeat(64),
    parserId: "local",
    parserVersion: "1",
    metadataExtractionPolicyVersion: "metadata-v1",
    identityPolicyVersion: "recovery-upload-identity-v2",
    parserOptions: {},
    languageDetectorId: "local",
    languageDetectorVersion: "1",
    minimumLanguageConfidence: 0.65,
    validationStatus: "COMPLETED",
    identityOutcome,
    identityReasonCode,
    humanConfirmation: null,
    selection: null,
    metadataCandidates: [],
    languageEligibility: "ELIGIBLE",
    detectedLanguage: "en",
    languageConfidence: 0.99,
    languageReasonCode: "ENGLISH",
    failureCode: null,
    createdAt: "2026-10-08T10:02:00Z",
  };
}

const localProviderDirectory = {
  providers: {
    claimExtractor: [{ role: "claimExtractor", providerId: "heuristic", displayName: "Local claims", version: "1", model: null, trustBoundary: "LOCAL", dataCategories: [], retentionDisclosure: null, retentionDisclosureFingerprint: null }],
    embedding: [{ role: "embedding", providerId: "local", displayName: "Local embeddings", version: "1", model: null, trustBoundary: "LOCAL", dataCategories: [], retentionDisclosure: null, retentionDisclosureFingerprint: null }],
    systemOne: [{ role: "systemOne", providerId: "mock", displayName: "Local review", version: "1", model: null, trustBoundary: "LOCAL", dataCategories: [], retentionDisclosure: null, retentionDisclosureFingerprint: null }],
    scholarlyMetadata: [{ role: "scholarlyMetadata", providerId: "recorded-fixtures", displayName: "Recorded metadata", version: "1", model: null, trustBoundary: "LOCAL", dataCategories: [], retentionDisclosure: null, retentionDisclosureFingerprint: null }],
    openAccess: [{ role: "openAccess", providerId: "recorded-fixtures", displayName: "Recorded access", version: "1", model: null, trustBoundary: "LOCAL", dataCategories: [], retentionDisclosure: null, retentionDisclosureFingerprint: null }],
  },
  dataCategories: [],
};

const predecessorConfiguration = {
  captureExecution: true,
  claimExtractor: { provider: "heuristic", version: "1" },
  embedding: { provider: "local", version: "1" },
  retrieval: {
    profileId: "local",
    vectorCandidateLimit: 5,
    lexicalCandidateLimit: 5,
    finalCandidateLimit: 5,
    reciprocalRankFusionConstant: 60,
    embeddingProfileHash: "a".repeat(64),
  },
  systemOne: { provider: "mock", version: "1" },
  sourceParser: { provider: "grobid", version: "1" },
  languageDetector: { provider: "local", version: "1" },
  referenceResolution: { executionStatus: "NOT_RUN", provider: { provider: "recorded-fixtures", version: "1" }, scorePolicyVersion: null, confidenceThreshold: null },
  openAccess: { provider: "recorded-fixtures", version: "1" },
  validationLimits: { minimumLanguageConfidence: 0.65, maxClaimCitationPairs: 10 },
  externalProviderConsents: [],
} as AnalysisRun["configuration"];

function renderWorkspace(
  entries: ParsedDocument["bibliographyEntries"] | null = bibliographyEntries,
  entriesError: string | null = null,
  predecessorRunStatus: AnalysisRun["status"] = "QUEUED",
  captureExecution: boolean | undefined = true,
  queryClient: QueryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
) {
  return render(
    <QueryClientProvider client={queryClient}>
      <RecoveryUploadWorkspace
        analysisRunId="run-1"
        predecessorConfiguration={{ ...predecessorConfiguration, captureExecution }}
        predecessorRunStatus={predecessorRunStatus}
        entries={entries}
        entriesLoading={false}
        entriesError={entriesError}
        enabled
      />
    </QueryClientProvider>,
  );
}

afterEach(() => vi.unstubAllGlobals());

describe("Recovery Upload workspace", () => {
  it("creates an identifier-only batch without a PDF-rights declaration", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    let activeBatch: RecoveryBatch | null = null;
    vi.stubGlobal("crypto", { ...globalThis.crypto, randomUUID: () => "batch-key-1" });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches" && options?.method === "POST") {
        activeBatch = makeBatch([], { rightsDeclarationVersion: null, rightsDeclarationText: null, rightsDeclaredAt: null });
        return Response.json(activeBatch);
      }
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json(activeBatch ? [activeBatch] : []);
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();
    const createButton = await screen.findByRole("button", { name: "Create Recovery Batch" });
    expect(createButton.hasAttribute("disabled")).toBe(false);
    expect(screen.queryByRole("checkbox", { name: /accept this declaration/i })).toBeNull();
    fireEvent.click(createButton);

    await screen.findByText("Current PDF-rights declaration · recovery-rights-v1");
    const createRequest = requests.find(({ options }) => options?.method === "POST");
    expect(createRequest?.url).toBe("/api/v1/analysis-runs/run-1/recovery-batches");
    expect(JSON.parse(String(createRequest?.options?.body))).toEqual({ idempotencyKey: "batch-key-1" });
    expect(screen.getByText(/DOI-only identity assistance does not require PDF rights/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Choose another PDF version/ })).toBeNull();
    expect(screen.getByText("No selected recovery work")).toBeTruthy();
    expect(screen.getByRole("button", { name: "Preview successor plan" }).hasAttribute("disabled")).toBe(true);
  });

  it("fails closed when submissions are disabled and no Recovery Batch exists", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json({ ...rights, submissionsEnabled: false });
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([]);
      if (url.includes("/recovery-history")) return Response.json({ analysisRunId: "run-1", events: [], nextCursor: null });
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();

    expect(await screen.findByText("New recovery submissions are disabled")).toBeTruthy();
    const createButton = await screen.findByRole("button", { name: "Create Recovery Batch" });
    expect(createButton.hasAttribute("disabled")).toBe(true);
    const history = await screen.findByRole("region", { name: "Recovery and work history" });
    expect(await within(history).findByText("No recovery workflow events are recorded for this predecessor run yet.")).toBeTruthy();
    expect(within(history).getByRole("link", { name: "Open predecessor Analysis Run report run-1" })).toBeTruthy();
    expect(requests.some(({ options }) => options?.method === "POST")).toBe(false);
  });

  it("keeps a missing capability disabled after a later true declaration refetch", async () => {
    let rightsReads = 0;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/v1/recovery-rights-declaration") {
        rightsReads += 1;
        return Response.json({ ...rights, submissionsEnabled: rightsReads === 1 ? undefined : true });
      }
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([]);
      if (url.includes("/recovery-history")) return Response.json({ analysisRunId: "run-1", events: [], nextCursor: null });
      throw new Error(`Unexpected request: ${url}`);
    }));

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <RecoveryUploadWorkspace
          analysisRunId="run-1"
          predecessorConfiguration={predecessorConfiguration}
          predecessorRunStatus="QUEUED"
          entries={bibliographyEntries}
          entriesLoading={false}
          entriesError={null}
          enabled
        />
      </QueryClientProvider>,
    );

    expect(await screen.findByText("New recovery submissions are disabled")).toBeTruthy();
    const createButton = await screen.findByRole("button", { name: "Create Recovery Batch" });
    expect(createButton.hasAttribute("disabled")).toBe(true);
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: ["recovery-uploads", "rights-declaration"] });
    });

    await waitFor(() => expect(rightsReads).toBe(2));
    await waitFor(() => expect(createButton.hasAttribute("disabled")).toBe(true));
    expect(await screen.findByText("New recovery submissions are disabled")).toBeTruthy();
    expect(await screen.findByRole("region", { name: "Recovery and work history" })).toBeTruthy();
  });

  it("keeps an existing batch inspectable and removals and read-only preview available while submissions are disabled", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    const activeBatch = makeBatch([stagedUpload(), stagedUpload("upload-2"), pendingUpload("upload-3")], {
      rightsDeclarationVersion: null,
      rightsDeclarationText: null,
      rightsDeclaredAt: null,
      identifierAssistance: [{
        bibliographyEntryId: "entry-1",
        localReferenceKey: "b0",
        identifierType: "DOI",
        identifierValue: "10.5678/researcher-asserted",
        recordedAt: "2026-10-09T00:00:00Z",
      }],
    });
    const previousSubmission: RecoveryHistoryEvent = {
      id: "history-previous-submit",
      batchId: "older-batch",
      batchStatus: "SUBMITTED",
      type: "SUCCESSOR_SUBMITTED",
      outcomeCode: "SUCCESSOR_CREATED",
      stageCode: null,
      attemptNumber: null,
      bibliographyEntryId: null,
      uploadId: null,
      previousUploadId: null,
      submission: { successorAnalysisRunId: "previous-successor", status: "PROCESSING", selectedEntryCount: 1, identifierAssistanceEntryCount: 0, resolutionQueuedEntryCount: 1 },
      createdAt: "2026-10-09T00:00:00Z",
    };
    const previousSuccessorRun = {
      id: "previous-successor",
      documentId: "document-1",
      filename: "source.pdf",
      sourceContentSha256: "b".repeat(64),
      status: "QUEUED",
      progress: {},
      pipeline: null,
      configuration: predecessorConfiguration,
      createdAt: "2026-10-09T00:00:00Z",
      startedAt: null,
      failureReason: null,
    };
    const preview = {
      batchId: "batch-1",
      predecessorAnalysisRunId: "run-1",
      revision: 0,
      planFingerprint: "plan-fingerprint",
      eligible: true,
      selectedEntries: [],
      identifierAssistanceEntries: [{
        predecessorBibliographyEntryId: "entry-1",
        localReferenceKey: "b0",
        identifierType: "DOI",
        identifierValue: "10.5678/researcher-asserted",
        stages: [],
        blockers: [],
      }],
      retainedReferenceKeys: [],
      retainedDecision: { stage: "EVIDENCE_RETRIEVAL", disposition: "RETAIN", reasonCode: "UNCHANGED" },
      invalidationReasons: [],
      blockers: [],
      expectedProviderWork: [],
      requiredConsents: [],
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json({ ...rights, submissionsEnabled: false });
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([activeBatch]);
      if (url === "/api/v1/providers") return Response.json(localProviderDirectory);
      if (url.includes("/recovery-history")) return Response.json({ analysisRunId: "run-1", events: [previousSubmission], nextCursor: null });
      if (url.endsWith("/uploads/upload-1/validation")) return Response.json(recoveryValidation("upload-1", "VALIDATED", "DOI_MATCH"));
      if (url.endsWith("/uploads/upload-2/validation")) return Response.json(recoveryValidation("upload-2", "NEEDS_CONFIRMATION", "DOI_NOT_EXTRACTED"));
      if (url === "/api/v1/analysis-runs/previous-successor") return Response.json(previousSuccessorRun);
      if (url.endsWith("/successor/preview") && options?.method === "POST") return Response.json(preview);
      if (url.endsWith("/uploads/upload-1") && options?.method === "DELETE") return Response.json({ ...stagedUpload(), status: "REMOVED" });
      if (url.endsWith("/entries/b0/identifier-assistance") && options?.method === "DELETE") return new Response(null, { status: 204 });
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();

    expect(await screen.findByText("New recovery submissions are disabled")).toBeTruthy();
    expect((await screen.findAllByText(/candidate\.pdf/)).length).toBe(2);
    expect(screen.getByText("10.5678/researcher-asserted")).toBeTruthy();
    const rightsCheckbox = await screen.findByRole("checkbox", { name: "I have read and accept the current PDF-rights declaration" });
    expect(rightsCheckbox.getAttribute("aria-disabled")).toBe("true");
    expect(screen.getByRole("button", { name: "Accept current declaration for PDF upload" }).hasAttribute("disabled")).toBe(true);
    expect(screen.getByRole("button", { name: "Replace DOI identity assertion" }).hasAttribute("disabled")).toBe(true);
    expect(screen.getByLabelText("DOI you assert identifies this Bibliography Entry").hasAttribute("disabled")).toBe(true);
    expect(screen.getByRole("button", { name: "I confirm this exact PDF version" }).hasAttribute("disabled")).toBe(true);
    expect(screen.getByRole("button", { name: "Select this exact version" }).hasAttribute("disabled")).toBe(true);
    expect(screen.getAllByRole("button", { name: "Validation saved" }).every((button) => button.hasAttribute("disabled"))).toBe(true);
    expect(screen.getByRole("button", { name: "Verify uploaded PDF" }).hasAttribute("disabled")).toBe(true);
    expect(screen.queryByRole("button", { name: "Choose another PDF version" })).toBeNull();

    const history = await screen.findByRole("region", { name: "Recovery and work history" });
    expect(await within(history).findByText("successor submitted")).toBeTruthy();
    expect(await within(history).findByRole("link", { name: /Open successor Analysis Run report · previous-successor/ })).toBeTruthy();
    expect(within(history).getByRole("link", { name: "Open predecessor Analysis Run report run-1" })).toBeTruthy();

    const previewButton = await screen.findByRole("button", { name: "Preview successor plan" });
    await waitFor(() => expect(previewButton.hasAttribute("disabled")).toBe(false));
    fireEvent.click(previewButton);
    expect(await screen.findByRole("heading", { name: "Successor plan preview" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Submit one immutable successor Analysis Run" }).hasAttribute("disabled")).toBe(true);

    const uploadRemovalButtons = screen.getAllByRole("button", { name: "Remove upload" });
    expect(uploadRemovalButtons[0].hasAttribute("disabled")).toBe(false);
    const removeIdentifier = screen.getByRole("button", { name: "Remove DOI assistance" });
    expect(removeIdentifier.hasAttribute("disabled")).toBe(false);
    fireEvent.click(uploadRemovalButtons[0]);
    fireEvent.click(removeIdentifier);
    await waitFor(() => {
      expect(requests.some(({ url, options }) => url.endsWith("/uploads/upload-1") && options?.method === "DELETE")).toBe(true);
      expect(requests.some(({ url, options }) => url.endsWith("/entries/b0/identifier-assistance") && options?.method === "DELETE")).toBe(true);
    });
    expect(requests.filter(({ options }) => options?.method === "POST").map(({ url }) => url)).toEqual([
      "/api/v1/recovery-batches/batch-1/successor/preview",
    ]);
  });

  it("fails closed and surfaces RECOVERY_SUBMISSIONS_DISABLED after a capability race", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    let rightsReads = 0;
    vi.stubGlobal("crypto", { ...globalThis.crypto, randomUUID: () => "batch-key-race" });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") {
        rightsReads += 1;
        return Response.json({ ...rights, submissionsEnabled: true });
      }
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches" && options?.method === "POST") {
        return Response.json({ code: "RECOVERY_SUBMISSIONS_DISABLED", message: "New recovery submissions are disabled." }, { status: 503 });
      }
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([]);
      if (url.includes("/recovery-history")) return Response.json({ analysisRunId: "run-1", events: [], nextCursor: null });
      throw new Error(`Unexpected request: ${url}`);
    }));

    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderWorkspace(bibliographyEntries, null, "QUEUED", true, queryClient);
    const createButton = await screen.findByRole("button", { name: "Create Recovery Batch" });
    await waitFor(() => expect(createButton.hasAttribute("disabled")).toBe(false));
    fireEvent.click(createButton);

    expect(await screen.findByText(/RECOVERY_SUBMISSIONS_DISABLED/)).toBeTruthy();
    expect(await screen.findByText("New recovery submissions are disabled")).toBeTruthy();
    await waitFor(() => expect(rightsReads).toBeGreaterThanOrEqual(2));
    await waitFor(() => expect(queryClient.isFetching({ queryKey: ["recovery-uploads", "rights-declaration"] })).toBe(0));
    await waitFor(() => expect(createButton.hasAttribute("disabled")).toBe(true));
    expect(requests.some(({ options }) => options?.method === "POST")).toBe(true);
    expect(screen.queryByText("Current PDF-rights declaration · recovery-rights-v1")).toBeNull();
  });

  it("requires explicit acceptance of the current PDF-rights declaration before upload controls appear", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    let activeBatch = makeBatch([], { rightsDeclarationVersion: null, rightsDeclarationText: null, rightsDeclaredAt: null });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([activeBatch]);
      if (url === "/api/v1/recovery-batches/batch-1/rights-declaration" && options?.method === "POST") {
        activeBatch = makeBatch();
        return Response.json(activeBatch);
      }
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();
    expect(await screen.findByText(/Before any new PDF upload/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Choose another PDF version/ })).toBeNull();
    fireEvent.click(screen.getByRole("checkbox", { name: "I have read and accept the current PDF-rights declaration" }));
    fireEvent.click(screen.getByRole("button", { name: "Accept current declaration for PDF upload" }));

    expect(await screen.findByText(/Current PDF-rights declaration recovery-rights-v1 accepted/)).toBeTruthy();
    expect(screen.getByRole("button", { name: /Choose another PDF version/ })).toBeTruthy();
    const acceptance = requests.find(({ url, options }) => url.endsWith("/rights-declaration") && options?.method === "POST");
    expect(JSON.parse(String(acceptance?.options?.body))).toEqual({ rightsDeclarationVersion: rights.version, accepted: true });
    expect(requests.some(({ url }) => url.includes("/entries/b0/uploads"))).toBe(false);
  });

  it("requires fresh PDF-rights acceptance after the active Recovery Batch changes", async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    let activeBatch = makeBatch([], { rightsDeclarationVersion: null, rightsDeclarationText: null, rightsDeclaredAt: null });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([activeBatch]);
      if (url === "/api/v1/recovery-batches/batch-1/rights-declaration" && options?.method === "POST") {
        activeBatch = makeBatch();
        return Response.json(activeBatch);
      }
      if (url === "/api/v1/recovery-batches/batch-2/rights-declaration" && options?.method === "POST") {
        activeBatch = makeBatch([], { id: "batch-2", rightsDeclarationVersion: rights.version, rightsDeclarationText: rights.text, rightsDeclaredAt: "2026-10-09T10:00:00Z" });
        return Response.json(activeBatch);
      }
      if (url.includes("/recovery-history")) return Response.json({ analysisRunId: "run-1", events: [], nextCursor: null });
      if (url === "/api/v1/providers") return Response.json(localProviderDirectory);
      throw new Error(`Unexpected request: ${url}`);
    }));

    const view = render(
      <QueryClientProvider client={queryClient}>
        <RecoveryUploadWorkspace analysisRunId="run-1" predecessorConfiguration={predecessorConfiguration} predecessorRunStatus="QUEUED" entries={bibliographyEntries} entriesLoading={false} entriesError={null} enabled />
      </QueryClientProvider>,
    );
    const firstAcceptance = await screen.findByRole("checkbox", { name: "I have read and accept the current PDF-rights declaration" });
    fireEvent.click(firstAcceptance);
    fireEvent.click(screen.getByRole("button", { name: "Accept current declaration for PDF upload" }));
    expect(await screen.findByText(/Current PDF-rights declaration recovery-rights-v1 accepted/)).toBeTruthy();

    activeBatch = makeBatch([], { id: "batch-2", rightsDeclarationVersion: null, rightsDeclarationText: null, rightsDeclaredAt: null });
    await queryClient.invalidateQueries({ queryKey: ["recovery-uploads", "batch", "run-1"] });

    const nextBatchAcceptance = await screen.findByRole("checkbox", { name: "I have read and accept the current PDF-rights declaration" });
    const nextBatchAcceptButton = screen.getByRole("button", { name: "Accept current declaration for PDF upload" });
    await waitFor(() => {
      expect(nextBatchAcceptance.getAttribute("aria-checked")).toBe("false");
      expect(nextBatchAcceptButton.hasAttribute("disabled")).toBe(true);
    });
    expect(requests.some(({ url, options }) => url.endsWith("/batch-2/rights-declaration") && options?.method === "POST")).toBe(false);

    fireEvent.click(nextBatchAcceptance);
    expect(nextBatchAcceptButton.hasAttribute("disabled")).toBe(false);
    fireEvent.click(nextBatchAcceptButton);
    await waitFor(() => expect(requests.some(({ url, options }) => url.endsWith("/batch-2/rights-declaration") && options?.method === "POST")).toBe(true));
    view.unmount();
  });

  it("explains how to recover when an accepted rights declaration is no longer current", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    const activeBatch = makeBatch([], {
      rightsDeclarationVersion: "recovery-rights-v0",
      rightsDeclarationText: "An older PDF-rights declaration.",
      rightsDeclaredAt: "2026-10-07T10:00:00Z",
      identifierAssistance: [{ bibliographyEntryId: "entry-1", localReferenceKey: "b0", identifierType: "DOI", identifierValue: "10.5678/researcher-asserted", recordedAt: "2026-10-09T00:00:00Z" }],
    });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([activeBatch]);
      if (url === "/api/v1/providers") return Response.json(localProviderDirectory);
      if (url.includes("/recovery-history")) return Response.json({ analysisRunId: "run-1", events: [], nextCursor: null });
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();

    const rightsAlertTitle = await screen.findByText("PDF-rights declaration changed");
    const rightsAlertText = rightsAlertTitle.closest('[role="alert"]')?.textContent ?? "";
    expect(rightsAlertText).toContain("This batch recorded PDF-rights declaration recovery-rights-v0, which cannot be changed to recovery-rights-v1.");
    expect(rightsAlertText).toContain("let this batch expire or submit eligible existing work, then create a new batch");
    expect(screen.getByText("10.5678/researcher-asserted")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Accept current declaration for PDF upload" })).toBeNull();
    expect(screen.queryByRole("button", { name: /Choose another PDF version/ })).toBeNull();
    expect(requests.some(({ url, options }) => url.endsWith("/rights-declaration") && options?.method === "POST")).toBe(false);
  });

  it("stages a DOI-only researcher identity assertion without PDF rights or provider consent", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    let activeBatch = makeBatch([], { rightsDeclarationVersion: null, rightsDeclarationText: null, rightsDeclaredAt: null });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([activeBatch]);
      if (url.endsWith("/entries/b0/identifier-assistance") && options?.method === "PUT") {
        activeBatch = makeBatch([], {
          rightsDeclarationVersion: null,
          rightsDeclarationText: null,
          rightsDeclaredAt: null,
          revision: 1,
          identifierAssistance: [{ bibliographyEntryId: "entry-1", localReferenceKey: "b0", identifierType: "DOI", identifierValue: "10.5678/researcher-asserted", recordedAt: "2026-10-09T00:00:00Z" }],
        });
        return Response.json(activeBatch.identifierAssistance[0]);
      }
      if (url.includes("/recovery-history")) return Response.json({ analysisRunId: "run-1", events: [], nextCursor: null });
      if (url === "/api/v1/providers") return Response.json({ providers: {}, dataCategories: [] });
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();
    const doiInput = await screen.findByRole("textbox", { name: "DOI you assert identifies this Bibliography Entry" });
    const confirmIdentity = screen.getByRole("checkbox", { name: "I confirm this DOI identifies this Bibliography Entry" });
    fireEvent.change(doiInput, { target: { value: "10.5678/first-confirmed-value" } });
    fireEvent.click(confirmIdentity);
    expect(confirmIdentity.getAttribute("aria-checked")).toBe("true");
    fireEvent.change(doiInput, { target: { value: "10.5678/researcher-asserted" } });
    expect(confirmIdentity.getAttribute("aria-checked")).toBe("false");
    expect(screen.getByRole("button", { name: "Stage DOI-only assistance" }).hasAttribute("disabled")).toBe(true);
    expect(requests.some(({ url, options }) => url.endsWith("/identifier-assistance") && options?.method === "PUT")).toBe(false);
    fireEvent.click(confirmIdentity);
    fireEvent.click(screen.getByRole("button", { name: "Stage DOI-only assistance" }));

    expect(await screen.findByText("10.5678/researcher-asserted")).toBeTruthy();
    expect(screen.getByText(/not provider verification, exact PDF identity/)).toBeTruthy();
    expect(requests.some(({ url, options }) => url.endsWith("/identifier-assistance") && options?.method === "PUT")).toBe(true);
    const stageRequest = requests.find(({ url, options }) => url.endsWith("/identifier-assistance") && options?.method === "PUT");
    expect(JSON.parse(String(stageRequest?.options?.body))).toEqual({
      identifierType: "DOI",
      identifierValue: "10.5678/researcher-asserted",
      confirmsBibliographyIdentity: true,
    });
    expect(requests.some(({ url, options }) => url.endsWith("/rights-declaration") && options?.method === "POST")).toBe(false);
  });

  it("links to the predecessor execution view only when its persisted trace is available", async () => {
    const requests: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      requests.push(url);
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([makeBatch()]);
      if (url === "/api/v1/providers") return Response.json(localProviderDirectory);
      if (url.includes("/recovery-history")) return Response.json({ analysisRunId: "run-1", events: [], nextCursor: null });
      if (url === "/api/v1/analysis-runs/run-1/execution") return Response.json({
        analysisRunId: "run-1", captureRequested: true, captureEnabled: true, recordingState: "STOPPED", completeness: "COMPLETE",
        startedAt: "2026-10-09T00:00:00Z", finishedAt: "2026-10-09T00:01:00Z", totalDurationMillis: 60_000, gapReason: null,
      });
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace(bibliographyEntries, null, "COMPLETED");
    const executionLink = await screen.findByRole("link", { name: "Open predecessor execution trace" });
    expect(executionLink.getAttribute("href")).toBe("/analysis-runs/run-1?view=execution");
    expect(requests).toContain("/api/v1/analysis-runs/run-1/execution");
  });

  it("explains that no predecessor trace is available when capture was disabled", async () => {
    const requests: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      requests.push(url);
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([makeBatch()]);
      if (url === "/api/v1/providers") return Response.json(localProviderDirectory);
      if (url.includes("/recovery-history")) return Response.json({ analysisRunId: "run-1", events: [], nextCursor: null });
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace(bibliographyEntries, null, "COMPLETED", false);
    expect(await screen.findByText("Execution capture was disabled for this Analysis Run; no predecessor trace link is available.")).toBeTruthy();
    expect(screen.queryByRole("link", { name: "Open predecessor execution trace" })).toBeNull();
    expect(requests.some((url) => url.endsWith("/execution"))).toBe(false);
  });

  it("does not link an unrecorded or removed predecessor execution trace", async () => {
    const requests: string[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      requests.push(url);
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([makeBatch()]);
      if (url === "/api/v1/providers") return Response.json(localProviderDirectory);
      if (url.includes("/recovery-history")) return Response.json({ analysisRunId: "run-1", events: [], nextCursor: null });
      if (url === "/api/v1/analysis-runs/run-1/execution") return Response.json({
        analysisRunId: "run-1", captureRequested: true, captureEnabled: true, recordingState: "NOT_RECORDED", completeness: "NOT_RECORDED",
        startedAt: null, finishedAt: null, totalDurationMillis: null, gapReason: null,
      });
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace(bibliographyEntries, null, "COMPLETED");
    expect(await screen.findByText("The predecessor execution trace was not recorded or has since been removed; no trace link is available.")).toBeTruthy();
    expect(screen.queryByRole("link", { name: "Open predecessor execution trace" })).toBeNull();
    expect(requests).toContain("/api/v1/analysis-runs/run-1/execution");
  });

  it("announces recovery-history loading and preserves a history API error", async () => {
    let resolveHistory!: (response: Response) => void;
    const historyResponse = new Promise<Response>((resolve) => { resolveHistory = resolve; });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([makeBatch()]);
      if (url === "/api/v1/providers") return Response.json(localProviderDirectory);
      if (url.includes("/recovery-history")) return historyResponse;
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();
    expect(await screen.findByText("Loading recovery and work history…")).toBeTruthy();
    resolveHistory(Response.json({ code: "UNAVAILABLE", message: "History service unavailable." }, { status: 503 }));
    expect(await screen.findByText("Recovery history unavailable")).toBeTruthy();
    expect(screen.getByText("History service unavailable.")).toBeTruthy();
  });

  it("loads older successor history only after the researcher requests it", async () => {
    const historyRequests: string[] = [];
    const submittedBatch = makeBatch([], { status: "SUBMITTED" });
    const recentEvent: RecoveryHistoryEvent = {
      id: "history-recent", batchId: "batch-1", batchStatus: "SUBMITTED", type: "ATTEMPT_STARTED", outcomeCode: "STARTED",
      stageCode: "REFERENCE_RESOLUTION", attemptNumber: 1, bibliographyEntryId: "entry-1", uploadId: null, previousUploadId: null, submission: null,
      createdAt: "2026-10-09T00:03:00Z",
    };
    const submissionEvent: RecoveryHistoryEvent = {
      id: "history-submit", batchId: "batch-1", batchStatus: "SUBMITTED", type: "SUCCESSOR_SUBMITTED", outcomeCode: "SUCCESSOR_CREATED",
      stageCode: null, attemptNumber: null, bibliographyEntryId: null, uploadId: null, previousUploadId: null,
      submission: { successorAnalysisRunId: "successor-1", status: "PROCESSING", selectedEntryCount: 0, identifierAssistanceEntryCount: 1, resolutionQueuedEntryCount: 1 },
      createdAt: "2026-10-09T00:02:00Z",
    };
    const successorRun = { id: "successor-1", documentId: "document-1", filename: "source.pdf", sourceContentSha256: "b".repeat(64), status: "QUEUED", progress: {}, pipeline: null, configuration: {}, createdAt: "2026-10-09T00:02:00Z", startedAt: null, failureReason: null };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([submittedBatch]);
      if (url === "/api/v1/providers") return Response.json(localProviderDirectory);
      if (url.includes("/recovery-history")) {
        historyRequests.push(url);
        return historyRequests.length === 1
          ? Response.json({ analysisRunId: "run-1", events: [recentEvent], nextCursor: "older-history" })
          : Response.json({ analysisRunId: "run-1", events: [submissionEvent], nextCursor: null });
      }
      if (url === "/api/v1/analysis-runs/successor-1") return Response.json(successorRun);
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();
    expect(await screen.findByText("attempt started")).toBeTruthy();
    expect(screen.getByText(/not in this history page/)).toBeTruthy();
    expect(historyRequests).toHaveLength(1);
    fireEvent.click(screen.getByRole("button", { name: "Load older history to find successor run" }));

    expect(await screen.findByRole("link", { name: /Open successor Analysis Run report · successor-1/ })).toBeTruthy();
    expect(historyRequests).toHaveLength(2);
    expect(historyRequests[1]).toContain("cursor=older-history");
  });

  it("keeps the submitted successor link and history visible when no batch remains active", async () => {
    const submissionEvent: RecoveryHistoryEvent = {
      id: "history-submit-after-active-batch",
      batchId: "batch-submitted",
      batchStatus: "SUBMITTED",
      type: "SUCCESSOR_SUBMITTED",
      outcomeCode: "SUCCESSOR_CREATED",
      stageCode: null,
      attemptNumber: null,
      bibliographyEntryId: null,
      uploadId: null,
      previousUploadId: null,
      submission: {
        successorAnalysisRunId: "successor-after-submit",
        status: "PROCESSING",
        selectedEntryCount: 0,
        identifierAssistanceEntryCount: 1,
        resolutionQueuedEntryCount: 1,
      },
      createdAt: "2026-10-09T00:02:00Z",
    };
    const successorRun = {
      id: "successor-after-submit",
      documentId: "document-1",
      filename: "source.pdf",
      sourceContentSha256: "b".repeat(64),
      status: "QUEUED",
      progress: {},
      pipeline: null,
      configuration: predecessorConfiguration,
      createdAt: "2026-10-09T00:02:00Z",
      startedAt: null,
      failureReason: null,
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([]);
      if (url === "/api/v1/providers") return Response.json(localProviderDirectory);
      if (url.includes("/recovery-history")) return Response.json({ analysisRunId: "run-1", events: [submissionEvent], nextCursor: null });
      if (url === "/api/v1/analysis-runs/successor-after-submit") return Response.json(successorRun);
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();

    const history = await screen.findByRole("region", { name: "Recovery and work history" });
    expect(await within(history).findByText("successor submitted")).toBeTruthy();
    expect(await within(history).findByRole("link", { name: /Open successor Analysis Run report · successor-after-submit/ })).toBeTruthy();
    expect(screen.getByRole("button", { name: "Create Recovery Batch" })).toBeTruthy();
    expect(screen.getByRole("link", { name: "Open predecessor Analysis Run report run-1" }).getAttribute("href")).toBe("/analysis-runs/run-1?view=pipeline");
    expect(screen.queryByText("Review successor plan and provider consent")).toBeNull();
  });

  it("keeps a new batch eligible after a different batch already submitted a successor", async () => {
    const previousSubmissionEvent: RecoveryHistoryEvent = {
      id: "history-previous-batch-submit",
      batchId: "batch-previous",
      batchStatus: "SUBMITTED",
      type: "SUCCESSOR_SUBMITTED",
      outcomeCode: "SUCCESSOR_CREATED",
      stageCode: null,
      attemptNumber: null,
      bibliographyEntryId: null,
      uploadId: null,
      previousUploadId: null,
      submission: {
        successorAnalysisRunId: "successor-from-previous-batch",
        status: "PROCESSING",
        selectedEntryCount: 0,
        identifierAssistanceEntryCount: 1,
        resolutionQueuedEntryCount: 1,
      },
      createdAt: "2026-10-08T00:02:00Z",
    };
    const previousSuccessorRun = {
      id: "successor-from-previous-batch",
      documentId: "document-1",
      filename: "source.pdf",
      sourceContentSha256: "b".repeat(64),
      status: "QUEUED",
      progress: {},
      pipeline: null,
      configuration: { ...predecessorConfiguration, captureExecution: false },
      createdAt: "2026-10-08T00:02:00Z",
      startedAt: null,
      failureReason: null,
    };
    const currentBatch = makeBatch([], {
      id: "batch-current",
      revision: 3,
      identifierAssistance: [{ bibliographyEntryId: "entry-2", localReferenceKey: "b0", identifierType: "DOI", identifierValue: "10.5678/current-batch", recordedAt: "2026-10-09T00:00:00Z" }],
    });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([currentBatch]);
      if (url === "/api/v1/providers") return Response.json(localProviderDirectory);
      if (url.includes("/recovery-history")) return Response.json({ analysisRunId: "run-1", events: [previousSubmissionEvent], nextCursor: null });
      if (url === "/api/v1/analysis-runs/successor-from-previous-batch") return Response.json(previousSuccessorRun);
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();

    const history = await screen.findByRole("region", { name: "Recovery and work history" });
    expect(await within(history).findByRole("link", { name: /Open successor Analysis Run report · successor-from-previous-batch/ })).toBeTruthy();
    await waitFor(() => expect(screen.getByRole("button", { name: "Preview successor plan" }).hasAttribute("disabled")).toBe(false));
  });

  it("requires successor-specific provider consent, previews DOI-only work, and navigates by stable run IDs", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    const disclosureFingerprint = "d".repeat(64);
    const crossrefFingerprint = "c".repeat(64);
    const dataCiteFingerprint = "e".repeat(64);
    const externalDirectory = {
      providers: {
        ...localProviderDirectory.providers,
        systemOne: [{ role: "systemOne", providerId: "evidence-cloud", displayName: "Evidence Cloud", version: "2", model: null, trustBoundary: "EXTERNAL", dataCategories: ["claim-text"], retentionDisclosure: "Provider retains request content for 14 days.", retentionDisclosureFingerprint: disclosureFingerprint }],
      },
      providerChains: [{
        chainId: "crossref-datacite-exact-doi-v1",
        displayName: "Crossref, then DataCite (exact DOI only)",
        description: "Bounded fallback for the same DOI.",
        providers: [
          { role: "scholarlyMetadata", providerId: "crossref", displayName: "Crossref", version: "v1", model: null, trustBoundary: "EXTERNAL", dataCategories: ["bibliographic_metadata"], retentionDisclosure: "Crossref metadata disclosure.", retentionDisclosureFingerprint: crossrefFingerprint },
          { role: "scholarlyMetadata", providerId: "datacite", displayName: "DataCite", version: "v1", model: null, trustBoundary: "EXTERNAL", dataCategories: ["bibliographic_metadata"], retentionDisclosure: "DataCite metadata disclosure.", retentionDisclosureFingerprint: dataCiteFingerprint },
        ],
      }],
      dataCategories: [
        { id: "claim-text", label: "Claim text", description: "Claim and citation-context content used for evidence review." },
        { id: "bibliographic_metadata", label: "Bibliographic metadata", description: "Citation fields used for exact DOI lookup." },
      ],
    };
    const externalPredecessorConfiguration = {
      ...predecessorConfiguration,
      captureExecution: false,
      systemOne: { provider: "evidence-cloud", version: "2" },
      referenceResolution: {
        executionStatus: "COMPLETED",
        provider: null,
        providerChain: {
          chainId: "crossref-datacite-exact-doi-v1",
          providers: [{ provider: "crossref" }, { provider: "datacite" }],
        },
        scorePolicyVersion: null,
        confidenceThreshold: null,
      },
    } as AnalysisRun["configuration"];
    const activeAssistance = { bibliographyEntryId: "entry-1", localReferenceKey: "b0", identifierType: "DOI" as const, identifierValue: "10.1234/private-doi", recordedAt: "2026-10-09T00:00:00Z" };
    let activeBatch = makeBatch([], { revision: 5, identifierAssistance: [activeAssistance] });
    const historyEvents: RecoveryHistoryEvent[] = [{
      id: "history-attempt-1", batchId: "batch-1", batchStatus: "OPEN", type: "ATTEMPT_STARTED", outcomeCode: "STARTED",
      stageCode: "REFERENCE_RESOLUTION", attemptNumber: 1, bibliographyEntryId: "entry-1", uploadId: null, previousUploadId: null, submission: null, createdAt: "2026-10-09T00:01:00Z",
    }];
    let previewBody: Record<string, unknown> | null = null;
    let submissionBody: Record<string, unknown> | null = null;
    const previewPlan = {
      batchId: "batch-1", predecessorAnalysisRunId: "run-1", revision: 5, planFingerprint: "a".repeat(64), eligible: true,
      selectedEntries: [],
      identifierAssistanceEntries: [{ predecessorBibliographyEntryId: "entry-1", localReferenceKey: "b0", identifierType: "DOI", identifierValue: "10.1234/private-doi", stages: [{ stage: "reference-resolution", disposition: "REPROCESS", reasonCode: "DOI_ASSISTANCE" }], blockers: [] }],
      retainedReferenceKeys: ["b1"], retainedDecision: { stage: "evidence", disposition: "RETAIN", reasonCode: "UNCHANGED" },
      invalidationReasons: [], blockers: [],
      expectedProviderWork: [{ stage: "reference-resolution", providerId: "evidence-cloud", trustBoundary: "EXTERNAL", dataCategories: ["claim-text"], retentionDisclosure: "Provider retains request content for 14 days." }],
      requiredConsents: [{ providerId: "evidence-cloud", dataCategories: ["claim-text"], retentionDisclosure: "Provider retains request content for 14 days." }],
    };
    const submission = {
      submissionId: "submission-1", successorAnalysisRunId: "successor-1", predecessorAnalysisRunId: "run-1", revision: 5,
      planFingerprint: "a".repeat(64), selectedEntryCount: 0, identifierAssistanceEntryCount: 1, resolutionQueuedEntryCount: 1,
      retainedEntryCount: 1, queuedEntryCount: 0, replayed: false,
    };
    const successorRun = {
      id: "successor-1", documentId: "document-1", filename: "source.pdf", sourceContentSha256: "b".repeat(64), status: "QUEUED",
      progress: {}, pipeline: null, configuration: {}, createdAt: "2026-10-09T00:02:00Z", startedAt: null, failureReason: null,
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([activeBatch]);
      if (url === "/api/v1/providers") return Response.json(externalDirectory);
      if (url.includes("/recovery-history")) return Response.json({ analysisRunId: "run-1", events: historyEvents, nextCursor: null });
      if (url.endsWith("/successor/preview") && options?.method === "POST") {
        previewBody = JSON.parse(String(options.body));
        return Response.json(previewPlan);
      }
      if (url.endsWith("/successor") && options?.method === "POST") {
        submissionBody = JSON.parse(String(options.body));
        activeBatch = { ...activeBatch, status: "SUBMITTED" };
        return Response.json(submission);
      }
      if (url === "/api/v1/analysis-runs/successor-1") return Response.json(successorRun);
      if (url === "/api/v1/analysis-runs/successor-1/execution") return Response.json({ analysisRunId: "successor-1", captureRequested: false, captureEnabled: false, recordingState: "NOT_RECORDED", completeness: "NOT_RECORDED", startedAt: null, finishedAt: null, totalDurationMillis: null, gapReason: null });
      throw new Error(`Unexpected request: ${url}`);
    }));

    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <RecoveryUploadWorkspace analysisRunId="run-1" predecessorConfiguration={externalPredecessorConfiguration} predecessorRunStatus="QUEUED" entries={bibliographyEntries} entriesLoading={false} entriesError={null} enabled />
      </QueryClientProvider>,
    );

    expect(await screen.findByText("attempt started")).toBeTruthy();
    const history = screen.getByRole("region", { name: "Recovery and work history" });
    expect(within(history).queryByText("10.1234/private-doi")).toBeNull();
    expect(screen.getByRole("button", { name: "Preview successor plan" }).hasAttribute("disabled")).toBe(true);
    fireEvent.click(await screen.findByRole("checkbox", { name: "Claim text" }));
    for (const categoryConsent of screen.getAllByRole("checkbox", { name: "Bibliographic metadata" })) {
      fireEvent.click(categoryConsent);
    }
    fireEvent.click(screen.getByRole("button", { name: "Preview successor plan" }));

    expect(await screen.findByText("Successor plan preview")).toBeTruthy();
    expect(screen.getByText(/DOI-only reference-resolution work/)).toBeTruthy();
    expect(previewBody).toEqual({
      expectedRevision: 5,
      configuration: {
        captureExecution: false,
        claimExtractorProvider: "heuristic",
        embeddingProvider: "local",
        systemOneProvider: "evidence-cloud",
        scholarlyMetadataProvider: null,
        openAccessProvider: "recorded-fixtures",
        providerChainId: "crossref-datacite-exact-doi-v1",
        externalProviderConsents: [
          { providerId: "crossref", dataCategories: ["bibliographic_metadata"], retentionDisclosureFingerprint: crossrefFingerprint },
          { providerId: "datacite", dataCategories: ["bibliographic_metadata"], retentionDisclosureFingerprint: dataCiteFingerprint },
          { providerId: "evidence-cloud", dataCategories: ["claim-text"], retentionDisclosureFingerprint: disclosureFingerprint },
        ],
      },
    });
    fireEvent.click(screen.getByRole("button", { name: "Submit one immutable successor Analysis Run" }));

    expect(await screen.findByText(/Successor status: queued/)).toBeTruthy();
    expect(screen.getByText("Immutable successor · submitted")).toBeTruthy();
    expect(screen.queryByText("Immutable successor · PROCESSING")).toBeNull();
    const previewConfiguration = (previewBody as Record<string, unknown> | null)?.configuration;
    expect(submissionBody).toEqual({
      idempotencyKey: expect.any(String),
      expectedRevision: 5,
      configuration: previewConfiguration,
    });
    expect(screen.getByRole("link", { name: "successor-1" }).getAttribute("href")).toBe("/analysis-runs/successor-1");
    expect(screen.getByRole("link", { name: "Open predecessor Analysis Run report run-1" }).getAttribute("href")).toBe("/analysis-runs/run-1?view=pipeline");
    expect(screen.queryByRole("link", { name: "Open predecessor execution trace" })).toBeNull();
    expect(await screen.findByText(/Execution capture was disabled or not recorded/)).toBeTruthy();
    expect(screen.getByText(/attempt started/)).toBeTruthy();
    expect(requests.some(({ url, options }) => url.endsWith("/successor") && options?.method === "POST")).toBe(true);
  });

  it("keeps existing uploads inspectable and removable when policy and bibliography reads fail", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    let activeBatch = makeBatch([stagedUpload()]);
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json({ code: "UNAVAILABLE", message: "Policy unavailable." }, { status: 503 });
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([activeBatch]);
      if (url === "/api/v1/recovery-batches/batch-1/uploads/upload-1" && options?.method === "DELETE") {
        activeBatch = makeBatch([{ ...stagedUpload(), status: "REMOVED" }]);
        return Response.json(activeBatch.uploads[0]);
      }
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace(null, "Could not load Bibliography Entries.");
    await screen.findByText("Server-verified snapshot");
    expect(screen.getAllByText("Bibliography Entry · b0").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "Remove upload" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: /Choose another PDF version/ })).toBeNull();
    expect(screen.getByText(/New recovery submissions are disabled; existing candidates remain available to inspect/)).toBeTruthy();
    fireEvent.click(screen.getByText("View recorded PDF-rights declaration"));
    expect(screen.getByText(rights.text)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Remove upload" }));
    await waitFor(() => expect(requests.some(({ options }) => options?.method === "DELETE")).toBe(true));
    expect(await screen.findByText(/This upload is removed\./)).toBeTruthy();
  });

  it("shows multiple PDF versions together and replaces the one current exact selection", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    const secondUpload = { ...stagedUpload(), id: "upload-2", filename: "alternate-version.pdf", idempotencyKey: "upload-key-2", actualSha256: "c".repeat(64), expectedSha256: "c".repeat(64) };
    const firstSelection = {
      id: "selection-1", batchId: "batch-1", analysisRunId: "run-1", bibliographyEntryId: "entry-1", uploadId: "upload-1",
      validationAttemptId: "validation-1", contentSha256: "b".repeat(64), selectionMethod: "MACHINE_VALIDATED" as const, selectedAt: "2026-10-08T10:02:00Z",
    };
    const secondSelection = { ...firstSelection, id: "selection-2", uploadId: "upload-2", validationAttemptId: "validation-2", contentSha256: "c".repeat(64) };
    const validationFor = (uploadId: string, id: string, contentSha256: string, selection: typeof firstSelection | null) => ({
      id, batchId: "batch-1", uploadId, analysisRunId: "run-1", contentSha256, parserId: "docling", parserVersion: "1.30.0",
      metadataExtractionPolicyVersion: "docling-first-page-metadata-candidates-v2", identityPolicyVersion: "recovery-upload-identity-v2",
      parserOptions: {}, languageDetectorId: "optimaize", languageDetectorVersion: "0.6", minimumLanguageConfidence: 0.65,
      validationStatus: "COMPLETED", identityOutcome: "VALIDATED", identityReasonCode: "DOI_MATCH", humanConfirmation: null,
      selection, metadataCandidates: [], languageEligibility: "ELIGIBLE", detectedLanguage: "en", languageConfidence: 0.99,
      languageReasonCode: "ENGLISH_DETECTED", failureCode: null, createdAt: "2026-10-08T10:01:00Z",
    });
    const validationOne = validationFor("upload-1", "validation-1", "b".repeat(64), firstSelection);
    let validationTwo = validationFor("upload-2", "validation-2", "c".repeat(64), null);
    let activeBatch = makeBatch([stagedUpload(), secondUpload], { revision: 2, selectedAssets: [firstSelection] });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([activeBatch]);
      if (url.endsWith("/uploads/upload-1/validation")) return Response.json(validationOne);
      if (url.endsWith("/uploads/upload-2/validation")) return Response.json(validationTwo);
      if (url.endsWith("/uploads/upload-2/select") && options?.method === "POST") {
        activeBatch = makeBatch([stagedUpload(), secondUpload], { revision: 3, selectedAssets: [secondSelection] });
        validationTwo = validationFor("upload-2", "validation-2", "c".repeat(64), secondSelection);
        return Response.json(secondSelection);
      }
      if (url.includes("/recovery-history")) return Response.json({ analysisRunId: "run-1", events: [], nextCursor: null });
      if (url === "/api/v1/providers") return Response.json(localProviderDirectory);
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();
    expect(await screen.findByText(/candidate\.pdf · 1 KiB/)).toBeTruthy();
    expect(screen.getByText(/alternate-version\.pdf · 1 KiB/)).toBeTruthy();
    expect(screen.getAllByText("Current selected exact version")).toHaveLength(1);
    const selectAlternateVersion = await screen.findByRole("button", { name: "Select this exact version" });
    fireEvent.click(selectAlternateVersion);

    await waitFor(() => expect(activeBatch.selectedAssets.map(({ uploadId }) => uploadId)).toEqual(["upload-2"]));
    expect(activeBatch.uploads).toHaveLength(2);
    expect(requests.some(({ url, options }) => url.endsWith("/uploads/upload-2/select") && options?.method === "POST")).toBe(true);
    expect(screen.getAllByText("Current selected exact version")).toHaveLength(1);
  });

  it("explains that scanned PDFs are unsupported when server validation rejects one", async () => {
    const rejectedUpload = {
      ...stagedUpload(),
      status: "REJECTED" as const,
      failureCode: "PDF_NO_EXTRACTABLE_TEXT",
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([makeBatch([rejectedUpload])]);
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();

    expect(await screen.findByText(/Scanned PDFs are not supported/)).toBeTruthy();
    expect(screen.getByText(/no selectable text/)).toBeTruthy();
  });

  it("explains that encrypted PDFs are unsupported when server validation rejects one", async () => {
    const rejectedUpload = {
      ...stagedUpload(),
      status: "REJECTED" as const,
      failureCode: "PDF_ENCRYPTED",
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([makeBatch([rejectedUpload])]);
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();

    expect(await screen.findByText("Encrypted PDFs are not supported for Recovery Upload validation.")).toBeTruthy();
  });

  it("waits for saved validation state before allowing a new validation", async () => {
    let resolveValidation!: (response: Response) => void;
    const savedValidation = new Promise<Response>((resolve) => { resolveValidation = resolve; });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([makeBatch([stagedUpload()])]);
      if (url.endsWith("/uploads/upload-1/validation")) return savedValidation;
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();

    const validateButton = await screen.findByRole("button", { name: "Validate PDF metadata" });
    expect(validateButton.hasAttribute("disabled")).toBe(true);
    expect(await screen.findByText("Loading saved result…")).toBeTruthy();

    resolveValidation(new Response(null, { status: 204 }));

    await waitFor(() => expect(validateButton.hasAttribute("disabled")).toBe(false));
  });

  it("runs local validation and displays machine identity, language, metadata provenance, and parser options separately", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    let latestValidation: object | null = null;
    const validation = {
      id: "validation-1",
      batchId: "batch-1",
      uploadId: "upload-1",
      analysisRunId: "run-1",
      contentSha256: "b".repeat(64),
      parserId: "docling",
      parserVersion: "1.30.0",
      metadataExtractionPolicyVersion: "docling-first-page-metadata-candidates-v2",
      identityPolicyVersion: "recovery-upload-identity-v2",
      parserOptions: { from_formats: "pdf", to_formats: "md,json", do_ocr: "false" },
      languageDetectorId: "optimaize",
      languageDetectorVersion: "0.6",
      minimumLanguageConfidence: 0.65,
      validationStatus: "COMPLETED",
      identityOutcome: "NEEDS_CONFIRMATION",
      identityReasonCode: "REFERENCE_IDENTITY_UNRESOLVED",
      humanConfirmation: null,
      selection: null,
      metadataCandidates: [{
        field: "DOI",
        value: "10.1234/alternate",
        pageNumber: 1,
        sourceLabel: "doi",
        extractionMethod: "EXPLICIT_DOI_PREFIX",
        sourceElementId: "docling-doi-1",
        sourceCharSpanStart: 110,
        sourceCharSpanEnd: 130,
        candidateFingerprint: `recovery-metadata-candidate-v1:${"c".repeat(64)}`,
        normalizedDoi: "10.1234/alternate",
      }],
      languageEligibility: "ELIGIBLE",
      detectedLanguage: "en",
      languageConfidence: 0.99,
      languageReasonCode: "ENGLISH_DETECTED",
      failureCode: null,
      createdAt: "2026-10-09T00:00:00Z",
    };
    const humanConfirmation = {
      id: "confirmation-1",
      batchId: "batch-1",
      uploadId: "upload-1",
      validationAttemptId: "validation-1",
      contentSha256: "b".repeat(64),
      decision: "CONFIRM_EXACT_VERSION",
      confirmedAt: "2026-10-09T00:01:00Z",
      identifierConfirmation: {
        id: "doi-confirmation-1",
        batchId: "batch-1",
        uploadId: "upload-1",
        analysisRunId: "run-1",
        validationAttemptId: "validation-1",
        contentSha256: "b".repeat(64),
        identifierType: "DOI",
        identifierValue: "10.1234/alternate",
        candidateFingerprint: `recovery-metadata-candidate-v1:${"c".repeat(64)}`,
        confirmedAt: "2026-10-09T00:01:00Z",
      },
    };
    const selection = {
      id: "selection-1",
      batchId: "batch-1",
      analysisRunId: "run-1",
      bibliographyEntryId: "entry-1",
      uploadId: "upload-1",
      validationAttemptId: "validation-1",
      contentSha256: "b".repeat(64),
      selectionMethod: "HUMAN_CONFIRMED",
      selectedAt: "2026-10-09T00:02:00Z",
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([makeBatch([stagedUpload()])]);
      if (url.endsWith("/uploads/upload-1/validation")) {
        return latestValidation ? Response.json(latestValidation) : new Response(null, { status: 204 });
      }
      if (url.endsWith("/uploads/upload-1/validate") && options?.method === "POST") {
        latestValidation = validation;
        return Response.json(validation);
      }
      if (url.endsWith("/validation/validation-1/confirm-identity") && options?.method === "POST") {
        latestValidation = { ...validation, humanConfirmation };
        return Response.json(humanConfirmation);
      }
      if (url.endsWith("/uploads/upload-1/select") && options?.method === "POST") {
        latestValidation = { ...validation, humanConfirmation, selection };
        return Response.json(selection);
      }
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();
    const validateButton = await screen.findByRole("button", { name: "Validate PDF metadata" });
    await waitFor(() => expect(validateButton.hasAttribute("disabled")).toBe(false));
    fireEvent.click(validateButton);

    expect(await screen.findByText("Needs human confirmation")).toBeTruthy();
    expect(screen.getByText("10.1234/alternate")).toBeTruthy();
    expect(screen.getByText(/Page 1/)).toBeTruthy();
    expect(screen.getByText(/English detected/)).toBeTruthy();
    expect(screen.getByText(/not evidence assessment/i)).toBeTruthy();
    fireEvent.change(screen.getByRole("combobox", { name: "DOI candidate that identifies the cited work" }), {
      target: { value: `recovery-metadata-candidate-v1:${"c".repeat(64)}` },
    });
    fireEvent.click(screen.getByRole("button", { name: "Confirm PDF and DOI identity" }));
    expect(await screen.findByText(/DOI identity confirmed: 10.1234\/alternate/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Select this exact version" }));
    expect(await screen.findByText(/Selected exact version/)).toBeTruthy();
    expect(requests.some(({ url, options }) => url.endsWith("/uploads/upload-1/validate") && options?.method === "POST")).toBe(true);
    const confirmationRequest = requests.find(({ url, options }) => url.endsWith("/validation/validation-1/confirm-identity") && options?.method === "POST");
    expect(JSON.parse(String(confirmationRequest?.options?.body))).toEqual({
      confirmExactVersion: true,
      confirmedDoi: "10.1234/alternate",
      doiCandidateFingerprint: `recovery-metadata-candidate-v1:${"c".repeat(64)}`,
    });
    expect(requests.some(({ url, options }) => url.endsWith("/uploads/upload-1/select") && options?.method === "POST")).toBe(true);
    expect(requests.some(({ url, options }) => url.includes("/assess") || (url.includes("/providers") && Boolean(options?.method && options.method !== "GET")))).toBe(false);
  });

  it("blocks identity actions but offers revalidation when the API does not report the current policy", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    const legacyValidation = {
      id: "legacy-validation",
      batchId: "batch-1",
      uploadId: "upload-1",
      analysisRunId: "run-1",
      contentSha256: "b".repeat(64),
      parserId: "docling",
      parserVersion: "1.30.0",
      metadataExtractionPolicyVersion: "docling-first-page-metadata-candidates-v1",
      parserOptions: { from_formats: "pdf", to_formats: "md,json", do_ocr: "false" },
      languageDetectorId: "optimaize",
      languageDetectorVersion: "0.6",
      minimumLanguageConfidence: 0.65,
      validationStatus: "COMPLETED",
      identityOutcome: "NEEDS_CONFIRMATION",
      identityReasonCode: "DOI_DIFFERS_REQUIRES_CONFIRMATION",
      humanConfirmation: null,
      selection: {
        id: "legacy-selection",
        batchId: "batch-1",
        analysisRunId: "run-1",
        bibliographyEntryId: "entry-1",
        uploadId: "upload-1",
        validationAttemptId: "legacy-validation",
        contentSha256: "b".repeat(64),
        selectionMethod: "HUMAN_CONFIRMED",
        selectedAt: "2026-10-09T00:02:00Z",
      },
      metadataCandidates: [],
      languageEligibility: "ELIGIBLE",
      detectedLanguage: "en",
      languageConfidence: 0.99,
      languageReasonCode: "ENGLISH_DETECTED",
      failureCode: null,
      createdAt: "2026-10-09T00:00:00Z",
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([makeBatch([stagedUpload()])]);
      if (url.endsWith("/uploads/upload-1/validation")) return Response.json(legacyValidation);
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();

    expect(await screen.findByText(/Identity policy is not reported as current/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "I confirm this exact PDF version" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Select this exact version" })).toBeNull();
    expect(screen.queryByText("Selected exact version")).toBeNull();
    const revalidateButton = screen.getByRole("button", { name: "Revalidate with current identity policy" });
    expect(revalidateButton.hasAttribute("disabled")).toBe(false);
    expect(requests.some(({ options }) => options?.method === "POST")).toBe(false);
  });

  it("blocks human confirmation and exact-version selection for a clear mismatch", async () => {
    const mismatch = {
      id: "validation-mismatch",
      batchId: "batch-1",
      uploadId: "upload-1",
      analysisRunId: "run-1",
      contentSha256: "b".repeat(64),
      parserId: "docling",
      parserVersion: "1.30.0",
      metadataExtractionPolicyVersion: "docling-first-page-metadata-candidates-v2",
      identityPolicyVersion: "recovery-upload-identity-v2",
      parserOptions: { from_formats: "pdf", to_formats: "md,json", do_ocr: "false" },
      languageDetectorId: "optimaize",
      languageDetectorVersion: "0.6",
      minimumLanguageConfidence: 0.65,
      validationStatus: "COMPLETED",
      identityOutcome: "MISMATCH",
      identityReasonCode: "DOI_TITLE_CONFLICT",
      humanConfirmation: null,
      selection: null,
      metadataCandidates: [],
      languageEligibility: "ELIGIBLE",
      detectedLanguage: "en",
      languageConfidence: 0.99,
      languageReasonCode: "ENGLISH_DETECTED",
      failureCode: null,
      createdAt: "2026-10-09T00:00:00Z",
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([makeBatch([stagedUpload()])]);
      if (url.endsWith("/uploads/upload-1/validation")) return Response.json(mismatch);
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();

    expect(await screen.findByText("Mismatch")).toBeTruthy();
    expect(screen.getByText("This mismatch cannot be selected.")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "I confirm this exact PDF version" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Select this exact version" })).toBeNull();
  });

  it("selects a title-matching alternate DOI without asking for human confirmation", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    const validation = {
      id: "validation-alternate-doi",
      batchId: "batch-1",
      uploadId: "upload-1",
      analysisRunId: "run-1",
      contentSha256: "b".repeat(64),
      parserId: "docling",
      parserVersion: "1.30.0",
      metadataExtractionPolicyVersion: "docling-first-page-metadata-candidates-v2",
      identityPolicyVersion: "recovery-upload-identity-v2",
      parserOptions: { from_formats: "pdf", to_formats: "md,json", do_ocr: "false" },
      languageDetectorId: "optimaize",
      languageDetectorVersion: "0.6",
      minimumLanguageConfidence: 0.65,
      validationStatus: "COMPLETED",
      identityOutcome: "VALIDATED",
      identityReasonCode: "TITLE_MATCH_DIFFERENT_DOI",
      humanConfirmation: null,
      selection: null,
      metadataCandidates: [{
        field: "TITLE",
        value: "A study of evidence",
        pageNumber: 1,
        sourceLabel: "section_header",
        extractionMethod: "DOCLING_LABEL",
        sourceElementId: "docling-title-1",
        sourceCharSpanStart: 0,
        sourceCharSpanEnd: 20,
      }],
      languageEligibility: "ELIGIBLE",
      detectedLanguage: "en",
      languageConfidence: 0.99,
      languageReasonCode: "ENGLISH_DETECTED",
      failureCode: null,
      createdAt: "2026-10-09T00:00:00Z",
    };
    const selection = {
      id: "selection-alternate-doi",
      batchId: "batch-1",
      analysisRunId: "run-1",
      bibliographyEntryId: "entry-1",
      uploadId: "upload-1",
      validationAttemptId: validation.id,
      contentSha256: validation.contentSha256,
      selectionMethod: "MACHINE_VALIDATED",
      selectedAt: "2026-10-09T00:02:00Z",
    };
    let latestValidation: object = validation;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([makeBatch([stagedUpload()])]);
      if (url.endsWith("/uploads/upload-1/validation")) return Response.json(latestValidation);
      if (url.endsWith("/uploads/upload-1/select") && options?.method === "POST") {
        latestValidation = { ...validation, selection };
        return Response.json(selection);
      }
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();

    expect(await screen.findByText("Validated (machine evidence)")).toBeTruthy();
    expect(screen.getByText(/title candidate matches the resolved cited work despite a different DOI/i)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "I confirm this exact PDF version" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Select this exact version" }));
    expect(await screen.findByText(/Selected exact version/)).toBeTruthy();
    expect(requests.some(({ url, options }) => url.endsWith("/uploads/upload-1/select") && options?.method === "POST")).toBe(true);
    expect(requests.some(({ url, options }) => url.includes("confirm-identity") && options?.method === "POST")).toBe(false);
  });

  it("blocks confirmation of a whole-book upload when chapter identity is unsupported", async () => {
    const validation = {
      id: "validation-chapter-unsupported",
      batchId: "batch-1",
      uploadId: "upload-1",
      analysisRunId: "run-1",
      contentSha256: "b".repeat(64),
      parserId: "docling",
      parserVersion: "1.30.0",
      metadataExtractionPolicyVersion: "docling-first-page-metadata-candidates-v2",
      identityPolicyVersion: "recovery-upload-identity-v2",
      parserOptions: { from_formats: "pdf", to_formats: "md,json", do_ocr: "false" },
      languageDetectorId: "optimaize",
      languageDetectorVersion: "0.6",
      minimumLanguageConfidence: 0.65,
      validationStatus: "COMPLETED",
      identityOutcome: "MISMATCH",
      identityReasonCode: "CHAPTER_IDENTITY_UNSUPPORTED",
      humanConfirmation: null,
      selection: null,
      metadataCandidates: [],
      languageEligibility: "ELIGIBLE",
      detectedLanguage: "en",
      languageConfidence: 0.99,
      languageReasonCode: "ENGLISH_DETECTED",
      failureCode: null,
      createdAt: "2026-10-09T00:00:00Z",
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([makeBatch([stagedUpload()])]);
      if (url.endsWith("/uploads/upload-1/validation")) return Response.json(validation);
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();

    expect(await screen.findByText("Mismatch")).toBeTruthy();
    expect(screen.getByText(/Whole-book PDFs are blocked until chapter identity can be established/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "I confirm this exact PDF version" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Select this exact version" })).toBeNull();
  });

  it("blocks inconclusive book uploads from older bibliography policies", async () => {
    const validation = {
      id: "validation-legacy-book",
      batchId: "batch-1",
      uploadId: "upload-1",
      analysisRunId: "run-1",
      contentSha256: "b".repeat(64),
      parserId: "docling",
      parserVersion: "1.30.0",
      metadataExtractionPolicyVersion: "docling-first-page-metadata-candidates-v2",
      identityPolicyVersion: "recovery-upload-identity-v2",
      parserOptions: { from_formats: "pdf", to_formats: "md,json", do_ocr: "false" },
      languageDetectorId: "optimaize",
      languageDetectorVersion: "0.6",
      minimumLanguageConfidence: 0.65,
      validationStatus: "COMPLETED",
      identityOutcome: "MISMATCH",
      identityReasonCode: "BOOK_CHAPTER_IDENTITY_UNSUPPORTED",
      humanConfirmation: null,
      selection: null,
      metadataCandidates: [],
      languageEligibility: "ELIGIBLE",
      detectedLanguage: "en",
      languageConfidence: 0.99,
      languageReasonCode: "ENGLISH_DETECTED",
      failureCode: null,
      createdAt: "2026-10-09T00:00:00Z",
    };
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([makeBatch([stagedUpload()])]);
      if (url.endsWith("/uploads/upload-1/validation")) return Response.json(validation);
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();

    expect(await screen.findByText("Mismatch")).toBeTruthy();
    expect(screen.getByText(/older book entry from a cited chapter/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "I confirm this exact PDF version" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Select this exact version" })).toBeNull();
  });

  it("records identity confirmation separately when English eligibility blocks selection", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    const validation = {
      id: "validation-language-gate",
      batchId: "batch-1",
      uploadId: "upload-1",
      analysisRunId: "run-1",
      contentSha256: "b".repeat(64),
      parserId: "docling",
      parserVersion: "1.30.0",
      metadataExtractionPolicyVersion: "docling-first-page-metadata-candidates-v2",
      identityPolicyVersion: "recovery-upload-identity-v2",
      parserOptions: { from_formats: "pdf", to_formats: "md,json", do_ocr: "false" },
      languageDetectorId: "optimaize",
      languageDetectorVersion: "0.6",
      minimumLanguageConfidence: 0.65,
      validationStatus: "COMPLETED",
      identityOutcome: "NEEDS_CONFIRMATION",
      identityReasonCode: "DOI_DIFFERS_REQUIRES_CONFIRMATION",
      humanConfirmation: null,
      selection: null,
      metadataCandidates: [],
      languageEligibility: "INELIGIBLE",
      detectedLanguage: "fr",
      languageConfidence: 0.99,
      languageReasonCode: "NON_ENGLISH_DETECTED",
      failureCode: null,
      createdAt: "2026-10-09T00:00:00Z",
    };
    const confirmation = {
      id: "confirmation-language-gate",
      batchId: "batch-1",
      uploadId: "upload-1",
      validationAttemptId: validation.id,
      contentSha256: validation.contentSha256,
      decision: "CONFIRM_EXACT_VERSION",
      confirmedAt: "2026-10-09T00:01:00Z",
    };
    let latestValidation: object = validation;
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([makeBatch([stagedUpload()])]);
      if (url.endsWith("/uploads/upload-1/validation")) return Response.json(latestValidation);
      if (url.endsWith("/validation/validation-language-gate/confirm-identity") && options?.method === "POST") {
        latestValidation = { ...validation, humanConfirmation: confirmation };
        return Response.json(confirmation);
      }
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();

    expect(await screen.findByText("Needs human confirmation")).toBeTruthy();
    expect(screen.getByRole("button", { name: "I confirm this exact PDF version" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Select this exact version" })).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "I confirm this exact PDF version" }));

    expect(await screen.findByText(/Human confirmation recorded/)).toBeTruthy();
    expect(screen.getByText(/not eligible for English-only assessment/)).toBeTruthy();
    expect(screen.getByText(/Selection is blocked/)).toBeTruthy();
    expect(screen.queryByRole("button", { name: "Select this exact version" })).toBeNull();
    expect(requests.some(({ url, options }) => url.includes("confirm-identity") && options?.method === "POST")).toBe(true);
    expect(requests.some(({ url }) => url.endsWith("/select"))).toBe(false);
  });

  it("shows a staged PDF as a verified candidate, preserves the declaration, and offers removal", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    let activeBatch = makeBatch([stagedUpload()]);
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json([activeBatch]);
      if (url === "/api/v1/recovery-batches/batch-1/uploads/upload-1" && options?.method === "DELETE") {
        activeBatch = makeBatch([{ ...stagedUpload(), status: "REMOVED" }]);
        return Response.json(activeBatch.uploads[0]);
      }
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();
    await screen.findByText("Server-verified snapshot");
    expect(screen.getByText("candidate.pdf · 1 KiB · SHA-256 " + "b".repeat(64))).toBeTruthy();
    expect(screen.getByText(/does not establish that this is the cited paper/)).toBeTruthy();
    fireEvent.click(screen.getByText("View recorded PDF-rights declaration"));
    expect(screen.getByText(rights.text)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Remove upload" }));
    await waitFor(() => expect(requests.some(({ options }) => options?.method === "DELETE")).toBe(true));
    expect(await screen.findByText(/This upload is removed\./)).toBeTruthy();
    expect(requests.some(({ url, options }) => url.includes("/assess") || (url.includes("/providers") && Boolean(options?.method && options.method !== "GET")))).toBe(false);
  });
});
