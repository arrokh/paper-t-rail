# API package organization: audit, research, and safe refactor plan

## Recommendation

**Use feature-first business code and provider-first external integrations.** Keep one Spring Boot application. Give each business feature predictable layered roles, and colocate provider-specific integration code under `external/<provider>/`.

The intended business flow is `controller/queue handler → service → repository and/or external client`. Services own orchestration and domain decisions; integration implementations own provider communication. Services consume integration contracts; only replacement implementations implement those contracts. Do not add interfaces, wrappers, or inheritance merely for folder consistency.

**Phase A was organization-only:** split focused files, move existing declarations, and update references without changing implementation behavior. At that time, Phase B (including repository-only SQL enforcement) was planned as a separate follow-up. The maintainer later authorized issue [#82](https://github.com/arrokh/paper-t-rail/issues/82) to be implemented in PR #81 alongside the #79 work. The repository-only relational rule is now documented in `api/AGENTS.md` and technical-design §37; this document's Phase A record remains historical and does not limit the authorized #82 scope.

The user approved the refined package structure on 2026-10-06 and authorized Phase A implementation tracked in [issue #79](https://github.com/arrokh/paper-t-rail/issues/79). This document records the #79 scope and implementation evidence; issue #82 defines the additional persistence-ownership work. A later navigation review moved the provider-neutral object-storage contract from `document/storage` to `infrastructure/storage`, because analysis, evidence, acquisition, and deletion features all consume it; the S3 adapter remains in `external/s3`. It also aligned the `/analysis-runs/{runId}/report` HTTP adapter and read orchestration under `analysis/report`, while keeping reference/evidence report projections with their owning features.

**Behavior preservation is an acceptance criterion, not a zero-risk guarantee.** Package changes can affect discovery and contracts even when bodies are unchanged. Use independently reversible commits and stop at the first unexplained difference.

### Phase A implementation record

- Implemented the approved A0–A36 organization changes on the feature branch, based on `1f8dbef`; no Phase B extraction or SQL redesign was included. The guidance changes preceded source moves, but A2–A35 were grouped rather than committed and fully tested one step at a time; interim compilation checks ran after move groups, and the complete suite/review/live gates ran on the final diff.
- Aligned `api/AGENTS.md` and technical-design §37 before moving production types. Feature contracts and business policy remain feature-owned; the nine provider families are under `external/<provider>`.
- Final check: `cd api && mise exec -- ./gradlew clean test bootJar` — **332 tests across 49 suites; 0 failures, 0 errors, 0 skipped**. Main, test, and calibration Kotlin source sets compiled, and the executable JAR was built.
- Ran the built JAR in both API and worker roles against isolated PostgreSQL/Redis containers using task-only credentials and synthetic data. The API returned `200` for `/api/v1/health` and `/v3/api-docs` (17 paths). The worker consumed a legacy-format fixture envelope and placed it in the dead-letter stream with `UNSUPPORTED_EVENT_TYPE`; no message remained pending. The existing queue integration suite also exercises the valid fixture pipeline through completion with controlled test providers.
- Compared generated `/v3/api-docs` with the saved pre-refactor baseline: identical after JSON key-order normalization (**17 paths, 60 schemas**). Crossref and Unpaywall cache tests replay literal prior-format JSON fixtures.
- No production resources, migrations, property defaults, dependency versions, or deployment files changed. A test-only outbox schema fixture was added for the live API role check. All disposable containers and application processes were stopped after verification.

### Audit baseline

- Reviewed on **2026-10-06**, against `main` at **`8182030`**.
- Spring Boot **3.5.3**, Kotlin **2.1.21**, Java **21**, Gradle Kotlin DSL.
- Persistence is **Spring JDBC**, not JPA. Do not introduce JPA conventions or entities.
- **244 production Kotlin files in 65 packages**, **51 test-source files**, and **10 calibration-source files**.
- No production package/directory mismatches found.
- Fresh verification: `cd api && mise exec -- ./gradlew test --rerun-tasks bootJar`.
- Result: **324 tests in 47 suites; 0 failures, 0 errors, 0 skipped**. Executable JAR packaging and calibration compilation passed. Existing deprecation/compiler warnings were not changed.

This is a source-layout audit supported by existing tests. It is not a complete architectural or production-runtime certification.

## 1. What official documentation actually recommends

| Source | Documented guidance | Application to this API |
|---|---|---|
| Spring Boot 3.5, *Structuring Your Code* [1] | Spring Boot requires no specific layout. Keep the application class in a root package above application code. Its typical example groups controllers, services, repositories, and data by `customer` and `order`. | Keep `com.papertrail.api.PaperTrailApplication` at the root. Feature-first is a supported convention, not a mandatory Spring architecture. |
| Kotlin, *Coding conventions* [2] | Use meaningful file names. A single-class file normally uses that class's name. Multiple closely related declarations are encouraged when their file remains reasonably small. For pure Kotlin, the directory convention follows packages with the common root omitted. | Our full JVM-style directory prefix already works and is consistent. Removing it adds churn without improving feature locality. Prefer focused files under the repo's stricter convention, but retain genuinely cohesive helpers. |
| Kotlin, *Visibility modifiers* [3] | Top-level `private` declarations are file-private. `internal` is visible throughout the compilation module/source set, not just a package. | A file split can break access to private helpers. A directory called `internal` does not enforce feature isolation. Do not widen visibility just to satisfy a file-count rule. |
| Spring Framework 6.2, *Classpath Scanning* [4] | Scanning discovers stereotypes; default component names use the unqualified class name unless explicitly named. | Moving a class below the same application root normally retains discovery and its default bean name. Renaming classes, bean methods, qualifiers, conditions, or scanning rules is a different and riskier change. |
| Spring Boot 3.5, *Testing Spring Boot Applications* [5] | Boot tests discover primary configuration automatically; application scanning and slice-test filters matter. | Keep the application root stable. Validate actual Spring wiring, not compilation alone. |
| Spring Framework 6.2, *Programmatic Transaction Management* [6] | `TransactionTemplate` explicitly encloses work in a transaction callback. | Existing callback boundaries must remain unchanged. Moving SQL or adding `@Transactional` is not needed for a directory refactor. |
| Spring Modulith, *Fundamentals* and *Verification* [7, 8] | Modules can be derived from packages; verification checks module cycles and access to internal packages. | Useful vocabulary for a later architecture project. Do **not** add Modulith or impose its default module rules during this refactor. |

**Important distinction:** “One type per file” is this repository's default, with a narrow cohesive-unit exception. It is not a universal Kotlin requirement. Likewise, neither Spring nor Kotlin requires every feature to have identical subdirectories.

Before A0, local authority in `api/AGENTS.md`, `docs/agents/coding-principles.md`, and technical design §37 specified feature-first organization. **There was a documented policy mismatch:** `api/AGENTS.md` and §37 placed external adapters inside features, while the user approved provider-first `external/` colocation. Phase A began by aligning `api/AGENTS.md` and §37 to the agreed ownership rule before moving source files. Do not silently ignore the old rule.

ADRs 0011 (shared OpenAI-compatible transport), 0014 (S3-compatible storage), and 0016 (execution capture) remain applicable. Changing an integration's package does not change their semantic, privacy, configuration, or storage decisions. Do not rewrite accepted ADRs just to rename folders. The convention `external` is the user's choice, not a layout mandated by Spring or Kotlin.

## 2. Findings verified in the code

Paths below are relative to `api/src/main/kotlin/com/papertrail/api/`.

### What is already sound

- Business capability packages dominate: `document` (7 files), `analysis` (42), `citation` (25), `scholarly` (66), `evidence` (67), and `review` (8).
- Controllers, services, domain policies, provider adapters, and repositories are already separated in many capabilities. Human Review is a particularly consistent small example.
- Feature queue handlers already live with the features they advance; generic Redis/outbox code lives in infrastructure.
- The Spring application class is in the correct root package.
- Run-pinned data in `analysis.configuration` is distinct from framework wiring in root `config`.
- Shared OpenAI-compatible transport remains separate from the claim-analysis adapter, as ADR 0011 requires.

### Organization gaps worth fixing

| Evidence | Finding | Organization-only response |
|---|---|---|
| `analysis/service/AnalysisRunPipelineProgressRepository.kt` has `@Repository`. | An existing persistence adapter is filed among application services. | Move the existing class to `analysis.repository`; do not extract any additional SQL. |
| `citation/parsing/ParsedDocumentRepository.kt` contains a JDBC repository and eight public read-projection types. | Parsing navigation includes persistence and several separately meaningful types. | Split the projections first, then move the repository and projections together to `citation.repository`. |
| `citation/parsing/ScientificDocumentParser.kt` contains both the parser interface and GROBID HTTP adapter. `GrobidTeiParser.kt` contains five public parsed-structure types plus the parser. | Parser contracts and adapter implementation are hard to locate by file name. | Split files in-place first, then move GROBID implementations to `external.grobid`; keep contracts/parsed structures feature-owned and parsing behavior unchanged. |
| `analysis/execution/` contains 16 files covering an HTTP controller, HTTP responses, persistence, recording, redaction, and internal contracts. | A valid subfeature has accumulated mixed roles. This is not itself a Spring violation, but weakens role-based navigation. | Retain the execution subfeature; introduce only the role packages justified by those existing files. |
| `document/validation/PdfValidation.kt` contains seven top-level types. | Finding the validator, validation limits, language detector, and result types requires knowing an umbrella file. | Split meaningful types in-place; keep private extraction helpers with the validator. |
| `infrastructure/providers/ProviderCatalog.kt` contains eleven top-level types and 492 lines. | Registration, directory contracts, payload authorization, and exceptions share one large file. | Split independent declarations in-place. Preserve the private-helper caveat below. |
| Crossref/Unpaywall clients, factories, configuration, caches and operator API types are spread across feature roles. | Provider code is scattered under the now-approved provider-first convention. | Colocate the existing provider families under `external.crossref` / `external.unpaywall`, preserving methods, role-specific operator packages and run lifetimes. |
| `scholarly/references/report/ReferenceResolutionReportResponse.kt` is an HTTP response envelope. | Transport naming is inconsistent with the existing feature `http` package. | Move the envelope to `scholarly.references.http`; keep the report projections in `report`. |

### Important observations that are NOT part of the refactor

- `AnalysisRunService` combines upload/run creation, queries, and direct SQL. Processing/stage-completion services and some queue orchestration also contain persistence work. Consistent role ownership is a Phase A goal; enforcing repository-only SQL or extracting orchestration is separate Phase B work. The health controller's direct `SELECT 1` is a small operational check, not a reason to add shallow layers.
- There are bidirectional source imports between analysis and citation/evidence/scholarly, and between shared infrastructure and features. For example, the Redis worker knows feature handlers, while features use messaging; the provider catalog knows feature settings. Moving directories does not remove these dependencies or create isolated modules. This was an import-level observation, not a full bytecode dependency analysis.
- `AnalysisRunQueueIntegrationTest.kt` is 4,462 lines and spans several capabilities. That is a later test-maintainability opportunity. Do not split its fixtures or assertions in the production-file move series.
- An approximate declaration scan identified 14 multi-type production files. This is a candidate list, **not** 14 automatic violations. Private body-subscriber helpers and small cursor/codec units are legitimate cohesive exceptions.

## 3. Target package structure

Keep source sets, resources, migrations, dependencies, application root, and artifact unchanged:

```text
api/
  build.gradle.kts
  src/main/kotlin/com/papertrail/api/
  src/main/resources/
  src/test/kotlin/com/papertrail/api/
  src/test/resources/
  src/calibration/kotlin/com/papertrail/api/calibration/
  db/
```

### Business features plus provider integrations

```text
com.papertrail.api/
  PaperTrailApplication.kt     # Application/scanning root; keep
  config/                     # Cross-feature Spring/OpenAPI composition; keep
  http/                       # Shared API contracts: ApiError, HealthResponse

  document/
    controller/  service/  validation/
  analysis/
    controller/  http/  service/  repository/
    configuration/            # Immutable run-pinned data, not Spring wiring
    pagination/  queue/
    execution/
      controller/  http/  service/  repository/
    report/
      controller/  http/  service/
  citation/
    parsing/  repository/  claims/
  scholarly/
    references/
      controller/  http/  service/  repository/
      client/  resolver/  normalization/  model/  report/  queue/
    acquisition/
      controller/  http/  service/  repository/
      client/  domain/  report/  queue/
  evidence/                   # Existing domain/use-case organization
  review/                     # Existing domain/use-case organization

  external/
    crossref/
    unpaywall/
    grobid/
    docling/
    openai/
    ollama/
    jev/
    laya/
    s3/

  infrastructure/             # Shared mechanisms and cross-feature contracts
    storage/  messaging/  cache/  crypto/  logging/  http/  providers/
```

Feature `client`/parser/provider packages retain provider-neutral feature contracts, local implementations, and feature-specific interpretation. Shared object-storage contracts used across features live in `infrastructure.storage`; concrete object-storage adapters remain provider-owned under `external/<provider>`. Keep business policies, run snapshots, application orchestration, repositories, and queue contracts feature-owned.

`external` means an integration outside the application process; it does **not** assign the `EXTERNAL` provider trust classification. Self-hosted GROBID, Docling, or Ollama remain local/trusted or consent-gated exactly as before. Renaming generic `infrastructure` to `shared` is not required here; `shared` must not become an alternative provider dumping ground.

### Package roles and data types

| Role | Responsibility |
|---|---|
| Feature `controller` | HTTP routing/binding; delegate application work to the owning service. |
| Feature `service` | Business operations, orchestration, domain policy, persistence/integration coordination. |
| Feature `repository` | Existing database adapters/read projections; do not extract additional SQL in Phase A. |
| Feature `http` | Our request/response DTOs and HTTP-specific validation/schema annotations. Keep the existing name; do not introduce a parallel `dto` naming scheme. |
| Feature `domain` / focused policy packages | Business value objects, rules, and interpretation. |
| Feature `model` | Persistence records/projections only; no standard Spring `@Model` annotation is required. MVC `Model`/`@ModelAttribute` is not a database entity definition. |
| Feature `queue` | Feature event payloads and incoming handlers; generic broker/outbox remains shared. |
| `external/<provider>` | Existing provider clients/implementations, provider-specific configuration, cache mechanics, wire types, exceptions, and integration factories. Start flat. |
| Provider `controller` / `service` / `http` | Only where real operator endpoints exist, colocate that provider's management HTTP flow inside its provider package. These DTOs describe our operator API, not the remote provider's wire format. |
| `config` / provider configuration classes | Spring composition/connection wiring. Property names/defaults remain in `application.yml`; move existing configurations without rewriting defaults or splitting bean methods. |

Class responsibility, not an annotation alone, decides ownership. An integration implementation using `@Component` belongs with its provider. A configuration class may live directly inside its small provider package. Keep one meaningful type per file by default, with private/cohesive exceptions. Create no empty packages or global technical-layer buckets.

Our shared `ApiError` and `HealthResponse` remain root `http` contracts. Move the existing `HealthController` and `ApiExceptionHandler` to generic `infrastructure.http`; keep their bodies intact, including the health query. Do not add pass-through health services/repositories just for uniformity. The provider directory actually lives in `infrastructure.providers.ProviderController`, not root `http`; give it a local `controller` package and its directory DTOs a local `http` package.

### Concrete provider colocation

Paths are relative to the production root. Class names and interfaces stay unchanged; the inventory covers existing declarations, not hypothetical new classes.

| Current declarations | Phase A target |
|---|---|
| `scholarly/references/client/`: `CrossrefCacheKeys`, `CrossrefLookupCache`, `RedisCrossrefLookupCache`, `CrossrefScholarlyMetadataLookup`; `service/`: `CrossrefScholarlyMetadataLookupFactory`, `CrossrefClientConfiguration`, `CrossrefCacheConfiguration` | `external.crossref` |
| `CrossrefCacheInvalidationController`; `CrossrefCacheInvalidationService`; `CrossrefCacheInvalidationRequest`, `CrossrefCacheInvalidationResponse`, `CrossrefCacheLookupType` | `external.crossref.controller`; `external.crossref.service`; `external.crossref.http`, respectively |
| `scholarly/acquisition/client/`: `UnpaywallDiscoveryCache`, `RedisUnpaywallDiscoveryCache`, `UnpaywallCacheKeys`, `UnpaywallCachedLocation`, `UnpaywallDiscoveryCacheEntry`, `UnpaywallOpenAccessProviderFactory`; `UnpaywallDiscoveryCacheConfiguration`; root `config/OpenAccessHttpConfiguration` | `external.unpaywall`. Move the entire existing HTTP configuration, including both named clients; do not split its shared private request-factory method. |
| `UnpaywallCacheInvalidationController`; `UnpaywallCacheInvalidationService`; `UnpaywallCacheInvalidationRequest` | `external.unpaywall.controller`; `external.unpaywall.service`; `external.unpaywall.http`, respectively |
| Extracted `GrobidScientificDocumentParser`, `GrobidTeiParser`, `GrobidCitedPaperPdfParser`, root `ScientificDocumentParserConfiguration` | `external.grobid` |
| `DoclingCitedPaperPdfParser`, root `DoclingCitedPaperParserConfiguration` | `external.docling` |
| Four existing `infrastructure/providers/openai/` files | `external.openai` |
| `OllamaEmbeddingProvider`, `OllamaEmbeddingSettings`, `OllamaEmbeddingException` | `external.ollama` |
| `JevSystemOneProvider`, `JevSystemOneSettings`, `JevSystemOneProviderException` | `external.jev` |
| `LayaSystemOneProvider`, `LayaSystemOneSettings`, `LayaSystemOneProviderException`, `LayaEvaluationProvider` with its existing nested types | `external.laya` |
| `document/storage/S3CompatibleSourceDocumentObjectStore` | `external.s3` |

Crossref's existing client/factory is bound to `AnalysisConfigurationSnapshot`, `ProviderCallGate`, execution capture, and scholarly lookup types. Unpaywall's factory also applies legal-access/public-address checks and creates a per-run implementation. **Phase A preserves those dependencies and per-run lifetimes, even after moving files.** It does not turn either into an unrestricted/global singleton or claim that they are context-neutral modules. Existing feature imports from `external` are a documented transitional condition, not a new dependency-enforcement failure.

Keep these feature-owned examples where they are: `ScholarlyMetadataLookup`/`ScholarlyMetadataLookupFactory`, `BibliographyReference`/`ScholarlyWork`, conservative resolver/DOI rules, `OpenAccessProvider`/`OpenAccessProviderFactory`, legal/public-address policies, parser contracts and parsed structures, `DefaultCitedPaperParser`, embedding/System One contracts and mocks, and `LayaEvidencePassageSpanPlanner`. The source-object-store contract initially remained feature-owned in Phase A; the later owner decision moves it to `infrastructure.storage` because multiple features depend on the capability. Keep claim-analysis prompts, payload/response semantics, use-case settings and `OpenAiCompatibleClaimAnalysisProvider` with claim analysis per ADR 0011; that feature implementation composes the shared `external.openai` transport. “All Crossref-related integration files together” is not permission to move scholarly matching or Analysis Run business rules into that provider.

Move corresponding provider tests/fixtures together only when needed for navigation; preserve assertions and class names. Update imports in main, test, and calibration sources, and configuration-test imports. Do not move resource files or regenerate fixtures.

### Optional future shared API

`CrossrefApi` is an illustrative possible interface, **not a Phase A file, mandatory abstraction, or approved method contract**. Reuse the current contract/concrete implementation when sufficient. A feature service consumes a client; a different client implementation implements its interface. Context-specific behavior usually composes the integration, not subclasses it or implements a remote API merely to call it.

A later context-neutral API needs a concrete consumer/substitution need, agreed methods/results, and explicit ownership of per-run authorization, cache behavior, capture, errors, and provider selection. Keep its role/model-specific business interpretation in features. Do not bypass the current gate, change fingerprints, or reuse one run's authorization for another run. Treat this as a separate reviewed behavior-preserving extraction, not part of the move series.

### Concrete execution-subfeature mapping

| Current declaration(s), all under `analysis.execution` | Proposed package |
|---|---|
| `AnalysisRunExecutionController` | `analysis.execution.controller` |
| `AnalysisRunExecutionSummary`, `ExecutionArtifactDescriptor`, `ExecutionArtifactResponse`, `ExecutionDomainLink`, `ExecutionSpanPage`, `ExecutionSpanResponse` | `analysis.execution.http` |
| `AnalysisRunExecutionRepository` | `analysis.execution.repository` |
| `AnalysisRunExecutionService`, `ExecutionCaptureSanitizer`, `ScholarlyProviderResponseProjector` | `analysis.execution.service` |
| `ExecutionOperationId`, `ExecutionSpanArtifactSpec`, `ExecutionSpanCursorCodec`, `ExecutionSpanHandle`, `ExecutionSpanSpec` | Keep `analysis.execution` |
| `CaptureFidelity`, `SanitizedExecutionArtifact`, currently in the sanitizer file | Split into focused files; keep `analysis.execution` as recording/capture contracts |

Moving the projector does not register it as a bean. Preserve its direct construction and helper lifetimes. Keeping internal contracts at the subfeature root avoids one-file domain/pagination packages.

## 4. Scope and invariants

### Allowed changes

- Move existing source files into the agreed feature-role or provider packages and update packages/imports/references.
- Extract an unchanged, independently meaningful declaration into a focused file.
- Update all source-set imports and relocate a focused test only for clear ownership; preserve assertions, resources and fixtures.
- Update architecture guidance before source moves, plus references to moved types.
- Add a narrowly scoped test-only behavior gate for an identified coverage gap before the relevant move. Do not change production code to make a move test pass.

### Must remain unchanged

- Class/type names; constructors; methods; defaults; visibility; annotations and annotation use-site targets.
- Spring bean names, factory method names, qualifiers, conditions, and scheduling settings.
- HTTP routes, methods, JSON names/nullability/enums, validation, status/error responses, and generated OpenAPI schema/operation identifiers.
- Database tables, migrations, SQL bodies, constraints, row mappings, locks, and transaction callback boundaries.
- Event type/version, payload/envelope fields, handler IDs, outbox/inbox behavior, retries, acknowledgements, and idempotency.
- Provider IDs, consent requirements, fingerprints, pinned run configuration, prompts, parsing, retrieval, and judgement/aggregation policies.
- Object keys, deletion/tombstone behavior, capture/redaction, and content-free operational logging.
- Source-set definitions, resource locations, dependencies, entry-point FQNs, and deployment configuration.
- Per-run provider factories/configuration lifetimes, gate-before-cache/call ordering, cache namespaces/keys/JSON/TTLs, operator credentials and status behavior, trusted-host/public-address/redirect rules, and response/download/token bounds.

**Stop rather than “clean up while here.”** If a move needs a signature/visibility/logic change, split it out for a separately approved design task.

### Private-helper caveat: provider catalog

`ProviderRegistration` and `ProviderCatalog` both use the file-private `sha256Fingerprint` function. Registration also uses a private disclosure constant. Moving registration alone cannot preserve current file-private access.

For this series, split the other independent declarations but keep the registration/catalog/private-helper unit together. Leave role constants and top-level helpers in the original file. Do not duplicate fingerprint code, invent a generic utility, or widen helper visibility merely to achieve one type per file. The residual coupled pair is an explicit exception, not a hidden blocker.

## 5. Commit-sized implementation sequence

Only Phase A is specified for execution. Apply sequentially; split a row further if the diff is not easy to review. Use one writer per working tree. Initial documentation/test gate commits do not move production code.

Every production step must compile main/test/calibration, pass relevant behavior tests and the full API suite, and leave a working application. Preserve bodies verbatim except for reference updates; a grouping table does not authorize moving business policy or extracting methods.

| Step | Small commit | Specific acceptance focus |
|---|---|---|
| A0 | Update `api/AGENTS.md` and tech design §37 to the approved feature-first business/provider-first external rule, package roles, and unchanged application root. Record Phase A/Phase B distinction. | Resolve the current guidance conflict before source moves; keep accepted ADR semantics unchanged. |
| A1 | Reestablish revision/test/contract/event/configuration/cache-fixture baselines and the repeatable real API/worker role gate in §6. Add a test-only gate only where existing evidence is insufficient. | Historical 324-test result is not a new runtime gate. Controlled fixtures only; reuse #55/#56 without widening to unrelated work. |
| A2 | Extract `ApiError` from `ApiExceptionHandler.kt` to its focused root `http` file, same package. | Error JSON/schema identity unchanged. |
| A3 | Split validation exception, limits, and validated-PDF result in the current package. | Upload codes/defaults/metadata unchanged; reuse #56 boundary assertions if needed. |
| A4 | Split language-detector interface/result/adapter; name remaining file after `PdfDocumentValidator`. | Detector/provenance/bean identity/private helpers unchanged; check facade references before renaming. |
| A5 | Split the five public parsed-document structure types from `GrobidTeiParser.kt`, same package. | Parser text/offset/citation/security fixtures unchanged. |
| A6 | Split `GrobidScientificDocumentParser` from its interface, same package. | Existing interface, consolidation-disabled options and response bounds unchanged. |
| A7 | Extract eight parsed-document read projections into focused files, same package. | Keep private row helpers; serialized fields unchanged. |
| A8 | Move `ParsedDocumentRepository` to `citation.repository`. | Persistence, processing and deletion integration scenarios pass. |
| A9 | Move its extracted projections to `citation.repository`. | Parsed-document JSON/OpenAPI names unchanged; no mappers added. |
| A10 | Move `AnalysisRunPipelineProgressRepository` to `analysis.repository`. | Progress and existing injected/default-constructed call sites unchanged. |
| A11 | Split provider directory DTOs and independent trust/data-category declarations into focused files, initially same package. | Keep registration/catalog/private-helper unit intact; disclosure order unchanged. |
| A12 | Split provider payload/gate/exceptions into focused files, same package. | Selection, exact per-run consent, availability checks and fingerprints unchanged. |
| A13 | Split capture fidelity/sanitized-artifact types, same package. | Capture schema/fidelity/omission behavior unchanged. |
| A14 | Move execution recording service, sanitizer and directly constructed projector to `analysis.execution.service`. | Recording context, opt-out/removal/redaction and no-impact-on-analysis preserved. |
| A15 | Move execution repository to `analysis.execution.repository`. | SQL, locks, terminal/deletion behavior unchanged. |
| A16 | Move execution response types to `analysis.execution.http`. | Paging, generated schema references and no-store responses unchanged. |
| A17 | Move execution controller to `analysis.execution.controller`. | All routes discovered with identical contracts. |
| A18 | Move `ReferenceResolutionReportResponse` to `scholarly.references.http`. | Keep report projections and combined JSON unchanged. |
| A19 | Move Crossref cache interface/keys/Redis implementation to `external.crossref`. | Preserve stored JSON, DOI/search keys, positive/negative TTLs, miss/negative-hit and invalidation semantics. |
| A20 | Move existing Crossref lookup and per-run factory to `external.crossref`. | Preserve feature contract, `forRun` lifetime, gate-before-cache/call ordering, capture and failure mapping. Do not create `CrossrefApi`. |
| A21 | Move both Crossref configuration classes to `external.crossref`. | Factory names/qualifiers/defaults unchanged; no bean-method extraction. |
| A22 | Move Crossref operator controller/service/HTTP types to corresponding roles inside `external.crossref`. | Existing route, exact logical-key invalidation, credential checks, statuses and schemas unchanged. |
| A23 | Move Unpaywall cache interface/keys/location/entry/Redis implementation to `external.unpaywall`. | Cache keys, stored formats, TTLs and discovery provenance unchanged. |
| A24 | Move Unpaywall per-run factory to `external.unpaywall`. | Consent, legal access, public-address checks, redirect policy, download limits and fixture outcomes unchanged. |
| A25 | Move Unpaywall cache configuration and whole `OpenAccessHttpConfiguration` to `external.unpaywall`. | Both named clients and shared private request factory preserved; no setting change. |
| A26 | Move Unpaywall operator controller/service/request to corresponding roles inside `external.unpaywall`. | Existing credential-protected HTTP and cache behavior unchanged. |
| A27 | Move GROBID adapters/TEI parser and whole source-parser configuration to `external.grobid`. | Keep parsed structures/contracts/segmentation policy feature-owned; trusted-host and consolidation/security rules unchanged. Split this move if necessary. |
| A28 | Move Docling parser/configuration to `external.docling`. | HTTP/1.1, trusted-host rules, timeouts, payload/response bounds and parser provenance unchanged. |
| A29 | Move four shared OpenAI transport files to `external.openai`. | ADR 0011 composition, endpoint/profile/exception behavior unchanged; claim semantics stay local. |
| A30 | Move three Ollama integration files to `external.ollama`. | Selection, dimension/fingerprint, timeout/bounds, consent and no-fallback behavior unchanged. |
| A31 | Move three Jev integration files to `external.jev`. | Existing provider contracts, consent, judgement mapping, retries/no-fallback unchanged. |
| A32 | Move four Laya integration files to `external.laya`. | Keep span planning/domain policy local; preserve ordinary-call versus evaluation-only capture, token guards, calibration imports and error/retry semantics. |
| A33 | Move S3-compatible implementation to `external.s3`. | Interface, credentials/lifetimes, object keys/hash metadata, presign overrides and deletion semantics unchanged; no data migration. |
| A34 | Move health controller and exception advice to generic `infrastructure.http`; retain shared root HTTP contracts. | Health query/body/status and global exception mappings unchanged. No extra health layers. |
| A35 | Move `ProviderController` to `infrastructure.providers.controller` and three directory DTOs to `infrastructure.providers.http`. | Provider-directory routes/order/disclosure/schema identities unchanged. Keep catalog policy/classes shared. |
| A36 | Refresh references and run final clean build, role smoke checks, generated-contract comparison, pre-refactor stored-data replay and fixture pipeline. | No unintended source/resource/config/schema changes; package moves are not claimed as strict layering or context-neutral extraction. |

For each provider step, update corresponding tests and calibration references, retaining assertions and resource locations. No new DTO/mapper files are implied by provider diagrams. Leave small cursor/codec units and private transport helpers together. Stop at a signature, visibility, wiring-lifetime, or method-body change and separate it from this series.

### Phase B — separately reviewed, not executable from this issue

Two possible follow-ups are explicitly outside Phase A: (1) a reusable context-neutral API, initially Crossref, once a real consumer and contract are agreed; (2) strict service/repository separation for existing SQL and orchestration. Do not begin either automatically, add scaffolding for it, or mark it complete because files moved. Each needs its own precise behavior seam, coverage assessment, tested extraction steps, and approval. No method signature or interface name has been decided for the future API.

## 6. Verification and no-regression gates

### Existing behavioral coverage to reuse

- `OpenApiDocumentationTest`: **22 tests**, including endpoint contracts, HTTP behavior, provider directory, error/authorization behavior, and execution responses.
- `AnalysisRunQueueIntegrationTest`: **57 tests**, including upload/outbox persistence, parsed structures, legacy configuration, exact citation associations, terminal access states, provider failures, retries, duplicate delivery, reclamation, deletion/tombstones, and execution capture.
- Validation, parser, provider-adapter, retrieval, aggregation, cursor, and execution-service unit/contract tests.
- Actual Redis cache integration tests and S3-compatible object-store contract integration.
- Calibration compilation is already part of the test compilation path. Do not run evaluation/provider-inference commands or regenerate benchmark reports for a directory-only change.

### Identified coverage gap

The main Redis integration suite constructs workers/handlers directly. It proves substantial async behavior, but does not prove that the real **worker-role Spring context** discovers and wires every moved bean. The full OpenAPI context test explicitly uses the **API role** and mocks several services.

Before moving scanned production types, obtain repeatable worker-context evidence. Prefer one focused behavior test or disposable packaged-application smoke check: start the real worker role against isolated PostgreSQL/Redis/object-storage and controlled local parser/provider fixtures; feed one fixture envelope and observe the expected terminal state. Pair it with API-role HTTP checks. Keep expected fixture outcomes independent of the implementation.

Existing issue [#55](https://github.com/arrokh/paper-t-rail/issues/55) already tracks the broader upload-to-report system-test gap. Reuse suitable evidence/fixtures from that work rather than opening a duplicate end-to-end test task. The narrow role-wiring gate is required; completion of unrelated full-web work is not. Existing issue [#56](https://github.com/arrokh/paper-t-rail/issues/56) covers the identified PDF page-limit boundary gap. Use its focused, independent expected outcomes if additional validation coverage is needed before splitting the validator file.

The runtime selector is **`paper-trail.role` / `APP_ROLE`**, not just a Spring profile. Preserve the worker-only Redis worker and API-only outbox publisher conditions. Do not assert folder names or bean counts as a substitute for this behavior gate.

### Per-commit checks

1. Review a rename-aware diff. Permitted changes are declaration relocation, packages/imports, and necessary references. Reject changed method bodies, annotations, defaults, initializers, SQL, policy strings, or transaction placement.
2. Search all source sets and repository configuration/resources/scripts for the moved FQNs. Check reflective lookup, package-scoped advice/pointcuts, serialization discriminators, generated schema names, and build entry points. The initial API scan found no explicit component-scan override, class-name polymorphic JSON annotations, or reflective `Class.forName` lookup; that does not remove the need to check each move.
3. Run `cd api && mise exec -- ./gradlew test --rerun-tasks bootJar`. Docker is required for integration coverage. A skipped integration suite is not a passing move gate.
4. For HTTP-exposed types, regenerate `/v3/api-docs` and compare it with the temporary pre-refactor baseline. Compare paths, operations, responses, validation, required fields, schemas, and `$ref` targets. Normalize JSON key order and only explicitly environment-dependent values; do not ignore schema/operation names. This temporary comparison is not a second maintained API specification.
5. For scanned types/configuration, exercise the real API and worker role gates. Replace enabled network integrations with controlled local fixture endpoints; otherwise keep them disabled. Do not let default remote/model endpoints activate, use production data/credentials, or invoke real inference. The package name `external` does not change consent/trust requirements.
6. Replay representative pre-refactor envelopes, immutable configuration and populated provider-cache JSON against the new code. Keep event/handler/version/fingerprint semantics, cache key namespaces/TTLs and negative-hit behavior unchanged. Use synthetic/checked-in fixtures, not captured user content.
7. For provider moves, verify selection and lifetime remain per-run, including rejection before a cached or remote result can be returned without valid matching consent. Reuse provider contracts and operator HTTP tests: cache invalidation, invalid credentials, unavailable Redis, trusted/public host rules, redirects, payload limits and no-fallback/retry behavior. Do not create a general ungated client or weaken tests.
8. Inspect both relocated configurations and unchanged composition roots for duplicate/missing bean names or qualifier mismatches. No class rename, `@Primary`, new bean registration, scheduling/profile change, or singleton promotion is implied by relocation.

### Final acceptance and rollback

- Run `cd api && mise exec -- ./gradlew clean test bootJar` to exclude stale class-output effects.
- Compile all three Kotlin source sets; preserve the application and calibration main-class FQNs in Gradle.
- Compare before/after HTTP contracts and deterministic fixture outcomes. Do not compare new outputs with expectations computed from those same outputs.
- Verify packaged API and worker startup/processing in an isolated environment, without changing the user's existing stack. Start with an empty fixture database and test current-schema/pre-refactor serialized data separately; no new migration should be needed.
- Use the same built backend artifact for both roles. Deploy/rollback through the existing release process; do not invent rolling-version compatibility changes here.
- If a check fails, stop the series, explain the difference, and correct or revert only the latest step. Do not loosen contracts or tests to make a move pass.
- Close disposable application processes/containers when checks finish. Do not alter live data, reset unrelated work, or run destructive cleanup.

Passing the present baseline does not guarantee future moves are safe. Acceptance applies to the actual refactored artifact after these gates.

## 7. Explicitly out of scope

- Repository extraction from service bodies, splitting use cases/queue orchestration, transaction redesign, or pipeline/state-machine changes.
- Creating a context-neutral `CrossrefApi`, removing per-run factories, changing interfaces/method signatures, or making run-bound clients unrestricted singletons.
- Moving reference matching, upload orchestration, domain policies, final-verification ownership, prompts, or feature-specific interpretation into an external provider.
- Dependency-cycle elimination, enforced module isolation/visibility, Gradle submodules, Modulith adoption, hexagonal scaffolding, inheritance frameworks, or microservices.
- New DTOs/interfaces/mappers/wrappers purely for package diagrams; deleting existing useful interfaces merely because they resemble ports/adapters.
- Application bootstrap relocation, root-package shortening, generic-infrastructure rename to `shared`, or blanket class renames.
- HTTP/DB/event/provider-contract, trust/consent, settings/default/credential, dependency-version or warning-cleanup changes.
- Moving feature handlers into provider/shared packages, sensitive content into operational logs, or weakening operator authentication.
- Large integration-fixture reorganization, benchmark/model/calibration changes, resource moves, inference runs, or uncontrolled network calls.

**Phase A stop condition:** listed responsibilities/files are consistently discoverable, provider integration families are colocated, and all preservation gates pass without implementation redesign. Document remaining imports/layering exceptions; do not present them as isolated modules or complete Phase B work.

## 8. Review and requirements traceability

The organization audit was performed against `main` at `8182030`; implementation started from the feature branch at `1f8dbef`. The 324-test audit result below is historical baseline evidence. Current implementation verification is recorded in the Phase A implementation record above and was completed after the package moves.

| Requirement / acceptance criterion | Plan section or step | Verification | Status / gap |
|---|---|---|---|
| Directory/file organization is the first priority | §§3–5; Phase A | Rename-aware diffs; existing names/bodies; no extra abstractions | Covered |
| Keep business code feature-first with understandable layering | §3 roles; A0, A8–A18 | Existing request/queue/service flows; acknowledge current SQL/orchestration | Covered as ownership convention; strict enforcement deferred |
| Group provider functionality under simple `external/<provider>` packages | §3 provider mapping; A19–A33 | Check the enumerated source families and remaining-name ownership exceptions | Covered, including cache/config/operator HTTP families |
| Other features can use integrations without inheriting service behavior | §3 optional future API; Phase B | Preserve present contracts; consumers compose; contract/substitution need before extraction | Colocation covered; context-neutral API not designed or claimed |
| Do not impose hexagonal/framework/interface boilerplate | §§1,3,7 | No new framework, wrapper, interface, `ports`/`adapters` tree or visibility widening | Covered |
| HTTP DTOs, persistence models, business VOs and remote types have clear roles | §3 role table; A2, A7–A9, A16–A18, A22/A26/A34/A35 | Independent existing JSON/schema/fixture assertions | Covered; keep `http` naming |
| No observable behavior changes | §4 invariants; §6 gates | Full suite, OpenAPI comparison, stored JSON/event/config/cache replay, deterministic fixture outcomes | Covered as acceptance; zero risk is not guaranteed |
| Preserve consent/security/provider provenance and cache semantics | §§3,4,6; provider steps | Gate/order tests, operator credential tests, legal/public-host/limits tests, pinned configuration fixtures | Covered; extraction cannot bypass gates |
| Preserve API/worker runtime discovery and queue behavior | A1; §6 | Packaged-JAR API HTTP smoke, worker fixture-envelope processing, and valid fixture pipeline suite | Verified with isolated PostgreSQL/Redis; no real inference or production provider calls |
| Keep runtime profiles/resources/schema/build entry points unchanged | §§4,6; A36 | Compile all source sets, clean artifact, same-role properties and fixtures | Covered; no migration/new environment needed |
| Resolve current guidance mismatch before moves | §1; A0 | Review `api/AGENTS.md` and §37 against approved rule; retain ADR semantics | Completed before source moves; guidance updates are in the reviewed change |
| Reuse existing testing work and avoid duplicates | A1; §6 | Inspect #55/#56 and reuse relevant evidence; narrow tests only for genuine gaps | Covered; broad web work not automatically blocking |
| Small working steps, rollout, rollback, cleanup | §§5–6 | Rename-aware review; final clean gates; same packaged artifact for both roles; isolated resources cleaned | Final gates and cleanup verified; intermediate commits/full suites per row were not used |

### Addressed gaps and evidence

1. **Old ownership targets contradicted the approved provider-first layout.** The previous plan moved provider configuration into scholarly feature `config` packages. The new map covers real Crossref/Unpaywall caches, factories, wiring and operator types, plus existing parser/model/storage integrations.
2. **Current guidance still required feature-local external adapters.** `api/AGENTS.md` and §37 substantiate that conflict. A0 resolves it explicitly before implementation.
3. **A provider move was liable to be mistaken for reusable-client extraction.** `CrossrefScholarlyMetadataLookupFactory.forRun` and the Unpaywall factory retain run configuration, consent, capture and guards. The scope now distinguishes colocation from Phase B extraction and preserves their lifetimes.
4. **HTTP naming mixed contracts and executable infrastructure.** Inspection shows root health/advice and a provider directory already under `infrastructure.providers`. A34/A35 clarify roles without new services or routes; earlier wording placing the provider directory in root `http` is corrected.
5. **The example `CrossrefApi` could imply an invented interface/rename.** It is now explicitly illustrative/future-only. Actual current class names/interfaces are the move inventory.
6. **Successful direct-worker tests could overstate discovery coverage.** A1 requires real role wiring; present mocks/direct construction are not treated as equivalent evidence.

### Remaining assumptions and recommendation

- Provider-specific bodies still import some feature contracts/policies after Phase A. This is deliberate preservation, not a claim of context-neutrality or acyclic modules.
- Real API/worker role gates and prior-format cache/event fixtures were exercised with isolated services and synthetic data. The packaged worker smoke intentionally used an unsupported envelope to verify routing to a terminal dead-letter state; valid pipeline completion remains covered by the existing fixture integration suite.
- A new consumer's exact shared API methods/results and stricter SQL extraction are unresolved **optional Phase B design**, not blockers to the organization sequence.
- Keep existing generic `infrastructure` and `http` names; there is no need for an additional naming decision to execute Phase A.

**Recommendation: Phase A complete.** Its package moves and preservation gates passed as recorded above. Phase B is not designed, authorized, or scheduled; do not infer strict module isolation or context-neutral provider clients from this work.

## Sources

Primary documentation consulted on 2026-10-06. Spring Boot/Framework references use the existing 3.5/6.2 lines; latest Kotlin and Modulith documentation supplies language conventions and optional architectural context, not an upgrade recommendation.

1. [Spring Boot 3.5 — Structuring Your Code](https://docs.spring.io/spring-boot/3.5/reference/using/structuring-your-code.html)
2. [Kotlin — Coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
3. [Kotlin — Visibility modifiers](https://kotlinlang.org/docs/visibility-modifiers.html)
4. [Spring Framework 6.2 — Classpath Scanning and Managed Components](https://docs.spring.io/spring-framework/reference/6.2/core/beans/classpath-scanning.html)
5. [Spring Boot 3.5 — Testing Spring Boot Applications](https://docs.spring.io/spring-boot/3.5/reference/testing/spring-boot-applications.html)
6. [Spring Framework 6.2 — Programmatic Transaction Management](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/programmatic.html)
7. [Spring Modulith — Fundamentals](https://docs.spring.io/spring-modulith/reference/fundamentals.html)
8. [Spring Modulith — Verifying Application Module Structure](https://docs.spring.io/spring-modulith/reference/verification.html)
