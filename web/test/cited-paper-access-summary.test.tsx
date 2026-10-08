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
          accessReason: null,
          accessReasons: ["LANGUAGE_UNSUPPORTED"],
          providerId: "recorded-fixtures",
          sourceUrl: null,
          license: "CC0-1.0",
          version: "publishedVersion",
          hostType: "repository",
          discoveredAt: "2025-01-01T00:00:00Z",
          contentSha256: "a".repeat(64),
          language: "fr",
          languageDetectorVersion: "0.6",
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

  it("keeps legacy access reasons coarse when detailed causes were not persisted", () => {
    render(
      <CitedPaperAccessSummary
        access={{
          accessStatus: "METADATA_ONLY",
          accessReason: "NO_LEGAL_FULL_TEXT_LOCATION",
          accessReasons: [],
          providerId: "legacy-provider",
          sourceUrl: null,
          license: null,
          version: null,
          hostType: null,
          discoveredAt: "2025-01-01T00:00:00Z",
          contentSha256: null,
          language: null,
          languageDetectorVersion: null,
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
          accessReason: null,
          accessReasons: [],
          providerId: "legacy-provider",
          sourceUrl: null,
          license: null,
          version: null,
          hostType: null,
          discoveredAt: "2025-01-01T00:00:00Z",
          contentSha256: null,
          language: "fr",
          languageDetectorVersion: "0.5",
          verificationOutcomes: [],
          evidenceIndexing: null,
        }}
        progressStatus="COMPLETED"
        progressReason={null}
      />,
    );

    expect(screen.getByText("Full-text availability alone does not show whether semantic assessment ran. Availability is not evidence of support.")).toBeTruthy();
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
