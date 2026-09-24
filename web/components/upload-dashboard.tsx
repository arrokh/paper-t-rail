"use client";

import { FormEvent, useCallback, useEffect, useMemo, useState } from "react";
import type { AnalysisRun, ApiError, CreatedRun, ParsedDocument } from "@/lib/types";
import {
  consentRequirements,
  createRunConfiguration,
  missingConsents,
  type ProviderDirectory,
  type ProviderRole,
  type ProviderSelections,
} from "@/lib/provider-configuration";

const DEFAULT_SELECTIONS: ProviderSelections = {
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

function isParsedDocumentReady(status: AnalysisRun["status"] | undefined): boolean {
  return status === "PARSED" || status === "COMPLETED" || status === "COMPLETED_WITH_WARNINGS";
}

export function UploadDashboard() {
  const [runs, setRuns] = useState<AnalysisRun[]>([]);
  const [providerDirectory, setProviderDirectory] = useState<ProviderDirectory | null>(null);
  const [providerSelections, setProviderSelections] = useState<ProviderSelections>(DEFAULT_SELECTIONS);
  const [approvedCategories, setApprovedCategories] = useState<Record<string, string[]>>({});
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [providerError, setProviderError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [parsedDocumentResult, setParsedDocumentResult] = useState<
    { runId: string; document: ParsedDocument } | { runId: string; error: string } | null
  >(null);

  const selectedRun = useMemo(() => runs.find((run) => run.id === selectedRunId) ?? null, [runs, selectedRunId]);
  const selectedRunStatus = selectedRun?.status;
  const parsedDocumentResultForSelection = parsedDocumentResult?.runId === selectedRunId ? parsedDocumentResult : null;
  const parsedDocument = parsedDocumentResultForSelection && "document" in parsedDocumentResultForSelection
    ? parsedDocumentResultForSelection.document
    : null;
  const parsedDocumentError = parsedDocumentResultForSelection && "error" in parsedDocumentResultForSelection
    ? parsedDocumentResultForSelection.error
    : null;
  const parsedDocumentLoading = Boolean(
    selectedRun && isParsedDocumentReady(selectedRunStatus) && !parsedDocumentResultForSelection,
  );
  const consentRequirementsForRun = useMemo(
    () => providerDirectory ? consentRequirements(providerDirectory, providerSelections) : [],
    [providerDirectory, providerSelections],
  );

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
    const controller = new AbortController();
    void fetch("/api/v1/providers", { cache: "no-store", signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error(await readError(response));
        const directory = (await response.json()) as ProviderDirectory;
        const roles: ProviderRole[] = ["claimExtractor", "embedding", "systemOne"];
        if (roles.some((role) => !directory.providers[role]?.length)) {
          throw new Error("The API has no enabled provider for one or more Analysis Run stages.");
        }
        const selectAvailable = (role: ProviderRole, current: string) =>
          directory.providers[role]?.find((provider) => provider.providerId === current)?.providerId
          ?? directory.providers[role]?.[0]?.providerId
          ?? current;
        setProviderDirectory(directory);
        setProviderSelections((current) => ({
          claimExtractorProvider: selectAvailable("claimExtractor", current.claimExtractorProvider),
          embeddingProvider: selectAvailable("embedding", current.embeddingProvider),
          systemOneProvider: selectAvailable("systemOne", current.systemOneProvider),
        }));
        setProviderError(null);
      })
      .catch((cause: unknown) => {
        if (cause instanceof DOMException && cause.name === "AbortError") return;
        setProviderError(cause instanceof Error ? cause.message : "Could not load available providers.");
      });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    const initialLoad = window.setTimeout(() => void refreshRuns(), 0);
    const interval = window.setInterval(() => void refreshRuns(), 2500);
    return () => {
      window.clearTimeout(initialLoad);
      window.clearInterval(interval);
    };
  }, [refreshRuns]);

  useEffect(() => {
    if (!selectedRunId || !isParsedDocumentReady(selectedRunStatus)) return;

    let active = true;
    void fetch(`/api/v1/analysis-runs/${encodeURIComponent(selectedRunId)}/parsed-document`, { cache: "no-store" })
      .then(async (response) => {
        if (!response.ok) throw new Error(await readError(response));
        return (await response.json()) as ParsedDocument;
      })
      .then((document) => { if (active) setParsedDocumentResult({ runId: selectedRunId, document }); })
      .catch((cause: unknown) => {
        if (active) setParsedDocumentResult({
          runId: selectedRunId,
          error: cause instanceof Error ? cause.message : "Could not load the parsed document.",
        });
      });

    return () => { active = false; };
  }, [selectedRunId, selectedRunStatus]);

  function runConfiguration() {
    if (!providerDirectory) throw new Error("Available providers have not loaded yet.");
    if (missingConsents(consentRequirementsForRun, approvedCategories).length > 0) {
      throw new Error("Approve every disclosed data category for each selected external provider, or choose a local provider.");
    }
    return createRunConfiguration(providerSelections, consentRequirementsForRun, approvedCategories);
  }

  function selectProvider(role: ProviderRole, providerId: string) {
    setProviderSelections((current) => ({
      ...current,
      claimExtractorProvider: role === "claimExtractor" ? providerId : current.claimExtractorProvider,
      embeddingProvider: role === "embedding" ? providerId : current.embeddingProvider,
      systemOneProvider: role === "systemOne" ? providerId : current.systemOneProvider,
    }));
  }

  function approveCategory(providerId: string, category: string, approved: boolean) {
    setApprovedCategories((current) => {
      const existing = new Set(current[providerId] ?? []);
      if (approved) existing.add(category);
      else existing.delete(category);
      return { ...current, [providerId]: [...existing] };
    });
  }

  function providerOptions(role: ProviderRole) {
    return providerDirectory?.providers[role] ?? [];
  }

  async function startRun(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = event.currentTarget;
    const fileInput = form.elements.namedItem("file");
    if (!(fileInput instanceof HTMLInputElement) || !fileInput.files?.[0]) {
      setError("Choose an English, text-based PDF to continue.");
      return;
    }

    let configuration;
    try {
      configuration = runConfiguration();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "Review provider consent before continuing.");
      return;
    }
    const data = new FormData();
    data.append("file", fileInput.files[0]);
    data.append("configuration", JSON.stringify(configuration));
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
      setApprovedCategories({});
      setBusy(false);
    }
  }

  async function reanalyze() {
    if (!selectedRun) return;
    setBusy(true);
    setError(null);
    try {
      const configuration = runConfiguration();
      const response = await fetch(`/api/v1/documents/${encodeURIComponent(selectedRun.documentId)}/analysis-runs`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(configuration),
      });
      if (!response.ok) throw new Error(await readError(response));
      const created = (await response.json()) as CreatedRun;
      setSelectedRunId(created.analysisRunId);
      await refreshRuns();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "A new Analysis Run could not be created.");
    } finally {
      setApprovedCategories({});
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
            <div className="provider-selection-grid" aria-label="Analysis provider selection">
              {([
                ["claimExtractor", "Claim extraction", "claimExtractorProvider"],
                ["embedding", "Embeddings", "embeddingProvider"],
                ["systemOne", "Evidence assessment", "systemOneProvider"],
              ] as const).map(([role, label, selectionField]) => (
                <label className="provider-select" key={role}>
                  <span>{label}</span>
                  <select
                    value={providerSelections[selectionField]}
                    disabled={busy || !providerDirectory}
                    onChange={(event) => selectProvider(role, event.target.value)}
                  >
                    {providerOptions(role).map((provider) => (
                      <option key={provider.providerId} value={provider.providerId}>{provider.displayName}</option>
                    ))}
                  </select>
                </label>
              ))}
            </div>
            {consentRequirementsForRun.length === 0 ? (
              <div className="provider-consent local-only" aria-live="polite">
                <strong>Local/mock providers selected</strong>
                <p>This run needs no external consent and sends no document content to an external provider.</p>
              </div>
            ) : consentRequirementsForRun.map((provider) => (
              <section className="provider-consent" key={provider.providerId} aria-labelledby={`consent-${provider.providerId}`}>
                <h3 id={`consent-${provider.providerId}`}>{provider.displayName} data access</h3>
                <p>This external provider may receive only the following request categories for this run:</p>
                {provider.retentionDisclosure && <p className="retention-disclosure">{provider.retentionDisclosure}</p>}
                <fieldset>
                  <legend>Approve each category to continue</legend>
                  {provider.dataCategories.map((categoryId) => {
                    const category = providerDirectory?.dataCategories.find((item) => item.id === categoryId);
                    return (
                      <label className="consent-category" key={categoryId}>
                        <input
                          type="checkbox"
                          disabled={busy}
                          checked={approvedCategories[provider.providerId]?.includes(categoryId) ?? false}
                          onChange={(event) => approveCategory(provider.providerId, categoryId, event.target.checked)}
                        />
                        <span><strong>{category?.label ?? categoryId}</strong><small><code>{categoryId}</code> · {category?.description}</small></span>
                      </label>
                    );
                  })}
                </fieldset>
                <p className="consent-note">Consent applies only to this Analysis Run. It does not change previous runs or authorize additional categories.</p>
              </section>
            ))}
            <details className="data-category-reference">
              <summary>All stable data categories</summary>
              <ul>
                {providerDirectory?.dataCategories.map((category) => (
                  <li key={category.id}><strong>{category.id}</strong> — {category.description}</li>
                ))}
              </ul>
            </details>
            <label className="file-drop" htmlFor="source-file">
              <span className="upload-icon" aria-hidden="true">↑</span>
              <span className="file-drop-title">Choose a PDF</span>
              <span className="file-drop-caption">PDF only · content is checked before storage</span>
            </label>
            <input id="source-file" name="file" type="file" accept="application/pdf,.pdf" required />
            <button className="primary-button" type="submit" disabled={busy || !providerDirectory}>
              {busy ? "Starting run…" : "Upload & start Analysis Run"}
              <span aria-hidden="true">↗</span>
            </button>
          </form>
          <div className="privacy-callout">
            <span className="lock-icon" aria-hidden="true">▣</span>
            <p><strong>Local/mock providers are the default.</strong> Disabled or unclassified providers are not offered for selection. External providers require explicit approval of every disclosed category for each run. Upload limits are configurable; over-limit files are rejected, never trimmed.</p>
          </div>
          {(error || providerError) && <div className="error-banner" role="alert"><strong>Could not continue</strong><span>{error ?? providerError}</span></div>}
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
                <div role="listitem" key={run.id}>
                  <button
                    type="button"
                    className={`run-row ${run.id === selectedRunId ? "selected" : ""}`}
                    onClick={() => setSelectedRunId(run.id)}
                  >
                    <span className={`run-status-dot ${run.status.toLowerCase()}`} aria-hidden="true" />
                    <span className="run-row-main"><strong>{run.filename}</strong><small>{new Date(run.createdAt).toLocaleString()}</small></span>
                    <span className={`status-pill ${run.status.toLowerCase()}`}>{statusLabel(run.status)}</span>
                  </button>
                </div>
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
              <button type="button" className="secondary-button" disabled={busy || !providerDirectory} onClick={reanalyze}>Create a new run from this document <span aria-hidden="true">↗</span></button>
            </div>
          )}
          {selectedRun && parsedDocumentLoading && <p className="parsed-state" role="status">Loading parsed document structure…</p>}
          {selectedRun && parsedDocumentError && <p className="error-detail" role="alert">{parsedDocumentError}</p>}
          {selectedRun && parsedDocument && (
            <section className="parsed-document" aria-labelledby="parsed-document-heading">
              <div className="parsed-heading-row">
                <div>
                  <div className="card-kicker">PARSED DOCUMENT</div>
                  <h3 id="parsed-document-heading">Sections and references</h3>
                </div>
                <span className="parser-badge">{parsedDocument.parser.provider} {parsedDocument.parser.version}</span>
              </div>
              <p className="parsed-offset-note">Source offsets are zero-based and end-exclusive UTF-16 indexes in the normalized source text.</p>

              <section className="parsed-group" aria-labelledby="sections-heading">
                <h4 id="sections-heading">Sections <span>{parsedDocument.sections.length}</span></h4>
                {parsedDocument.sections.length === 0 ? <p className="parsed-empty">No sections were returned by the parser.</p> : (
                  <ol className="parsed-sections">
                    {parsedDocument.sections.map((section) => (
                      <li key={section.id}>
                        <details>
                          <summary>
                            <strong>{section.heading || `Section ${section.sectionOrder + 1}`}</strong>
                            <span>{section.startOffset}–{section.endOffset}</span>
                          </summary>
                          <p className="parsed-section-text">{section.text}</p>
                        </details>
                      </li>
                    ))}
                  </ol>
                )}
              </section>

              <section className="parsed-group" aria-labelledby="contexts-heading">
                <h4 id="contexts-heading">Citation Contexts <span>{parsedDocument.citationContexts.length}</span></h4>
                {parsedDocument.citationContexts.length === 0 ? <p className="parsed-empty">No citation markers were detected.</p> : (
                  <ol className="parsed-contexts">
                    {parsedDocument.citationContexts.map((context) => (
                      <li key={context.id}>
                        <article>
                          <div className="context-meta"><span>{context.boundaryKind.replaceAll("_", " ").toLowerCase()}</span><span>{context.startOffset}–{context.endOffset}</span></div>
                          <p>{context.text}</p>
                          <ul className="parsed-markers">
                            {context.occurrences.map((occurrence) => (
                              <li key={occurrence.id}>
                                <code>{occurrence.markerText}</code> <span>{occurrence.startOffset}–{occurrence.endOffset}</span>
                                {occurrence.bibliographyReferenceKeys.length > 0 && (
                                  <span className="citation-targets"> → {occurrence.bibliographyReferenceKeys.map((key) => (
                                    <a key={key} href={`#bibliography-${key}`}>{key}</a>
                                  ))}</span>
                                )}
                              </li>
                            ))}
                          </ul>
                        </article>
                      </li>
                    ))}
                  </ol>
                )}
              </section>

              <section className="parsed-group" aria-labelledby="bibliography-heading">
                <h4 id="bibliography-heading">Bibliography Entries <span>{parsedDocument.bibliographyEntries.length}</span></h4>
                {parsedDocument.bibliographyEntries.length === 0 ? <p className="parsed-empty">No bibliography entries were detected.</p> : (
                  <ol className="parsed-bibliography">
                    {parsedDocument.bibliographyEntries.map((entry) => (
                      <li key={entry.localReferenceKey} id={`bibliography-${entry.localReferenceKey}`}>
                        <strong>{entry.title || entry.localReferenceKey}</strong>
                        <span className="reference-key">{entry.localReferenceKey} · {entry.referenceType.toLowerCase().replaceAll("_", " ")}{entry.year ? ` · ${entry.year}` : ""}</span>
                        {entry.authors.length > 0 && <span className="reference-authors">{entry.authors.join(", ")}</span>}
                        <p>{entry.rawText}</p>
                        {entry.doi && <small>DOI: {entry.doi}</small>}
                      </li>
                    ))}
                  </ol>
                )}
              </section>
            </section>
          )}
        </section>
      </div>
    </section>
  );
}
