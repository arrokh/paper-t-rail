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

To stop new recovery writes, set `PAPER_RECOVERY_SUBMISSIONS_ENABLED=false` in the API deployment configuration and roll/restart every API replica. No database migration or worker contract change is required. Confirm each replica has loaded the disabled configuration and that the load balancer no longer routes to an older enabled replica before considering the gate closed: during a mixed rollout, any enabled replica can still accept submissions. Refresh the rights declaration response to observe the capability; it is `Cache-Control: no-store`.

The switch prevents new gated writes but cannot revoke a presigned PUT URL already issued. Keep the configured `PAPER_RECOVERY_UPLOAD_URL_TTL_SECONDS` in mind, reject finalization while disabled, allow that URL TTL and the configured in-flight transfer grace to elapse, and rely on the existing cleanup scheduler and durable tombstones for staged objects. Do not manually bypass cleanup or claim that in-flight writes are revoked. Do not log or copy signed URLs, filenames, file/content data, or request bodies while operating the rollback. Existing cleanup and already-submitted worker processing continue unchanged.
