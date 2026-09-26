import assert from "node:assert/strict";
import test from "node:test";
import { formatConfidenceThreshold } from "../features/reference-resolution/format-confidence-threshold.ts";

test("preserves the configured threshold precision for run provenance", () => {
  assert.equal(formatConfidenceThreshold(0.905), "0.905");
  assert.equal(formatConfidenceThreshold(0.904), "0.904");
});

test("formats configured boundary values without adding misleading precision", () => {
  assert.equal(formatConfidenceThreshold(0), "0");
  assert.equal(formatConfidenceThreshold(1), "1");
});

test("identifies an unconfigured threshold", () => {
  assert.equal(formatConfidenceThreshold(null), "Not configured");
});
