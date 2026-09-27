import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { HumanReviewPanel } from "@/features/reference-resolution/components/human-review-panel";
import type { HumanReview } from "@/features/analysis-runs/types";

function renderPanel({ reviews = [] }: { reviews?: HumanReview[] } = {}) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <HumanReviewPanel
        analysisRunId="run-123"
        verificationId="verification-123"
        machineStatus="INSUFFICIENT_EVIDENCE"
        reviews={reviews}
      />
    </QueryClientProvider>,
  );
}

afterEach(() => vi.unstubAllGlobals());

describe("Human Review panel", () => {
  it("presents review history separately from the unchanged machine result", () => {
    const reviews: HumanReview[] = [{
      id: "review-1",
      analysisRunId: "run-123",
      verificationId: "verification-123",
      action: "OVERRIDE",
      overrideStatus: "SUPPORTED",
      note: "The cited Results section directly supports the claim.",
      createdAt: "2026-01-02T03:04:05Z",
    }];

    renderPanel({ reviews });

    expect(screen.getByText("Machine result: insufficient evidence. Human assessments are separate and do not change it.")).toBeTruthy();
    expect(screen.getByText("Human assessment: supported")).toBeTruthy();
    expect(screen.getByText(reviews[0].note!)).toBeTruthy();
    expect(screen.getByText(reviews[0].createdAt)).toBeTruthy();
    expect(screen.getByRole("heading", { name: "Human review history" })).toBeTruthy();
  });

  it("submits an append-only override with its separate status and note", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    const fetchMock = vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      requests.push({ url: String(input), options });
      return {
        ok: true,
        json: async () => ({
          id: "review-new",
          analysisRunId: "run-123",
          verificationId: "verification-123",
          action: "OVERRIDE",
          overrideStatus: "SUPPORTED",
          note: "My separate reading supports the claim.",
          createdAt: "2026-02-03T04:05:06Z",
        }),
      };
    });
    vi.stubGlobal("fetch", fetchMock);
    renderPanel();

    fireEvent.change(screen.getByLabelText("Review action"), { target: { value: "OVERRIDE" } });
    const submit = screen.getByRole("button", { name: "Record Human Review" });
    expect(submit.hasAttribute("disabled")).toBe(true);

    fireEvent.change(screen.getByLabelText("Human assessment status"), { target: { value: "SUPPORTED" } });
    fireEvent.change(screen.getByLabelText("Note (optional)"), { target: { value: "My separate reading supports the claim." } });
    fireEvent.click(submit);

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    const request = requests[0];
    expect(request?.url).toBe("/api/v1/verifications/verification-123/reviews");
    expect(request?.options?.method).toBe("POST");
    expect(request?.options?.headers).toEqual({ "content-type": "application/json" });
    expect(JSON.parse(String(request?.options?.body))).toEqual({
      action: "OVERRIDE",
      overrideStatus: "SUPPORTED",
      note: "My separate reading supports the claim.",
    });
    expect(screen.getByText("Machine result: insufficient evidence. Human assessments are separate and do not change it.")).toBeTruthy();
  });

  it("submits an agreement without optional override status or note", async () => {
    const requests: Array<{ url: string; options: RequestInit | undefined }> = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, options?: RequestInit) => {
      requests.push({ url: String(input), options });
      return {
        ok: true,
        json: async () => ({
          id: "review-agreement",
          analysisRunId: "run-123",
          verificationId: "verification-123",
          action: "AGREE",
          overrideStatus: null,
          note: null,
          createdAt: "2026-02-03T04:05:06Z",
        }),
      };
    }));
    renderPanel();

    fireEvent.click(screen.getByRole("button", { name: "Record Human Review" }));

    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].url).toBe("/api/v1/verifications/verification-123/reviews");
    expect(JSON.parse(String(requests[0].options?.body))).toEqual({ action: "AGREE" });
  });
});
