# API Service Instructions

For cross-service simplicity, maintainability, SOLID, guard-clause, and type/file conventions, follow [Shared Coding Principles](../docs/agents/coding-principles.md).

## Feature-first architecture

For new API code or structural refactors, read [Suggested Backend Package Structure](../docs/paper-t-rail-tech-design.md#37-suggested-backend-package-structure). Organize by business capability—`document`, `analysis`, `citation`, and `scholarly`—then keep each capability's HTTP entry points, application services, domain policies, persistence, and external adapters local to that feature. Feature-specific queue handlers belong with the feature they advance; only generic outbox, broker/stream worker, and messaging contracts belong in shared infrastructure.

Keep the request flow legible: HTTP controllers and queue adapters should delegate to a named feature service/use case; that service coordinates domain policies and persistence/provider adapters. Centralize lifecycle and state-transition rules with their owning feature. Preserve existing HTTP contracts, database schema, event envelopes, idempotency, retries, and transaction boundaries during behavior-preserving refactors.

Use role packages such as `controller`, `service`, `repository`, `client`, and `queue` inside a feature when they improve navigation. Keep Spring-managed application services/components in `service`; keep framework adapters in their role packages. Use `model` only for persistence entities or projections, not as a bucket for request DTOs, domain concepts, or configuration snapshots. Put each named production type in a focused file by default. Keep generic infrastructure genuinely shared and avoid catch-all packages.

## Kotlin imports

Kotlin imports are file-scoped: keep them in the import block immediately below the `package` declaration. Never write fully qualified class or type names inline when an ordinary import is appropriate; use an explicit import alias only to resolve a genuine name collision.
