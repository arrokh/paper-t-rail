import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { CitedPaperAccessSummary } from "@/features/reference-resolution/components/cited-paper-access-summary";

describe("Cited Paper access summary", () => {
  it("explains a persisted identity-unresolved access skip when no access result exists", () => {
    render(
      <CitedPaperAccessSummary
        access={null}
        progressStatus="SKIPPED"
        progressReason="ACCESS_SKIPPED_IDENTITY_UNRESOLVED"
      />,
    );

    expect(screen.getByRole("region", { name: "Cited Paper access status" })).toBeTruthy();
    expect(screen.getByText("Access skipped")).toBeTruthy();
    expect(screen.getByText("Access was skipped because the Bibliography Entry has no resolved identity.")).toBeTruthy();
  });

  it("distinguishes acquired full text from language eligibility for semantic assessment", () => {
    render(
      <CitedPaperAccessSummary
        access={{
          accessStatus: "FULL_TEXT_AVAILABLE",
          accessOrigin: "AUTOMATIC",
          accessReason: null,
          accessReasons: ["LANGUAGE_UNSUPPORTED"],
          candidateOutcomes: [],
          candidateOutcomesOmittedCount: 0,
          providerId: "recorded-fixtures",
          sourceUrl: null,
          license: "CC0-1.0",
          version: "publishedVersion",
          hostType: "repository",
          discoveredAt: "2025-01-01T00:00:00Z",
          contentSha256: "a".repeat(64),
          mediaType: null,
          language: "fr",
          languageDetectorVersion: "0.6",
          rightsDeclarationVersion: null,
          rightsDeclarationText: null,
          rightsDeclaredAt: null,
          originatingAnalysisRunId: null,
          originatingRecoveryBatchId: null,
          originatingRecoveryUploadId: null,
          verificationOutcomes: [],
          evidenceIndexing: null,
        }}
        progressStatus="COMPLETED"
        progressReason={null}
      />,
    );

    expect(screen.getByText("full text available")).toBeTruthy();
    expect(screen.getByText("Full text was acquired and parsed, but its language was not confirmed as supported English; semantic assessment was not run. Availability is not evidence of support.")).toBeTruthy();
  });

  it("identifies a supplied PDF without inventing a provider or license", () => {
    render(
      <CitedPaperAccessSummary
        access={{
          accessStatus: "FULL_TEXT_AVAILABLE",
          accessOrigin: "USER_SUPPLIED",
          accessReason: null,
          accessReasons: [],
          candidateOutcomes: [],
          candidateOutcomesOmittedCount: 0,
          providerId: null,
          sourceUrl: null,
          license: null,
          version: null,
          hostType: null,
          discoveredAt: "2025-01-01T00:00:00Z",
          contentSha256: "b".repeat(64),
          mediaType: "application/pdf",
          language: "en",
          languageDetectorVersion: "0.6",
          rightsDeclarationVersion: "recovery-v1",
          rightsDeclarationText: "I may use this copy for private analysis.",
          rightsDeclaredAt: "2025-01-01T00:00:00Z",
          originatingAnalysisRunId: "run-id",
          originatingRecoveryBatchId: "batch-id",
          originatingRecoveryUploadId: "upload-id",
          verificationOutcomes: [],
          evidenceIndexing: null,
        }}
        progressStatus="COMPLETED"
        progressReason={null}
      />,
    );

    expect(screen.getByText("Access origin")).toBeTruthy();
    expect(screen.getByText("User-supplied PDF")).toBeTruthy();
    expect(screen.getByText("Access recorded")).toBeTruthy();
    expect(screen.getByText("Supplied PDF SHA-256")).toBeTruthy();
    expect(screen.queryByText("Discovery provider")).toBeNull();
    expect(screen.queryByText("License")).toBeNull();
  });

  it.each([
    ["FULL_TEXT_IDENTITY_UNVERIFIED", "The file's identity could not be confirmed from its metadata; it was not sent for evidence assessment."],
    ["FULL_TEXT_IDENTITY_MISMATCH", "The file failed the identity check because of a mismatch or unsupported reference type; it was not sent for evidence assessment."],
    ["FULL_TEXT_IDENTITY_VALIDATION_FAILED", "The system could not complete the file identity check; it was not sent for evidence assessment."],
  ] as const)("explains the automatic identity gate for %s", (cause, explanation) => {
    render(
      <CitedPaperAccessSummary
        access={{
          accessStatus: "METADATA_ONLY",
          accessOrigin: "AUTOMATIC",
          accessReason: "FULL_TEXT_ACQUISITION_FAILED",
          accessReasons: [cause],
          candidateOutcomes: [],
          candidateOutcomesOmittedCount: 0,
          providerId: "recorded-fixtures",
          sourceUrl: null,
          license: "CC0-1.0",
          version: "publishedVersion",
          hostType: "repository",
          discoveredAt: "2025-01-01T00:00:00Z",
          contentSha256: null,
          mediaType: null,
          language: null,
          languageDetectorVersion: null,
          rightsDeclarationVersion: null,
          rightsDeclarationText: null,
          rightsDeclaredAt: null,
          originatingAnalysisRunId: null,
          originatingRecoveryBatchId: null,
          originatingRecoveryUploadId: null,
          verificationOutcomes: [],
          evidenceIndexing: null,
        }}
        progressStatus="COMPLETED"
        progressReason={null}
      />,
    );

    expect(screen.getByText(explanation)).toBeTruthy();
  });

  it("keeps legacy access reasons coarse when detailed causes were not persisted", () => {
    render(
      <CitedPaperAccessSummary
        access={{
          accessStatus: "METADATA_ONLY",
          accessOrigin: "AUTOMATIC",
          accessReason: "NO_LEGAL_FULL_TEXT_LOCATION",
          accessReasons: [],
          candidateOutcomes: [],
          candidateOutcomesOmittedCount: 0,
          providerId: "legacy-provider",
          sourceUrl: null,
          license: null,
          version: null,
          hostType: null,
          discoveredAt: "2025-01-01T00:00:00Z",
          contentSha256: null,
          mediaType: null,
          language: null,
          languageDetectorVersion: null,
          rightsDeclarationVersion: null,
          rightsDeclarationText: null,
          rightsDeclaredAt: null,
          originatingAnalysisRunId: null,
          originatingRecoveryBatchId: null,
          originatingRecoveryUploadId: null,
          verificationOutcomes: [],
          evidenceIndexing: null,
        }}
        progressStatus={null}
        progressReason={null}
      />,
    );

    expect(screen.getByText("No legally usable full-text location was found.")).toBeTruthy();
    expect(screen.queryByText("The provider returned no full-text location.")).toBeNull();
    expect(screen.queryByText("A full-text location did not include a license that permits processing.")).toBeNull();
  });

  it("does not infer assessment eligibility from a legacy full-text access row", () => {
    render(
      <CitedPaperAccessSummary
        access={{
          accessStatus: "FULL_TEXT_AVAILABLE",
          accessOrigin: "AUTOMATIC",
          accessReason: null,
          accessReasons: [],
          candidateOutcomes: [],
          candidateOutcomesOmittedCount: 0,
          providerId: "legacy-provider",
          sourceUrl: null,
          license: null,
          version: null,
          hostType: null,
          discoveredAt: "2025-01-01T00:00:00Z",
          contentSha256: null,
          mediaType: null,
          language: "fr",
          languageDetectorVersion: "0.5",
          rightsDeclarationVersion: null,
          rightsDeclarationText: null,
          rightsDeclaredAt: null,
          originatingAnalysisRunId: null,
          originatingRecoveryBatchId: null,
          originatingRecoveryUploadId: null,
          verificationOutcomes: [],
          evidenceIndexing: null,
        }}
        progressStatus="COMPLETED"
        progressReason={null}
      />,
    );

    expect(screen.getByText("Full-text availability alone does not show whether semantic assessment ran. Availability is not evidence of support.")).toBeTruthy();
  });

  it("shows bounded per-location reasons without exposing candidate URLs", () => {
    render(
      <CitedPaperAccessSummary
        access={{
          accessStatus: "FULL_TEXT_AVAILABLE",
          accessOrigin: "AUTOMATIC",
          accessReason: null,
          accessReasons: ["FULL_TEXT_LOCATION_URL_REJECTED"],
          candidateOutcomes: [
            {
              ordinal: 1,
              host: "unsafe.example",
              license: "CC0-1.0",
              version: "publishedVersion",
              hostType: "repository",
              outcome: "REJECTED",
              reasonCodes: ["FULL_TEXT_LOCATION_URL_REJECTED"],
            },
            {
              ordinal: 2,
              host: "archive.example",
              license: "CC-BY-4.0",
              version: "publishedVersion",
              hostType: "repository",
              outcome: "ACQUIRED",
              reasonCodes: [],
            },
          ],
          candidateOutcomesOmittedCount: 3,
          providerId: "unpaywall",
          sourceUrl: null,
          license: "CC-BY-4.0",
          version: "publishedVersion",
          hostType: "repository",
          discoveredAt: "2025-01-01T00:00:00Z",
          contentSha256: "c".repeat(64),
          mediaType: "application/pdf",
          language: "en",
          languageDetectorVersion: "0.6",
          rightsDeclarationVersion: null,
          rightsDeclarationText: null,
          rightsDeclaredAt: null,
          originatingAnalysisRunId: null,
          originatingRecoveryBatchId: null,
          originatingRecoveryUploadId: null,
          verificationOutcomes: [],
          evidenceIndexing: null,
        }}
        progressStatus="COMPLETED"
        progressReason={null}
      />,
    );

    expect(screen.getByText("Full-text location outcomes")).toBeTruthy();
    expect(screen.getByText("Location 1: Rejected by the location policy")).toBeTruthy();
    expect(screen.getAllByText("A full-text location URL did not pass the safe-location policy.").length).toBe(2);
    expect(screen.getByText(/unsafe\.example/)).toBeTruthy();
    expect(screen.getByText("Location 2: Acquired and identity-checked")).toBeTruthy();
    expect(screen.getByText("3 additional location outcomes were omitted to keep this report bounded.")).toBeTruthy();
    expect(screen.queryByText(/token|query/i)).toBeNull();
  });

  it("reports missing attribution without guessing a cause", () => {
    render(<CitedPaperAccessSummary access={null} progressStatus={null} progressReason={null} />);

    expect(screen.getByText("No access result")).toBeTruthy();
    expect(screen.getByText("No access outcome was recorded; no specific cause is available.")).toBeTruthy();
  });

  it("shows a persisted reason even when stage status is unavailable", () => {
    render(<CitedPaperAccessSummary access={null} progressStatus={null} progressReason="ACCESS_SKIPPED_IDENTITY_UNRESOLVED" />);

    expect(screen.getByText("Access was skipped because the Bibliography Entry has no resolved identity.")).toBeTruthy();
  });
});
