export function scrollToAnchorTarget(target: HTMLElement, historyHash?: string): void {
  if (historyHash && window.location.hash !== historyHash) {
    window.history.pushState(null, "", historyHash);
  }

  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const stickyNavigation = document.querySelector<HTMLElement>("[data-sticky-step-navigation]");
  const stickyOffset = stickyNavigation?.getBoundingClientRect().height ?? 0;
  const targetTop = Math.max(
    0,
    window.scrollY + target.getBoundingClientRect().top - stickyOffset - 16,
  );
  let didScroll = false;
  let finished = false;
  let settleTimer: number | null = null;
  let noScrollTimer: number | null = null;

  const highlightTarget = () => {
    target.classList.remove("citation-target-highlight");
    void target.offsetWidth;
    target.classList.add("citation-target-highlight");
    window.setTimeout(() => target.classList.remove("citation-target-highlight"), 1_500);
  };
  const finishAfterScroll = () => {
    if (finished) return;
    finished = true;
    if (settleTimer !== null) window.clearTimeout(settleTimer);
    if (noScrollTimer !== null) window.clearTimeout(noScrollTimer);
    window.removeEventListener("scroll", handleScroll);
    document.removeEventListener("scrollend", finishAfterScroll);
    window.removeEventListener("scrollend", finishAfterScroll);
    highlightTarget();
  };
  const handleScroll = () => {
    didScroll = true;
    if (noScrollTimer !== null) window.clearTimeout(noScrollTimer);
    if (settleTimer !== null) window.clearTimeout(settleTimer);
    settleTimer = window.setTimeout(finishAfterScroll, 500);
  };

  window.addEventListener("scroll", handleScroll, { passive: true });
  document.addEventListener("scrollend", finishAfterScroll, { once: true });
  window.addEventListener("scrollend", finishAfterScroll, { once: true });
  window.scrollTo({ top: targetTop, behavior: reducedMotion ? "auto" : "smooth" });
  noScrollTimer = window.setTimeout(() => {
    if (!didScroll) finishAfterScroll();
  }, 120);
}
