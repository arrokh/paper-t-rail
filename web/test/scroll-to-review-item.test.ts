import { afterEach, describe, expect, it, vi } from "vitest";
import { scrollToReviewItem } from "@/features/analysis-runs/scroll-to-review-item";

afterEach(() => {
  vi.restoreAllMocks();
});

describe("scrollToReviewItem", () => {
  it("aligns a review item with the top edge of its scroll viewport after the filters", () => {
    const viewport = document.createElement("div");
    viewport.dataset.reviewItemsViewport = "";
    viewport.style.overflowY = "auto";
    Object.defineProperties(viewport, {
      clientHeight: { configurable: true, value: 400 },
      scrollHeight: { configurable: true, value: 1_200 },
      scrollTop: { configurable: true, writable: true, value: 50 },
    });
    viewport.getBoundingClientRect = () => ({ top: 100 } as DOMRect);

    const filters = document.createElement("div");
    filters.setAttribute("aria-label", "Selected status filters");
    const target = document.createElement("button");
    target.getBoundingClientRect = () => ({ top: 400, height: 40 } as DOMRect);
    viewport.append(filters, target);
    document.body.append(viewport);

    vi.spyOn(window, "requestAnimationFrame").mockImplementation((callback) => {
      callback(performance.now() + 2_000);
      return 1;
    });

    const cancelScroll = scrollToReviewItem(target);

    expect(viewport.scrollTop).toBe(350);
    cancelScroll();
  });
});
