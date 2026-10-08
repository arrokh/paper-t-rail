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

function renderWorkspace() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <RecoveryUploadWorkspace
        analysisRunId="run-1"
        entries={bibliographyEntries}
        entriesLoading={false}
        entriesError={null}
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
