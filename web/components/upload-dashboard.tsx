"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import type { AnalysisRun, ApiError, CreatedRun } from "@/lib/types";

const DEFAULT_CONFIGURATION = {
  claimExtractorProvider: "heuristic",
  embeddingProvider: "local",
  systemOneProvider: "mock",
};

async function readError(response: Response): Promise<string> {
  try {
    const error = (await response.json()) as ApiError;
    return error.message || "The request was rejected.";
  } catch {
    return `Request failed (${response.status}).`;
  }
}

function statusLabel(status: AnalysisRun["status"]): string {
  return status.replaceAll("_", " ").toLowerCase();
}

export function UploadDashboard() {
  const [runs, setRuns] = useState<AnalysisRun[]>([]);
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const selectedRun = useMemo(() => runs.find((run) => run.id === selectedRunId) ?? null, [runs, selectedRunId]);

  const refreshRuns = useCallback(async () => {
    try {
      const response = await fetch("/api/v1/analysis-runs?limit=25", { cache: "no-store" });
      if (!response.ok) throw new Error(await readError(response));
      const currentRuns = (await response.json()) as AnalysisRun[];
      setRuns(currentRuns);
      setError(null);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Could not load saved Analysis Runs.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const initialLoad = window.setTimeout(() => void refreshRuns(), 0);
    const interval = window.setInterval(() => void refreshRuns(), 2500);
    return () => {
      window.clearTimeout(initialLoad);
      window.clearInterval(interval);
    };
  }, [refreshRuns]);

  async function startRun(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fileInput = form.elements.namedItem("file");
    if (!(fileInput instanceof HTMLInputElement) || !fileInput.files?.[0]) {
      setError("Choose an English, text-based PDF to continue.");
      return;
    }

    const data = new FormData();
    data.append("file", fileInput.files[0]);
    data.append("configuration", JSON.stringify(DEFAULT_CONFIGURATION));
    setBusy(true);
    setError(null);
    try {
      const response = await fetch("/api/v1/analysis-runs", { method: "POST", body: data });
      if (!response.ok) throw new Error(await readError(response));
      const created = (await response.json()) as CreatedRun;
      setSelectedRunId(created.analysisRunId);
      form.reset();
      await refreshRuns();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "The upload could not be processed.");
    } finally {
      setBusy(false);
    }
  }

  async function reanalyze() {
    if (!selectedRun) return;
    setBusy(true);
    setError(null);
    try {
      const response = await fetch(`/api/v1/documents/${encodeURIComponent(selectedRun.documentId)}/analysis-runs`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(DEFAULT_CONFIGURATION),
      });
      if (!response.ok) throw new Error(await readError(response));
      const created = (await response.json()) as CreatedRun;
      setSelectedRunId(created.analysisRunId);
      await refreshRuns();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "A new Analysis Run could not be created.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="workspace" aria-label="Source Document workspace">
      <div className="workspace-grid">
        <section className="upload-card" aria-labelledby="upload-heading">
          <div className="card-kicker"><span className="kicker-number">01</span> SOURCE DOCUMENT</div>
          <h2 id="upload-heading">Start with your PDF</h2>
          <p className="muted">Upload an English academic document with selectable text. Scanned PDFs and other languages are rejected with a reason.</p>
          <form onSubmit={startRun} className="upload-form">
            <label className="file-drop" htmlFor="source-file">
              <span className="upload-icon" aria-hidden="true">↑</span>
              <span className="file-drop-title">Choose a PDF</span>
              <span className="file-drop-caption">PDF only · content is checked before storage</span>
            </label>
            <input id="source-file" name="file" type="file" accept="application/pdf,.pdf" required />
            <button className="primary-button" type="submit" disabled={busy}>
              {busy ? "Starting run…" : "Upload & start Analysis Run"}
              <span aria-hidden="true">↗</span>
            </button>
          </form>
          <div className="privacy-callout">
            <span className="lock-icon" aria-hidden="true">▣</span>
            <p><strong>Local and private by default.</strong> The selected configuration uses heuristic extraction, local embeddings, and a mock verifier. Upload limits are configurable; over-limit files are rejected, never trimmed.</p>
          </div>
          {error && <div className="error-banner" role="alert"><strong>Could not continue</strong><span>{error}</span></div>}
        </section>

        <section className="runs-card" aria-labelledby="runs-heading">
          <div className="runs-heading-row">
            <div>
              <div className="card-kicker"><span className="kicker-number">02</span> PERSISTED PROGRESS</div>
              <h2 id="runs-heading">Analysis Runs</h2>
            </div>
            <span className="run-count">{runs.length.toString().padStart(2, "0")}</span>
          </div>
          {loading ? (
            <p className="empty-state">Loading saved runs…</p>
          ) : runs.length === 0 ? (
            <div className="empty-state"><span className="empty-track" aria-hidden="true">— — —</span><p>Your first Analysis Run will appear here.</p></div>
          ) : (
            <div className="run-list" role="list" aria-label="Saved Analysis Runs">
              {runs.map((run) => (
                <button
                  type="button"
                  className={`run-row ${run.id === selectedRunId ? "selected" : ""}`}
                  key={run.id}
                  onClick={() => setSelectedRunId(run.id)}
                  role="listitem"
                >
                  <span className={`run-status-dot ${run.status.toLowerCase()}`} aria-hidden="true" />
                  <span className="run-row-main"><strong>{run.filename}</strong><small>{new Date(run.createdAt).toLocaleString()}</small></span>
                  <span className={`status-pill ${run.status.toLowerCase()}`}>{statusLabel(run.status)}</span>
                </button>
              ))}
            </div>
          )}

          {selectedRun && (
            <div className="run-detail" aria-live="polite">
              <div className="detail-topline">
                <span>RUN PROGRESS</span>
                <span className={`status-pill ${selectedRun.status.toLowerCase()}`}>{statusLabel(selectedRun.status)}</span>
              </div>
              <p className="progress-message">{selectedRun.progress.message ?? (selectedRun.status === "QUEUED" ? "Waiting for a worker." : "Progress saved.")}</p>
              <dl className="provenance-list">
                <div><dt>Source SHA-256</dt><dd><code>{selectedRun.sourceContentSha256}</code></dd></div>
                <div><dt>Configuration</dt><dd>{selectedRun.configuration.claimExtractor.provider} · {selectedRun.configuration.embedding.provider} · {selectedRun.configuration.systemOne.provider}</dd></div>
                <div><dt>Worker stage</dt><dd>{selectedRun.progress.stage?.replaceAll("_", " ").toLowerCase() ?? "queued"}</dd></div>
              </dl>
              {selectedRun.status === "FAILED" && selectedRun.failureReason && <p className="error-detail">{selectedRun.failureReason}</p>}
              <button type="button" className="secondary-button" disabled={busy} onClick={reanalyze}>Create a new run from this document <span aria-hidden="true">↗</span></button>
            </div>
          )}
        </section>
      </div>
    </section>
  );
}
