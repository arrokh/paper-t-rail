"use client";

import { useEffect, useState } from "react";
import { ArrowUp } from "lucide-react";
import { Button } from "@/components/ui/button";

const SHOW_AFTER_SCROLL_PX = 360;

export function BackToTopFab() {
  const [isVisible, setIsVisible] = useState(false);
  const [isFooterVisible, setIsFooterVisible] = useState(false);

  useEffect(() => {
    const updateVisibility = () => setIsVisible(window.scrollY > SHOW_AFTER_SCROLL_PX);
    updateVisibility();
    window.addEventListener("scroll", updateVisibility, { passive: true });

    const footer = document.querySelector("footer");
    const footerObserver = footer ? new IntersectionObserver(([entry]) => {
      setIsFooterVisible(entry.isIntersecting);
    }) : null;
    if (footer && footerObserver) footerObserver.observe(footer);

    return () => {
      window.removeEventListener("scroll", updateVisibility);
      footerObserver?.disconnect();
    };
  }, []);

  function scrollToTop() {
    const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    window.scrollTo({ top: 0, behavior: prefersReducedMotion ? "instant" : "smooth" });
  }

  return (
    <Button
      type="button"
      variant="default"
      size="default"
      aria-hidden={!isVisible || isFooterVisible}
      tabIndex={isVisible && !isFooterVisible ? 0 : -1}
      onClick={scrollToTop}
      className={`fixed bottom-4 right-[max(1rem,calc((100vw_-_72rem)_/_2_+_1rem))] z-50 h-11 gap-2 rounded-full px-4 text-sm font-medium shadow-lg transition-[opacity,transform] duration-200 motion-reduce:transition-none sm:bottom-6 ${
        isVisible && !isFooterVisible ? "translate-y-0 opacity-100" : "pointer-events-none translate-y-2 opacity-0"
      }`}
    >
      <ArrowUp className="size-5" aria-hidden="true" />
      <span>Back to top</span>
    </Button>
  );
}
