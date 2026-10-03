// In an isolated Chromium agent-browser session, select Show in PDF, then run:
// AGENT_BROWSER_ENGINE=chrome agent-browser --session <session> eval --stdin < web/test/pdf-text-layer.browser.js
// Compare rendered text metrics against an independent parse of the original PDF.
(async () => {
  const viewer = document.querySelector(".source-document-pdf-page");
  const canvas = viewer?.querySelector("canvas");
  const layer = viewer?.querySelector(".textLayer");
  const spans = [...(layer?.querySelectorAll('span[role="presentation"]') ?? [])];
  const selectedSpans = spans.filter((span) => span.querySelector("[data-pdf-search-match]"));
  if (!canvas || !layer || !selectedSpans.length || !viewer.querySelector("[data-pdf-search-highlight]")) {
    throw new Error("Select Show in PDF before checking highlight geometry.");
  }

  const pageNumber = Number(viewer.getAttribute("aria-label").match(/PDF page (\d+)/)[1]);
  const loadingTask = globalThis.pdfjsLib.getDocument({ url: document.querySelector("a[download]").href });
  try {
    const sourceDocument = await loadingTask.promise.catch(() => {
      throw new Error("Original PDF could not be loaded. Refresh the PDF link and retry.");
    });
    const sourcePage = await sourceDocument.getPage(pageNumber);
    const viewport = sourcePage.getViewport({ scale: 1 });
    const scale = canvas.getBoundingClientRect().width / viewport.width * viewport.userUnit;
    const sourceContent = await sourcePage.getTextContent();
    const sourceItems = sourceContent.items.filter((item) => typeof item.str === "string" && item.str.length > 0);
    const violations = [];
    for (const span of selectedSpans) {
      const sourceItem = sourceItems[spans.indexOf(span)];
      if (!sourceItem || sourceItem.str !== span.textContent) {
        throw new Error("Selected text layer does not match original PDF text.");
      }
      const style = getComputedStyle(span);
      const transform = new DOMMatrix(style.transform === "none" ? undefined : style.transform);
      const actualFontSize = Number.parseFloat(style.fontSize) * Math.hypot(transform.c, transform.d);
      const expectedFontSize = Math.hypot(sourceItem.transform[2], sourceItem.transform[3]) * scale;
      if (Math.abs(actualFontSize - expectedFontSize) > 0.1) {
        violations.push({ expectedFontSize, actualFontSize });
      }

      const angle = Math.atan2(sourceItem.transform[1], sourceItem.transform[0]) + viewport.rotation * Math.PI / 180;
      const horizontal = Math.abs(Math.sin(angle)) < 0.00001;
      const vertical = Math.abs(Math.cos(angle)) < 0.00001;
      if (horizontal || vertical) {
        const rect = span.getBoundingClientRect();
        const actualExtent = horizontal ? rect.width : rect.height;
        const expectedExtent = sourceItem.width * scale;
        if (Math.abs(actualExtent - expectedExtent) > 1) {
          violations.push({ expectedExtent, actualExtent });
        }
      }
      for (const element of [span, ...span.querySelectorAll("*")]) {
        if (getComputedStyle(element).color !== "rgba(0, 0, 0, 0)") {
          violations.push({ visibleDuplicateText: true });
        }
      }
    }
    if (violations.length) throw new Error(JSON.stringify({ pageNumber, scale, violations }));
    return { passed: true, pageNumber, scale, checkedSourceTextRuns: selectedSpans.length };
  } finally {
    await loadingTask.destroy();
  }
})()
