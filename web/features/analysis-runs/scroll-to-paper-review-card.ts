export function scrollToPaperReviewCard(): void {
  const paperReviewCard = document.getElementById("paper-review-card");
  if (!paperReviewCard) return;

  const behavior = window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "instant" : "smooth";
  paperReviewCard.scrollIntoView({ behavior, block: "start" });
}
