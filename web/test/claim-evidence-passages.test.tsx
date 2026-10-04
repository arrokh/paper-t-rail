import { fireEvent, render, screen, within } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { ClaimEvidencePassages } from "@/features/reference-resolution/components/claim-evidence-passages";
import type { ReferenceResolutionReportResponse } from "@/features/analysis-runs/types";

type Verification = ReferenceResolutionReportResponse["referenceResolution"]["entries"][number]["verificationOutcomes"][number];
type Passage = Verification["evidencePassages"][number];

const passage: Passage = {
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
  diagnosticSpans: [],
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
};

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
  humanReviews: [],
  evidencePassages: [passage],
};

function renderPassages(
  verification: Verification,
  presentation: "pipeline" | "paper-review" = "pipeline",
) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <ul>
        <ClaimEvidencePassages
          analysisRunId="run-123"
          outcome={verification}
          indexingStatus="COMPLETED"
          presentation={presentation}
        />
      </ul>
    </QueryClientProvider>,
  );
}

describe("Claim Evidence Passages", () => {
  it("explains the saved result and shows readable excerpts before full passage text", () => {
    const longText = `${passage.text} ${"Additional source context remains available in the full passage. ".repeat(8)}TAIL-END-MARKER`;
    renderPassages({ ...outcome, evidencePassages: [{ ...passage, text: longText }] });

    expect(screen.getByText(outcome.claimText)).toBeTruthy();
    expect(screen.getByText("Machine result: supported")).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Why this result" })).toBeTruthy();
    expect(screen.getByText(/strongest supporting passage outweighs any conflicting evidence/)).toBeTruthy();
    expect(screen.getByText(outcome.citationContextText)).toBeTruthy();
    expect(screen.getByText("[1]")).toBeTruthy();
    expect(screen.queryByText("Provisional rubric strength")).toBeNull();
    expect(screen.queryByText("Judgement confidence")).toBeNull();
    expect(screen.queryByText("Provider mock · model mock-v1 · version v1")).toBeNull();

    const passageTrigger = screen.getByRole("button", { name: /Evidence Passage · fused rank 1/ });
    expect(within(passageTrigger).getByText(/The intervention group showed a sustained improvement/)).toBeTruthy();
    expect(within(passageTrigger).queryByText(/TAIL-END-MARKER/)).toBeNull();
    expect(screen.queryByText(longText)).toBeNull();

    fireEvent.click(passageTrigger);
    expect(screen.getByText(longText)).toBeTruthy();
    expect(screen.getByText("Section 1 · Results · paragraphs 3–4")).toBeTruthy();
  });

  it("keeps Paper Review provenance available once while leaving per-passage scores out", () => {
    const secondPassage: Passage = {
      ...passage,
      id: "passage-2",
      fusedRank: 2,
      vectorRank: 3,
      evidenceJudgement: { ...passage.evidenceJudgement!, judgement: "PARTIAL_SUPPORT" },
    };
    renderPassages({ ...outcome, evidencePassages: [passage, secondPassage] }, "paper-review");

    expect(screen.queryByRole("region", { name: "System One results" })).toBeNull();
    const provenanceTrigger = screen.getByRole("button", { name: /Provider and retrieval details/ });
    expect(screen.queryByText("mock · mock-v1 · v1")).toBeNull();
    fireEvent.click(provenanceTrigger);

    expect(screen.getAllByText("mock · mock-v1 · v1")).toHaveLength(1);
    expect(screen.getByText(/postgres-hybrid-rrf-v1 · vector top 10 · lexical top 10 · final 5 · RRF 60/)).toBeTruthy();
    expect(screen.getByText(/local · feature-hash-384-v1 · v1 · 384 dimensions/)).toBeTruthy();
    expect(screen.queryByText("Judgement confidence")).toBeNull();
    expect(screen.queryByText("Provisional rubric strength")).toBeNull();

    const evidenceList = screen.getByRole("list", { name: /Evidence Passages for Atomic Claim/ });
    expect(within(evidenceList).getByRole("button", { name: /fused rank 1/ })).toBeTruthy();
    expect(within(evidenceList).getByRole("button", { name: /fused rank 2/ })).toBeTruthy();
    fireEvent.click(within(evidenceList).getByRole("button", { name: /fused rank 2/ }));
    expect(screen.getByRole("button", { name: /Passage retrieval details/ })).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /Passage retrieval details/ }));
    expect(screen.getByText("Vector 3 · lexical 1 · fused 2")).toBeTruthy();
    expect(screen.getAllByText("mock · mock-v1 · v1")).toHaveLength(1);
  });

  it("keeps human review after the evidence list and separate from the machine explanation", () => {
    renderPassages(outcome);

    const evidenceList = screen.getByRole("list", { name: /Evidence Passages for Atomic Claim/ });
    const humanReview = screen.getByRole("region", { name: "Human review history" });
    expect(evidenceList.compareDocumentPosition(humanReview) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByText(/Human assessments are separate and do not change it/)).toBeTruthy();
    expect(screen.getByText("No human reviews recorded.")).toBeTruthy();
  });

  it("explains partial support and contradiction in plain language", () => {
    const partial = renderPassages({ ...outcome, finalStatus: "PARTIALLY_SUPPORTED" });
    expect(screen.getByText(/Some evidence supports part of the claim/)).toBeTruthy();
    partial.unmount();

    renderPassages({ ...outcome, finalStatus: "CONTRADICTED" });
    expect(screen.getByText(/strongest conflicting evidence outweighs any support/)).toBeTruthy();
  });

  it("explains comparable conflict and keeps support and contradiction passages ordered", () => {
    const conflict: Verification = {
      ...outcome,
      finalStatus: "INSUFFICIENT_EVIDENCE",
      evidenceConflict: true,
      evidencePassages: [
        passage,
        {
          ...passage,
          id: "passage-2",
          text: "The later study found no improvement in the measured outcome.",
          fusedRank: 2,
          evidenceJudgement: { ...passage.evidenceJudgement!, judgement: "CONTRADICTS" },
        },
      ],
    };

    renderPassages(conflict);

    expect(screen.getByText("Support and contradiction are comparably strong")).toBeTruthy();
    expect(screen.getByText(/Supporting and conflicting evidence are similarly strong/)).toBeTruthy();
    const evidenceList = screen.getByRole("list", { name: /Evidence Passages for Atomic Claim/ });
    const buttons = within(evidenceList).getAllByRole("button");
    expect(buttons[0].textContent).toContain("fused rank 1");
    expect(buttons[1].textContent).toContain("fused rank 2");
  });

  it("highlights only exact persisted span offsets in the parent passage", () => {
    const span = {
      id: "span-1",
      spanIndex: 0,
      coreStartOffset: 13,
      coreEndOffset: 20,
      contextStartOffset: 13,
      contextEndOffset: 20,
      coreText: "Anchor.",
      contextText: "Anchor.",
      tokenCounts: [900, 910, 920, 930, 940, 950],
      status: "COMPLETED" as const,
      failureReason: null,
      providerId: "mock",
      modelId: "mock-v1",
      providerVersion: "v1",
      judgementRubricVersion: "rubric-v1",
      splittingPolicyVersion: "sentence-v1",
      evidenceJudgement: null,
    };
    const repeatedText = "Anchor. Gap. Anchor.";
    const repeated = renderPassages({
      ...outcome,
      evidencePassages: [{ ...passage, text: repeatedText, evidenceJudgement: null, diagnosticSpans: [span] }],
    });

    fireEvent.click(screen.getByRole("button", { name: /span diagnostics · no parent judgement/ }));
    const repeatedMark = repeated.container.querySelector("mark");
    expect(repeatedMark?.textContent).toBe("Anchor.");
    expect(repeatedMark?.previousSibling?.textContent).toBe("Anchor. Gap. ");
    repeated.unmount();

    const mismatched = renderPassages({
      ...outcome,
      evidencePassages: [{
        ...passage,
        text: "Anchor. Gap.",
        evidenceJudgement: null,
        diagnosticSpans: [{
          ...span,
          coreStartOffset: 0,
          coreEndOffset: 4,
          contextStartOffset: 0,
          contextEndOffset: 4,
          coreText: "Gap.",
          contextText: "Gap.",
        }],
      }],
    });

    fireEvent.click(screen.getByRole("button", { name: /span diagnostics · no parent judgement/ }));
    expect(mismatched.container.querySelector("mark")).toBeNull();
  });

  it("keeps diagnostic spans distinct and highlights only exact persisted source spans", () => {
    const diagnosticSpan = {
      id: "span-1",
      spanIndex: 0,
      coreStartOffset: 0,
      coreEndOffset: 16,
      contextStartOffset: 0,
      contextEndOffset: 16,
      coreText: "The intervention",
      contextText: "The intervention",
      tokenCounts: [900, 910, 920, 930, 940, 950],
      status: "COMPLETED" as const,
      failureReason: null,
      providerId: "mock",
      modelId: "mock-v1",
      providerVersion: "v1",
      judgementRubricVersion: "weighted-evidence-role-scope-design-v1",
      splittingPolicyVersion: "sentence-v1",
      evidenceJudgement: passage.evidenceJudgement,
    };
    const diagnostic: Verification = {
      ...outcome,
      processingStatus: "INCOMPLETE",
      processingFailureReason: "SYSTEM_ONE_INCOMPLETE",
      finalStatus: null,
      aggregatorVersion: null,
      evidencePassages: [{ ...passage, evidenceJudgement: null, diagnosticSpans: [diagnosticSpan] }],
    };

    const { container } = renderPassages(diagnostic);
    expect(screen.getByText("Machine result unavailable · incomplete pair")).toBeTruthy();
    expect(screen.getByText(/Verification did not finish, so no final machine result was assigned/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: /span diagnostics · no parent judgement/ }));

    const marks = container.querySelectorAll("mark");
    expect([...marks].map((mark) => mark.textContent)).toContain("The intervention");
    const diagnostics = screen.getByRole("region", { name: /Diagnostic sentence spans for Evidence Passage passage-1/ });
    expect(within(diagnostics).getByText(/not combined into a parent Evidence Judgement or final Claim–Paper status/)).toBeTruthy();
    fireEvent.click(within(diagnostics).getByRole("button", { name: "View diagnostic span details" }));
    expect(within(diagnostics).getByText("The intervention")).toBeTruthy();
    expect(within(diagnostics).queryByText(/provisional rubric strength/i)).toBeNull();
    expect(within(diagnostics).queryByText(/token counts/i)).toBeNull();
  });

  it("explains an unjudged retrieved passage when a completed pair has no passage judgement", () => {
    renderPassages({
      ...outcome,
      finalStatus: "INSUFFICIENT_EVIDENCE",
      evidencePassages: [{ ...passage, evidenceJudgement: null }],
    });

    expect(screen.getByText("A machine result is saved, but no passage-level judgement is available to explain it.")).toBeTruthy();
    expect(screen.getByText("No System One Evidence Judgement was recorded for this Claim–Reference pair.")).toBeTruthy();
  });

  it("explains inaccessible results without claiming a passage judgement exists", () => {
    const inaccessible: Verification = {
      ...outcome,
      finalStatus: "INACCESSIBLE",
      aggregatorVersion: null,
      evidencePassages: [],
    };

    renderPassages(inaccessible, "paper-review");

    expect(screen.getByText("No usable cited-paper evidence was available to assess this claim.")).toBeTruthy();
    expect(screen.queryByText("No System One Evidence Judgement was recorded for this Claim–Reference pair.")).toBeNull();
    expect(screen.queryByRole("button", { name: /Provider and retrieval details/ })).toBeNull();
  });

  it("explains pending pairs without repeating the missing-judgement message", () => {
    const pending: Verification = {
      ...outcome,
      processingStatus: "PENDING",
      finalStatus: null,
      aggregatorVersion: null,
      evidencePassages: [{ ...passage, evidenceJudgement: null }],
    };

    renderPassages(pending);

    expect(screen.getByText("No result yet. This pair is waiting for a saved machine assessment.")).toBeTruthy();
    expect(screen.queryByText("No System One Evidence Judgement was recorded for this Claim–Reference pair.")).toBeNull();
  });

  it("explains an incomplete provider failure without inventing a final status", () => {
    const incomplete: Verification = {
      ...outcome,
      processingStatus: "INCOMPLETE",
      processingFailureReason: "SYSTEM_ONE_CONTEXT_LIMIT_EXCEEDED",
      finalStatus: null,
      evidencePassages: [],
    };

    renderPassages(incomplete);

    expect(screen.getByText("Machine result unavailable · incomplete pair")).toBeTruthy();
    expect(screen.getByText("Verification did not finish, so no final machine result was assigned.")).toBeTruthy();
    expect(screen.getByText(/The complete Laya request exceeded the 1,024-token context limit/)).toBeTruthy();
    expect(screen.queryByText("supported")).toBeNull();
  });
});
