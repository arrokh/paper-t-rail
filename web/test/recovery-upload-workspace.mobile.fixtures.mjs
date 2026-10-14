import { createServer } from "node:http";

const analysisRunId = "run-mobile";
const batchId = "batch-mobile";
const bibliographyEntryId = "entry-mobile";
const localReferenceKey = `mobile-reference-${"R".repeat(150)}-猫`;
const identifierValue = `10.1234/mobile-layout-${"longidentifier".repeat(18)}`;
const disclosureText = `LONG_DISCLOSURE_START ${"The researcher asserts rights to stage this PDF locally after reading every term. ".repeat(8)} LONG_DISCLOSURE_END`;

const rights = {
  version: "recovery-rights-v1",
  text: disclosureText,
  maxFileBytes: 52_428_800,
  maxFilesPerBatch: 10,
  maxBatchBytes: 262_144_000,
  uploadUrlTtlSeconds: 300,
  inactivityTtlSeconds: 604_800,
};

const fixtures = new Map([
  [`/api/v1/analysis-runs/${analysisRunId}`, {
    id: analysisRunId,
    documentId: "document-mobile",
    filename: "recovery-mobile-layout.pdf",
    sourceContentSha256: "a".repeat(64),
    status: "COMPLETED",
    progress: {},
    pipeline: null,
    configuration: {
      captureExecution: false,
      claimExtractor: { provider: "heuristic", version: "1" },
      embedding: { provider: "local", version: "1" },
      retrieval: {
        profileId: "local",
        vectorCandidateLimit: 5,
        lexicalCandidateLimit: 5,
        finalCandidateLimit: 5,
        reciprocalRankFusionConstant: 60,
        embeddingProfileHash: "b".repeat(64),
      },
      systemOne: { provider: "mock", version: "1" },
      sourceParser: { provider: "grobid", version: "1" },
      languageDetector: { provider: "local", version: "1" },
      referenceResolution: {
        executionStatus: "NOT_RUN",
        provider: { provider: "recorded-fixtures", version: "1" },
        scorePolicyVersion: null,
        confidenceThreshold: null,
      },
      openAccess: { provider: "recorded-fixtures", version: "1" },
      externalProviderConsents: [],
    },
    createdAt: "2026-10-13T10:00:00Z",
    startedAt: null,
    failureReason: null,
  }],
  [`/api/v1/analysis-runs/${analysisRunId}/parsed-document`, {
    parser: { provider: "grobid", version: "1" },
    sourceContentSha256: "a".repeat(64),
    normalizedSourceText: "Synthetic mobile layout test document.",
    sections: [],
    citationContexts: [],
    bibliographyEntries: [{
      entryOrder: 0,
      localReferenceKey,
      rawText: "Synthetic entry for checking mobile reflow.",
      title: "Synthetic entry for checking mobile reflow.",
      authors: ["Example Researcher"],
      year: 2026,
      doi: null,
      referenceType: "JOURNAL_ARTICLE",
      resolutionStatus: "UNRESOLVED",
      sourceTextContent: null,
      sourceElement: "biblStruct",
      sourceLocalReferenceKey: localReferenceKey,
      localReferenceKeyOrigin: "GROBID_XML_ID",
      identifiers: [],
      sourceLocations: [],
      provisionalArtifactSignals: [],
      extractionLimitations: [],
      provenanceCaptureStatus: "CAPTURED",
    }],
  }],
  [`/api/v1/analysis-runs/${analysisRunId}/report`, {
    analysisRunId,
    runStatus: "COMPLETED",
    entries: [],
  }],
  ["/api/v1/recovery-rights-declaration", rights],
  [`/api/v1/analysis-runs/${analysisRunId}/recovery-batches`, [{
    id: batchId,
    analysisRunId,
    status: "OPEN",
    rightsDeclarationVersion: rights.version,
    rightsDeclarationText: disclosureText,
    rightsDeclaredAt: "2026-10-13T10:02:00Z",
    createdAt: "2026-10-13T10:00:00Z",
    lastActivityAt: "2026-10-13T10:02:00Z",
    expiresAt: "2026-10-20T10:00:00Z",
    revision: 1,
    uploads: [],
    selectedAssets: [],
    identifierAssistance: [{
      bibliographyEntryId,
      localReferenceKey,
      identifierType: "DOI",
      identifierValue,
      recordedAt: "2026-10-13T10:02:00Z",
    }],
  }]],
  ["/api/v1/providers", {
    providers: {
      claimExtractor: [{ role: "claimExtractor", providerId: "heuristic", displayName: "Local claims", version: "1", model: null, trustBoundary: "LOCAL", dataCategories: [], retentionDisclosure: null, retentionDisclosureFingerprint: null }],
      embedding: [{ role: "embedding", providerId: "local", displayName: "Local embeddings", version: "1", model: null, trustBoundary: "LOCAL", dataCategories: [], retentionDisclosure: null, retentionDisclosureFingerprint: null }],
      systemOne: [{ role: "systemOne", providerId: "mock", displayName: "Local review", version: "1", model: null, trustBoundary: "LOCAL", dataCategories: [], retentionDisclosure: null, retentionDisclosureFingerprint: null }],
      scholarlyMetadata: [{ role: "scholarlyMetadata", providerId: "recorded-fixtures", displayName: "Recorded metadata", version: "1", model: null, trustBoundary: "LOCAL", dataCategories: [], retentionDisclosure: null, retentionDisclosureFingerprint: null }],
      openAccess: [{ role: "openAccess", providerId: "recorded-fixtures", displayName: "Recorded access", version: "1", model: null, trustBoundary: "LOCAL", dataCategories: [], retentionDisclosure: null, retentionDisclosureFingerprint: null }],
    },
    dataCategories: [],
  }],
  [`/api/v1/analysis-runs/${analysisRunId}/recovery-history`, {
    analysisRunId,
    events: [{
      id: "history-mobile",
      batchId,
      batchStatus: "OPEN",
      type: "ATTEMPT_STARTED",
      outcomeCode: `MOBILE_HISTORY_${"O".repeat(50)}`,
      stageCode: `mobile-stage-${"s".repeat(40)}`,
      attemptNumber: 1,
      bibliographyEntryId,
      uploadId: null,
      previousUploadId: null,
      submission: null,
      createdAt: "2026-10-13T10:03:00Z",
    }],
    nextCursor: null,
  }],
]);

const port = Number(process.env.PORT ?? 8081);
createServer((request, response) => {
  const pathname = new URL(request.url ?? "/", "http://127.0.0.1").pathname;
  const body = fixtures.get(pathname);
  response.writeHead(body === undefined ? 404 : 200, { "content-type": "application/json" });
  response.end(JSON.stringify(body ?? { code: "NOT_FOUND", message: "Fixture endpoint not found." }));
}).listen(port, "127.0.0.1", () => {
  console.log(`Synthetic Recovery Upload mobile fixtures listening on http://127.0.0.1:${port}`);
});
