# Recovery submission gate

`PAPER_RECOVERY_SUBMISSIONS_ENABLED` is the authoritative server-side switch for Recovery Batch submissions. Its application default is `false`. `GET /api/v1/recovery-rights-declaration` is the existing no-store capability response; its `submissionsEnabled` boolean is the authoritative UI signal and is returned with the current policy limits. Do not infer availability from local UI state or add a separate capability endpoint.

When the switch is off, the API rejects these requests with HTTP `503`, code `RECOVERY_SUBMISSIONS_DISABLED`, and the content-free message `Recovery submissions are disabled.` before calling a feature service:

- create or retrieve a batch (`POST .../analysis-runs/{id}/recovery-batches`),
- accept rights, add DOI assistance, or create an upload intent,
- finalize or validate an upload, confirm identity, or select/replace an exact version,
- submit a successor Analysis Run.

Read-only recovery rights, batch/state, validation, history, report, and successor preview operations remain available. Explicit DOI-assistance and upload `DELETE`s and Source Document deletion remain available. Cleanup scheduling/workers and already-submitted successor worker jobs are unchanged; the gate is not a cancellation mechanism.

## Local development

The root `application.yml` default is off. The regular local Compose API opts in with `PAPER_RECOVERY_SUBMISSIONS_ENABLED=true` (also shown in `.env.example`) so local recovery flows remain available. Set it to `false` in the local environment and recreate/restart the API to exercise the disabled behavior. An unconfigured deployment outside Compose remains disabled.

## Rollback / multi-replica operation

### Disable submissions while keeping gate-aware code

`PAPER_RECOVERY_SUBMISSIONS_ENABLED` is read at API startup. It closes the gated writes only in binaries that contain `RecoverySubmissionGate`.

To stop new recovery writes while keeping a gate-aware release in service:

1. Set `PAPER_RECOVERY_SUBMISSIONS_ENABLED=false` and roll/restart every API producer. Do not route writes to an older enabled replica during the transition; any gate-aware replica still running with `true` can accept submissions.
2. Probe each replica directly. The no-store rights declaration must report `submissionsEnabled=false`, and a harmless synthetic request to each gated write must return the stable `503 RECOVERY_SUBMISSIONS_DISABLED` before feature services are called.
3. Probe the load-balancer route as well, and confirm no API producer is still enabled or running code without the gate. A response from one replica or the service VIP alone does not prove every backend is disabled.

No database migration or worker contract change is required for this flag-only shutdown. Keep existing migrations and retained history.

### Roll back to code from before this gate

A pre-gate binary does not read `PAPER_RECOVERY_SUBMISSIONS_ENABLED`; setting it to `false` has no effect on that version. Its recovery write handlers may accept requests. **Do not serve a pre-gate API on the strength of this flag or a capability response from a gate-aware replica.**

Before any pre-gate code receives traffic, drain in-flight requests and install an independent ingress/service-level deny for every gated recovery write and successor-submission method/path. Keep that deny in force for the entire time pre-gate code is reachable, or keep all such API instances offline. If the independent deny cannot be applied and verified, do not roll back to or serve the pre-gate version. Preserve safe reads, explicit removals, Source Document deletion, and background cleanup as intended; the deny is not a reason to block cleanup or delete history.

The deny must cover these methods and path patterns for this API version:

- `POST /api/v1/analysis-runs/{analysisRunId}/recovery-batches`
- `POST /api/v1/recovery-batches/{batchId}/rights-declaration`
- `PUT /api/v1/recovery-batches/{batchId}/entries/{localReferenceKey}/identifier-assistance`
- `POST /api/v1/recovery-batches/{batchId}/entries/{localReferenceKey}/uploads`
- `POST /api/v1/recovery-batches/{batchId}/uploads/{uploadId}/finalize`
- `POST /api/v1/recovery-batches/{batchId}/uploads/{uploadId}/validate`
- `POST /api/v1/recovery-batches/{batchId}/uploads/{uploadId}/validation/{attemptId}/confirm-identity`
- `POST /api/v1/recovery-batches/{batchId}/uploads/{uploadId}/select`
- `POST /api/v1/recovery-batches/{batchId}/successor`

Keep safe `GET`s, the read-only `POST /api/v1/recovery-batches/{batchId}/successor/preview`, and explicit recovery `DELETE`s outside this deny. Review this list against `RecoverySubmissionGateIntegrationTest` when gated API routes change; do not use a broad path deny that also blocks reads, previews, or removals.

For a local rollback rehearsal, use only synthetic identifiers/state and disposable services: demonstrate that a pre-gate binary ignores the false flag when reached directly, then verify the independent router/ingress deny rejects the same write routes while the old binary is present. Probe each backend and the routed path; compare the synthetic database/history/outbox/queue/storage state before and after rejected routed writes. Keep a compatible worker available for already-submitted work and verify that the predecessor/history is retained. A no-store capability response from a gate-aware instance is not evidence that a pre-gate instance is blocked.

The application flag and ingress deny do not revoke already-issued S3 presigned PUT URLs. Keep the configured `PAPER_RECOVERY_UPLOAD_URL_TTL_SECONDS` in mind, reject finalization while gate-aware code is disabled, allow that URL TTL and the configured in-flight transfer grace to elapse, and rely on the existing cleanup scheduler and durable tombstones for staged objects. Do not manually bypass cleanup or claim in-flight writes are revoked. Do not log or copy signed URLs, filenames, file/content data, or request bodies. Existing cleanup and already-submitted worker processing must continue unchanged.
