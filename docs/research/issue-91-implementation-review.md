# Issue #91 implementation and verification record

**Date:** 2026-10-09
**Branch:** `feat/issue-91-identity-policy`
**State at the original implementation handoff:** The worktree was uncommitted and no publication actions had been taken. A later explicit user request authorized review, commit, PR creation, merge, and issue updates.

## Final diff review

Reviewed the Recovery Upload persistence, services, HTTP contracts, PDF validation, Docling parser, web query/component flow, migrations, and behavioral/API/OpenAPI tests. The reviewed implementation changes are scoped to those areas. At the time of review, `git diff --check` passed and the worktree was intentionally **dirty** because publication had not yet been authorized; “clean” referred only to the whitespace/error check, not an empty diff. Generated `web/next-env.d.ts` changes from local type generation were restored; `tsc --noEmit` passed afterward.

## Follow-up implementation review

The explicitly authorized review found and fixed three in-scope issues:

- Batch expiry failed when uploads existed because its row mapper required the Bibliography Entry's `local_reference_key`, but the query selected only upload columns. The expiry query now joins the entry, and the integration test exercises actual expiry.
- Successful persisted validation, confirmation, and selection did not refresh the seven-day inactivity clock. Those actions now update `last_activity_at` and `expires_at` atomically. Removed uploads and expired batches now purge extracted validation metadata and dependent confirmations/selections. Validation writes lock and recheck active state, and an additive migration adds active-upload and source-deletion guards.
- The validate endpoint documents the new `409` inactive-state response in Springdoc and its OpenAPI contract test.

Focused `AnalysisRunQueueIntegrationTest` coverage passed after these fixes, including inactivity renewal and cleanup on removal/expiry. The full repository check is rerun below before publication.

## Behavioral test-first chronology

The local session log was checked to answer the prior audit's test-order concern. These timestamps record only tool actions, not Analysis Run content:

- **API/PDF validation seam:** `RecoveryPdfValidatorTest.kt` was created at 02:17:13Z. Its focused run at 02:17:35Z was red: the image-only-PDF test failed. The selectable-text and encryption checks in `RecoveryPdfValidator.kt` were edited at 02:17:48Z. The test asserts observable validation outcomes, including the explicit no-OCR blocker for image-only PDFs.
- **Web/UI seam:** the scanned-PDF rejection test in `web/test/recovery-upload-workspace.test.tsx` was added at 02:18:13Z; its focused Vitest run at 02:18:23Z was red. The corresponding UI rejection-message mapping in `recovery-upload-workspace.tsx` was edited at 02:18:38Z. Later focused and full web suites passed.

This establishes test-before-implementation order for those API and UI vertical slices. A follow-up also exercised the broader persisted-validation API seam with a fresh test-first rework:

- Added a behavioral assertion to `AnalysisRunQueueIntegrationTest` that a repeated validation reuses its persisted completed attempt even when the pinned parser adapter is unavailable. The focused test passed against the prior implementation; after disabling completed-attempt reuse, it failed on the expected attempt ID; then the reuse predicate was refactored into `canBeReusedFor`, and the focused test passed again. The assertion uses synthetic fixtures and checks the public service result and persisted attempt.

The chronology of the **initial** implementation was not uniform: `RecoveryUploadValidationService.kt` was first written at 02:29:32Z, while the broad persisted-validation integration test was added at 02:33:37Z. The follow-up is a test-first rework of its persisted-validation/idempotency seam; it does not rewrite that historical fact or claim every original implementation change followed TDD.

A second API red-green slice addresses the wrong-work mismatch gate: a synthetic reference with both a differing DOI and a conflicting extracted title was added to the integration test first. The focused test failed when that case was classified as `NEEDS_CONFIRMATION`; identity evaluation now returns non-overridable `MISMATCH`, and the focused test passes while verifying that confirmation and selection are rejected. At that earlier implementation point, a differing DOI with a matching or absent title remained `NEEDS_CONFIRMATION`; the later maintainer-approved Option A below changes only an exact title match to machine-validated. Missing/unclear matching title remains confirmable, and a clear DOI/title conflict cannot be human-overridden.

## Maintainer-confirmed policy update (2026-10-09)

After PR #111 merged, the maintainer confirmed these follow-up rules:

- Docling title, positional author, and explicitly prefixed DOI values remain metadata candidates, not identity proof by themselves.
- A differing DOI with a matching extracted title may be machine-validated. A differing DOI without a matching title remains confirmable; a conflicting title remains a non-overridable mismatch.
- Bibliography normalization policy v3 classifies GROBID TEI with an analytic work inside a monograph as `BOOK_CHAPTER`; v1/v2 parsing behavior stays unchanged for queued and historical Analysis Runs. `BOOK_CHAPTER` is supported by the existing conservative resolver.
- An inconclusive `BOOK_CHAPTER`, `INBOOK`, or `INCOLLECTION` identity is blocked and cannot be human-confirmed as a whole-book substitute. A chapter that is identified by the current metadata policy may proceed; current-policy `BOOK` references use normal identity checks. Older `BOOK` references are type-ambiguous because earlier policies did not preserve chapter boundaries, so inconclusive identities are blocked pending a new Analysis Run with chapter-aware parsing.
- OCR remains disabled; scanned/image-only PDFs remain unsupported.
- Evaluation evidence is expected, but does not block engineering work. No accuracy, held-out, representative-coverage, or calibrated-threshold claim is made; #86, #88, and #89 remain open.

Identity decisions now carry a separate policy version. Legacy v1 attempts are not reused or returned as current; users must rerun validation under v2 before confirmation or selection. The web UI also withholds confirmation/selection when an API response does not report the current policy, protecting mixed-version rollout. Validation still does not start evidence assessment.

A complete follow-up review found two additional API/parser policy-versioning gaps and fixed them:

- Chapter classification was not tied to the run-pinned bibliography policy, and a TEI `meeting` element could take precedence over an analytic work in a monograph. Normalization v3 now classifies that explicit analytic/monograph structure as `BOOK_CHAPTER` before conference-meeting fallback, unless a journal title is present. GROBID parsing with v1/v2 remains unchanged for historical and queued Analysis Runs, and the v3 type is documented in Springdoc/OpenAPI.
- A pre-v3 `BOOK` entry cannot show whether its cited work was a chapter. If identity is inconclusive, Recovery Upload now blocks confirmation/selection instead of treating it as an ordinary book. An exact DOI match or the approved exact-title alternate-DOI rule can still identify the work. Current v3 `BOOK` entries continue through normal identity checks. This behavior and the stale-validation error are covered by the API, web, and OpenAPI tests and recorded in ADR 0019.

The final staged-diff review also found that the UI said to revalidate an attempt from an older or unreported identity policy, but disabled the validation button for every completed attempt. The UI now offers explicit revalidation for non-current policy responses while continuing to block confirmation and selection. The Recovery Upload workspace test covers this behavior.

## Verification

- `mise exec -- make verify-db` — passed; Sqitch verified the full migration plan, including `recovery_identity_policy_version`.
- `mise exec -- make validate` — passed before the final UI revalidation-control follow-up: API Gradle suite (including OpenAPI and Recovery Upload integration tests), repository Python suites, 37 web Node tests, 144 web Vitest tests, ESLint, TypeScript, and production build.
- After the UI follow-up, `mise exec -- pnpm --dir web exec vitest run test/recovery-upload-workspace.test.tsx` passed (13 tests), and `mise exec -- pnpm --dir web exec tsc --noEmit` passed.
- `cd api && mise exec -- ./gradlew test --rerun-tasks --tests 'com.papertrail.api.analysis.queue.AnalysisRunQueueIntegrationTest.Recovery Upload validation persists Docling provenance and separates identity, language, and assessment'` — passed after shortening the PostgreSQL trigger name; exercises fresh Sqitch deployment and expiry/activity/retention behavior.
- `mise exec -- pnpm --dir web exec tsc --noEmit` — passed after restoring the generated `next-env.d.ts` change.
- Browser check — isolated Chromium with synthetic API responses only. The Recovery Upload confirmation/selection flow rendered at desktop and 390px mobile widths. At mobile size, document width equaled viewport width. Axe reported zero violations; its one incomplete color-contrast check was manually measured at 13.42:1. No Analysis Run text or private data was used.

## Evaluation boundary and issue state

Specific actions deferred for this pass are recorded in [issue-91-evaluation-deferrals.md](issue-91-evaluation-deferrals.md). The existing run was used only for bounded local diagnostics; no human labels, evaluation corpus, held-out results, representative-coverage results, or calibrated thresholds were produced. No thresholds changed.

GitHub issue state was checked after the final update: #86, #88, #89, and #91 are all OPEN. #91's acceptance checkboxes remain unchecked. The final verification comment is [issue comment 6073996497](https://github.com/arrokh/paper-t-rail/issues/91#issuecomment-6073996497). The #86/#88/#89 issue comments separately identify their skipped/deferred evaluation actions.

This note records implementation review and verification only. It contains no Analysis Run identifier, Source Document text, bibliography text, claims, evidence, raw provider payloads, or copied run data.
