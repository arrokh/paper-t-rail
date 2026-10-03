import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { runInNewContext } from "node:vm";

const browserProbe = readFileSync(new URL("./pdf-text-layer.browser.js", import.meta.url), "utf8");

test("PDF geometry probe releases its worker and hides access details when loading fails", async () => {
  let destroyed = false;
  const loadingTask = {
    promise: Promise.reject(new Error("Failed to fetch private PDF access URL")),
    async destroy() { destroyed = true; },
  };
  const selectedSpan = { querySelector: () => ({}) };
  const layer = { querySelectorAll: () => [selectedSpan] };
  const viewer = {
    querySelector: (selector) => selector === ".textLayer" ? layer : {},
    getAttribute: () => "PDF page 3 scroll area",
  };
  const document = {
    querySelector: (selector) => selector === ".source-document-pdf-page"
      ? viewer
      : { href: "https://example.invalid/private-pdf" },
  };
  const result = runInNewContext(browserProbe, {
    document,
    pdfjsLib: { getDocument: () => loadingTask },
  });

  let failure;
  try {
    await result;
  } catch (error) {
    failure = error;
  }

  assert.equal(destroyed, true);
  assert.equal(failure?.message, "Original PDF could not be loaded. Refresh the PDF link and retry.");
});
