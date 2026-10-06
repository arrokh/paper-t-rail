# API Service Instructions

For cross-service simplicity, maintainability, SOLID, guard-clause, and type/file conventions, follow [Shared Coding Principles](../docs/agents/coding-principles.md).

## Feature-first architecture

For new API code or structural refactors, read [Suggested Backend Package Structure](../docs/paper-t-rail-tech-design.md#37-suggested-backend-package-structure). Organize business code by capability—`document`, `analysis`, `citation`, `scholarly`, `evidence`, and `review`—with clear role packages inside each feature. Keep feature-specific queue handlers, contracts, domain policies, use cases, and persistence with the feature they serve. Group provider communication and provider-owned wiring/cache mechanics by provider under `external/<provider>` (for example `external/crossref` or `external/openai`); do not classify a provider package as a business feature or as generic shared infrastructure.

Keep the request flow legible: HTTP controllers and queue handlers delegate to the owning feature service/use case; that service coordinates domain policies, repositories, and external clients as needed. Not every operation needs every layer. Provider integrations may depend on existing feature contracts and run-specific configuration; relocating them does not authorize changing consent, trust, cache, capture, or provider-selection behavior. Keep feature-specific interpretation and lifecycle/state-transition rules with their owning feature. Only genuinely generic mechanisms—such as outbox, broker/stream worker, shared cache/crypto/logging, and provider catalog/gating—belong in generic infrastructure.

Use focused roles such as `controller`, `service`, `repository`, `http`, `domain`, and `queue` where they improve navigation. Existing provider operator endpoints may use provider-local `controller`, `service`, and `http` packages. Keep feature application services/components in the owning feature's `service`; keep persistence adapters in `repository`. Provider integration components live with their provider under `external/<provider>`. Use `http` for our HTTP contracts, provider wire DTOs beside their provider, and `model` only for persistence records/projections. Do not use `model` as a bucket for request DTOs, business concepts, or configuration snapshots. Keep `config` for composition/framework wiring; provider-specific configuration may live beside that provider. Put each named production type in a focused file by default, retaining cohesive private-helper exceptions. Do not add interfaces, wrappers, or packages solely to make the tree look uniform.

For organization-only refactors, preserve class names, signatures, visibility, Spring wiring, public HTTP/OpenAPI contracts, database schema/SQL/transactions, event envelopes/retries/idempotency, provider consent/configuration fingerprints, cache formats/keys/TTLs, and resource locations. Change package/import references only; stop and separately design any move that requires a behavior, signature, visibility, or lifetime change.

## Relational database access

Apply the repository-only rule to PostgreSQL/JDBC. Only repository classes in a feature's `repository` package, or an infrastructure-owned repository package, may hold `JdbcTemplate`, `DataSource`, JDBC connections/statements, or issue SQL. Controllers, services, queue handlers, health endpoints, and publishers must call repository operations instead. Services and handlers may retain existing `TransactionTemplate` boundaries to preserve atomicity; transaction orchestration does not permit direct SQL. Preserve current query semantics, locks, ordering, idempotency, rollback behavior, and observable outcomes. This rule does not reclassify Redis Streams/caches or S3 object storage; keep their existing infrastructure/external adapters.

## OpenAPI contract — always maintain

OpenAPI documentation is part of every public endpoint's contract. Keep the generated contract complete and current in the same change as the implementation; no endpoint is exempt, including health checks and provider-directory endpoints. When adding or changing a route, method, request/response schema, validation rule, consent behavior, or observable status/error response:

- Document the operation, parameters/request body, success and error responses, and relevant schema fields with Springdoc annotations on the owning controller and HTTP DTOs.
- Update `api/src/test/kotlin/com/papertrail/api/openapi/OpenApiDocumentationTest.kt` to cover important contract details, including changed provider-directory behavior.
- Verify `/v3/api-docs` reflects the implementation and the OpenAPI documentation test passes.

Treat the generated OpenAPI document as the API contract; do not rely on inferred schemas alone for important behavior or maintain a separate hand-edited specification.

## Kotlin imports

Kotlin imports are file-scoped: keep them in the import block immediately below the `package` declaration. Never write fully qualified class or type names inline when an ordinary import is appropriate; use an explicit import alias only to resolve a genuine name collision.

## Configuration defaults

Keep Spring application-property defaults in `api/src/main/resources/application.yml`. In `api/src/main/kotlin/com/papertrail/api/config/`, inject declared properties without repeating fallback values in `@Value` placeholders. When a default changes, keep the effective Compose environment in `infra/docker-compose.yml` and `.env.example` aligned, and update the relevant provider documentation.
