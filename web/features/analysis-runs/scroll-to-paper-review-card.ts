function scrollToRunViewCard(id: string): void {
  const card = document.getElementById(id);
  if (!card) return;

  const behavior = window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth";
  card.scrollIntoView({ behavior, block: "start" });
}

export function scrollToPaperReviewCard(): void {
  scrollToRunViewCard("paper-review-card");
}

export function scrollToAnalysisPipelineCard(): void {
  scrollToRunViewCard("analysis-pipeline-card");
}
