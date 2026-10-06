function scrollIntoViewAndWaitForCompletion(element: HTMLElement): Promise<void> {
  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
  const scrollMarginTop = Number.parseFloat(window.getComputedStyle(element).scrollMarginTop) || 0;
  const targetScrollTop = Math.max(0, element.getBoundingClientRect().top + window.scrollY - scrollMarginTop);
  const alreadyAtTarget = Math.abs(targetScrollTop - window.scrollY) < 1;

  if (reducedMotion || alreadyAtTarget) {
    element.scrollIntoView({ behavior: "instant", block: "start" });
    return Promise.resolve();
  }

  return new Promise((resolve) => {
    let idleTimeout = 0;
    let fallbackTimeout = 0;
    let settled = false;

    const finish = () => {
      if (settled) return;
      settled = true;
      window.removeEventListener("scroll", handleScroll);
      window.clearTimeout(idleTimeout);
      window.clearTimeout(fallbackTimeout);
      resolve();
    };

    const handleScroll = () => {
      window.clearTimeout(idleTimeout);
      idleTimeout = window.setTimeout(finish, 120);
    };

    window.addEventListener("scroll", handleScroll, { passive: true });
    fallbackTimeout = window.setTimeout(finish, 2000);
    element.scrollIntoView({ behavior: "smooth", block: "start" });
  });
}

export function scrollToPipelineStageNavigation(): Promise<void> {
  const pipelineStages = document.getElementById("analysis-pipeline-stages");
  return pipelineStages ? scrollIntoViewAndWaitForCompletion(pipelineStages) : Promise.resolve();
}
