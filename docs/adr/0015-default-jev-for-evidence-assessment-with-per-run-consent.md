---
status: accepted
---
# Prefer Jev for Evidence Assessment Without Pre-Authorizing Transfer

Jev is the shipped System One preference when valid server-side configuration makes it selectable; if Jev is unavailable, an omitted choice falls back to local `mock`. The new-run form may preselect configured Jev, but leaves every consent category unchecked, and API run configuration must reject an external selection unless that Analysis Run carries exact matching consent for `atomic_claims` and `evidence_passages`. This supersedes only ADR 0009's Jev non-default clause; Jev remains `EXTERNAL`, its retention uncertainty is disclosed, and its outputs remain uncalibrated.
