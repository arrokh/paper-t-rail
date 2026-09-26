import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ClaimEvidencePassages } from "@/features/reference-resolution/components/claim-evidence-passages";
import type { ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";

type Access = NonNullable<ReferenceResolutionReportResponse["referenceResolution"]["entries"][number]["citedPaperAccess"]>;

const outcome: Access["verificationOutcomes"][number] = {
  atomicClaimId: "claim-12345678",
  claimText: "The intervention improved the measured outcome.",
  finalStatus: null,
  verificationScope: "FULL_TEXT",
  terminalReason: null,
  evidencePassages: [{
    id: "passage-1",
    text: "The intervention group showed a sustained improvement in the measured outcome.",
    sectionOrder: 0,
    sectionHeading: "Results",
    paragraphStart: 3,
    paragraphEnd: 4,
    pageNumber: null,
    vectorRank: 2,
    lexicalRank: 1,
    fusedRank: 1,
    fusionScore: 0.032522,
    sourceAssetId: "asset-1234",
    contentSha256: "a".repeat(64),
    parserProvider: "grobid",
    parserVersion: "0.9.1-crf",
    language: "en",
    languageDetectorVersion: "0.6",
    retrievalProfile: {
      profileId: "postgres-hybrid-rrf-v1",
      vectorCandidateLimit: 10,
      lexicalCandidateLimit: 10,
      finalCandidateLimit: 5,
      reciprocalRankFusionConstant: 60,
      embeddingProvider: "local",
      embeddingModel: "feature-hash-384-v1",
      embeddingVersion: "v1",
      embeddingDimension: 384,
      embeddingProfileHash: "b".repeat(64),
    },
  }],
};

describe("Claim Evidence Passages", () => {
  it("drills from a claim to its exact ranked passage and retrieval provenance", () => {
    render(
      <ul>
        <ClaimEvidencePassages outcome={outcome} indexingStatus="COMPLETED" />
      </ul>,
    );

    expect(screen.getByText(outcome.claimText)).toBeTruthy();
    expect(screen.getByRole("button", { name: /Evidence Passage · fused rank 1/ })).toBeTruthy();
    expect(screen.queryByText(outcome.evidencePassages[0].text)).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /Evidence Passage · fused rank 1/ }));

    expect(screen.getByText(outcome.evidencePassages[0].text)).toBeTruthy();
    expect(screen.getByText("Section 1 · Results")).toBeTruthy();
    expect(screen.getByText("3–4")).toBeTruthy();
    expect(screen.getByText("grobid · 0.9.1-crf")).toBeTruthy();
    expect(screen.getByText(/postgres-hybrid-rrf-v1 · vector top 10 · lexical top 10 · final 5 · RRF 60/)).toBeTruthy();
    expect(screen.getByText("b".repeat(64))).toBeTruthy();
  });
});
