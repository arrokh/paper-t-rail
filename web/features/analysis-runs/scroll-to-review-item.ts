const REVIEW_ITEM_SCROLL_DURATION_MS = 1800;
const REVIEW_ITEM_FOCUS_DURATION_MS = 3200;
const REVIEW_ITEM_FOCUS_CLASS = "analysis-run-review-item-focus";

function easeInOutCubic(progress: number): number {
  return progress < 0.5
    ? 4 * progress ** 3
    : 1 - ((-2 * progress + 2) ** 3) / 2;
}

function scrollReviewItemIntoView(element: HTMLElement): () => void {
  const scrollViewport = element.closest<HTMLElement>("[data-review-items-viewport]");
  const hasScrollableViewport = scrollViewport
    && scrollViewport.scrollHeight > scrollViewport.clientHeight
    && /auto|scroll/.test(window.getComputedStyle(scrollViewport).overflowY);
  const itemBounds = element.getBoundingClientRect();
  const startScroll = hasScrollableViewport ? scrollViewport.scrollTop : window.scrollY;
  const targetScroll = hasScrollableViewport
    ? scrollViewport.scrollTop + itemBounds.top - scrollViewport.getBoundingClientRect().top - (scrollViewport.clientHeight - itemBounds.height) / 2
    : window.scrollY + itemBounds.top - (window.innerHeight - itemBounds.height) / 2;
  const maxScroll = hasScrollableViewport
    ? scrollViewport.scrollHeight - scrollViewport.clientHeight
    : document.documentElement.scrollHeight - window.innerHeight;
  const endScroll = Math.max(0, Math.min(targetScroll, maxScroll));

  let animationFrame = 0;
  let cancelled = false;
  let focusTimer = 0;

  const clearFocus = () => {
    window.clearTimeout(focusTimer);
    element.classList.remove(REVIEW_ITEM_FOCUS_CLASS);
  };

  const stopScroll = () => {
    if (cancelled) return;
    cancelled = true;
    window.cancelAnimationFrame(animationFrame);
    window.removeEventListener("wheel", cancel);
    window.removeEventListener("touchstart", cancel);
  };

  const cancel = () => {
    stopScroll();
    clearFocus();
  };

  element.classList.remove(REVIEW_ITEM_FOCUS_CLASS);
  void element.offsetWidth;
  element.classList.add(REVIEW_ITEM_FOCUS_CLASS);
  focusTimer = window.setTimeout(clearFocus, REVIEW_ITEM_FOCUS_DURATION_MS);

  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    element.scrollIntoView({ behavior: "auto", block: "center" });
    return cancel;
  }

  const setScroll = (top: number) => {
    if (hasScrollableViewport) scrollViewport.scrollTop = top;
    else window.scrollTo({ top, behavior: "instant" });
  };

  const scrollDistance = endScroll - startScroll;
  if (Math.abs(scrollDistance) < 1) return cancel;

  const startTime = performance.now();
  const animate = (now: number) => {
    if (cancelled) return;

    const progress = Math.min((now - startTime) / REVIEW_ITEM_SCROLL_DURATION_MS, 1);
    setScroll(startScroll + scrollDistance * easeInOutCubic(progress));

    if (progress < 1) animationFrame = window.requestAnimationFrame(animate);
    else stopScroll();
  };

  window.addEventListener("wheel", cancel, { passive: true, once: true });
  window.addEventListener("touchstart", cancel, { passive: true, once: true });
  animationFrame = window.requestAnimationFrame(animate);
  return cancel;
}

export function scrollToReviewItem(element: HTMLElement | null): () => void {
  if (!element) return () => {};
  return scrollReviewItemIntoView(element);
}
