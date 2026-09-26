import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ClaimEvidencePassages } from "@/features/reference-resolution/components/claim-evidence-passages";
import type { ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";

type Verification = ReferenceResolutionReportResponse["referenceResolution"]["entries"][number]["verificationOutcomes"][number];

const outcome: Verification = {
  id: "verification-1234",
  atomicClaimId: "claim-12345678",
  claimText: "The intervention improved the measured outcome.",
  claimSourceStartOffset: 12,
  claimSourceEndOffset: 61,
  citationContextText: "The intervention improved the measured outcome [1].",
  citationMarkers: ["[1]"],
  associationKind: "INFERRED_PROVISIONAL",
  processingStatus: "COMPLETED",
  processingFailureReason: null,
  finalStatus: "SUPPORTED",
  verificationScope: "FULL_TEXT",
  terminalReason: null,
  evidenceConflict: false,
  aggregatorVersion: "conflict-aware-evidence-strength-v1",
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
    evidenceJudgement: {
      providerId: "mock",
      modelId: "mock-v1",
      providerVersion: "v1",
      judgement: "DIRECT_SUPPORT",
      evidenceRole: "PRIMARY_FINDING",
      confidence: 0.9,
      directness: 0.9,
      claimScopeMatch: 0.9,
      studyDesignQuality: 0.8,
      relevance: 0.9,
      calibratedStrength: 0.88,
    },
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
  it("drills from an Atomic Claim and its citation to a ranked, judged passage and retrieval provenance", () => {
    render(
      <ul>
        <ClaimEvidencePassages outcome={outcome} indexingStatus="COMPLETED" />
      </ul>,
    );

    expect(screen.getByText(outcome.claimText)).toBeTruthy();
    expect(screen.getByText(outcome.citationContextText)).toBeTruthy();
    expect(screen.getByText("[1]")).toBeTruthy();
    expect(screen.getByText(/inferred provisional — inferred, not author-confirmed/)).toBeTruthy();
    expect(screen.getByRole("button", { name: /Evidence Passage · fused rank 1/ })).toBeTruthy();
    expect(screen.queryByText(outcome.evidencePassages[0].text)).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /Evidence Passage · fused rank 1/ }));

    expect(screen.getByText(outcome.evidencePassages[0].text)).toBeTruthy();
    expect(screen.getByText("direct support · role: primary finding · calibrated strength 0.880")).toBeTruthy();
    expect(screen.getByText("Section 1 · Results")).toBeTruthy();
    expect(screen.getByText("3–4")).toBeTruthy();
    expect(screen.getByText("grobid · 0.9.1-crf")).toBeTruthy();
    expect(screen.getByText(/postgres-hybrid-rrf-v1 · vector top 10 · lexical top 10 · final 5 · RRF 60/)).toBeTruthy();
    expect(screen.getByText("b".repeat(64))).toBeTruthy();
  });

  it("keeps comparably strong support and contradiction passages visible together", () => {
    const conflict: Verification = {
      ...outcome,
      finalStatus: "INSUFFICIENT_EVIDENCE",
      evidenceConflict: true,
      evidencePassages: [
        outcome.evidencePassages[0],
        {
          ...outcome.evidencePassages[0],
          id: "passage-2",
          text: "The later study found no improvement in the measured outcome.",
          fusedRank: 2,
          evidenceJudgement: {
            ...outcome.evidencePassages[0].evidenceJudgement!,
            judgement: "CONTRADICTS",
            calibratedStrength: 0.87,
          },
        },
      ],
    };

    render(
      <ul>
        <ClaimEvidencePassages outcome={conflict} indexingStatus="COMPLETED" />
      </ul>,
    );

    expect(screen.getByText("Support and contradiction are comparably strong")).toBeTruthy();
    expect(screen.getByRole("button", { name: /Evidence Passage · fused rank 1/ })).toBeTruthy();
    expect(screen.getByRole("button", { name: /Evidence Passage · fused rank 2/ })).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: /Evidence Passage · fused rank 1/ }));
    fireEvent.click(screen.getByRole("button", { name: /Evidence Passage · fused rank 2/ }));

    expect(screen.getByText(outcome.evidencePassages[0].text)).toBeTruthy();
    expect(screen.getByText("The later study found no improvement in the measured outcome.")).toBeTruthy();
  });

  it("shows processing failure as an incomplete pair with no fabricated domain status", () => {
    const incomplete: Verification = {
      ...outcome,
      processingStatus: "INCOMPLETE",
      processingFailureReason: "EVIDENCE_VERIFICATION_RETRIES_EXHAUSTED",
      finalStatus: null,
      evidencePassages: [],
    };

    render(
      <ul>
        <ClaimEvidencePassages outcome={incomplete} indexingStatus="COMPLETED" />
      </ul>,
    );

    expect(screen.getByText("incomplete pair")).toBeTruthy();
    expect(screen.getByText("evidence verification retries exhausted")).toBeTruthy();
    expect(screen.queryByText("supported")).toBeNull();
    expect(screen.getByText("Processing stopped before this Claim–Reference Verification completed.")).toBeTruthy();
  });
});
