import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RecoveryUploadWorkspace } from "@/features/recovery-uploads/components/recovery-upload-workspace";
import type { ParsedDocument } from "@/features/analysis-runs/types";
import type { RecoveryBatch, RecoveryRightsDeclaration } from "@/features/recovery-uploads/types";

const rights: RecoveryRightsDeclaration = {
  version: "recovery-rights-v1",
  text: "I have the right to stage this PDF locally.",
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

function makeBatch(uploads: RecoveryBatch["uploads"] = []): RecoveryBatch {
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
    uploads,
  };
}

function stagedUpload(): RecoveryBatch["uploads"][number] {
  return {
    id: "upload-1",
    localReferenceKey: "b0",
    idempotencyKey: "upload-key-1",
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

function renderWorkspace(
  entries: ParsedDocument["bibliographyEntries"] | null = bibliographyEntries,
  entriesError: string | null = null,
) {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RecoveryUploadWorkspace
        analysisRunId="run-1"
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
  it("requires the displayed rights declaration before creating a batch", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    let activeBatch: RecoveryBatch | null = null;
    vi.stubGlobal("crypto", { ...globalThis.crypto, randomUUID: () => "batch-key-1" });
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      const url = String(input);
      requests.push({ url, options });
      if (url === "/api/v1/recovery-rights-declaration") return Response.json(rights);
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches" && options?.method === "POST") {
        activeBatch = makeBatch();
        return Response.json(activeBatch);
      }
      if (url === "/api/v1/analysis-runs/run-1/recovery-batches") return Response.json(activeBatch ? [activeBatch] : []);
      throw new Error(`Unexpected request: ${url}`);
    }));

    renderWorkspace();
    await screen.findByText(rights.text);
    expect(screen.getByText(/10 PDFs and 250 MiB total/)).toBeTruthy();
    const createButton = screen.getByRole("button", { name: "Create Recovery Batch" });
    expect(createButton.hasAttribute("disabled")).toBe(true);

    fireEvent.click(screen.getByRole("checkbox", { name: "I have read and accept this declaration" }));
    expect(createButton.hasAttribute("disabled")).toBe(false);
    fireEvent.click(createButton);

    await screen.findByText("Active Recovery Batch");
    const createRequest = requests.find(({ options }) => options?.method === "POST");
    expect(createRequest?.url).toBe("/api/v1/analysis-runs/run-1/recovery-batches");
    expect(JSON.parse(String(createRequest?.options?.body))).toEqual({
      idempotencyKey: "batch-key-1",
      rightsDeclarationVersion: rights.version,
      rightsDeclarationAccepted: true,
    });
    expect(screen.getByText(/does not confirm the paper identity/)).toBeTruthy();
    expect(screen.getByText(/Uploading never starts provider assessment/)).toBeTruthy();
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
    expect(screen.queryByRole("button", { name: "Choose PDF" })).toBeNull();
    expect(screen.getByText(/New browser uploads are disabled/)).toBeTruthy();
    fireEvent.click(screen.getByText("View recorded rights declaration"));
    expect(screen.getByText(rights.text)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Remove upload" }));
    await waitFor(() => expect(requests.some(({ options }) => options?.method === "DELETE")).toBe(true));
    expect(await screen.findByText(/This upload is removed\./)).toBeTruthy();
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
      metadataExtractionPolicyVersion: "docling-first-page-metadata-candidates-v1",
      parserOptions: { from_formats: "pdf", to_formats: "md,json", do_ocr: "false" },
      languageDetectorId: "optimaize",
      languageDetectorVersion: "0.6",
      minimumLanguageConfidence: 0.65,
      validationStatus: "COMPLETED",
      identityOutcome: "NEEDS_CONFIRMATION",
      identityReasonCode: "DOI_DIFFERS_REQUIRES_CONFIRMATION",
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
    fireEvent.click(screen.getByRole("button", { name: "I confirm this exact PDF version" }));
    expect(await screen.findByText(/Human confirmation recorded/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Select this exact version" }));
    expect(await screen.findByText(/Selected exact version/)).toBeTruthy();
    expect(requests.some(({ url, options }) => url.endsWith("/uploads/upload-1/validate") && options?.method === "POST")).toBe(true);
    const confirmationRequest = requests.find(({ url, options }) => url.endsWith("/validation/validation-1/confirm-identity") && options?.method === "POST");
    expect(JSON.parse(String(confirmationRequest?.options?.body))).toEqual({ confirmExactVersion: true });
    expect(requests.some(({ url, options }) => url.endsWith("/uploads/upload-1/select") && options?.method === "POST")).toBe(true);
    expect(requests.some(({ url }) => url.includes("/assess") || url.includes("/providers"))).toBe(false);
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
      metadataExtractionPolicyVersion: "docling-first-page-metadata-candidates-v1",
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
      metadataExtractionPolicyVersion: "docling-first-page-metadata-candidates-v1",
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
    fireEvent.click(screen.getByText("View recorded rights declaration"));
    expect(screen.getByText(rights.text)).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "Remove upload" }));
    await waitFor(() => expect(requests.some(({ options }) => options?.method === "DELETE")).toBe(true));
    expect(await screen.findByText(/This upload is removed\./)).toBeTruthy();
    expect(requests.some(({ url }) => url.includes("/assess") || url.includes("/providers"))).toBe(false);
  });
});
