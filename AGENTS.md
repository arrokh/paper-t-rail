## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues for `arrokh/paper-t-rail`; use `gh`. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the five canonical labels listed in `docs/agents/triage-labels.md`.

### Domain docs

This is a single-context repo. Read `CONTEXT.md` and relevant ADRs in `docs/adr/`. See `docs/agents/domain.md`.

### API and observability

When changing API endpoints or application logging, follow `docs/paper-t-rail-tech-design.md` §§40 and 49. Never log Source Document, claim, or evidence text, request bodies, or query strings.

### Testing and TDD

When implementing with TDD, agree on the public behavior seam before writing tests, then work in vertical red-green slices. Keep tests behavioral and derive expected outcomes independently.

- Tautological tests are harmful: do not assert a result against the same computation or derive the expected value from the actual result.
- Change-detector tests are harmful: do not pin implementation details or incidental structure when no user-visible behavior is being verified.
- Bug fixes do not automatically need regression tests. Add one only when existing behavior tests have a genuine, identified coverage gap.

See `docs/paper-t-rail-tech-design.md` §56 for the repository testing strategy.

### Cross-service implementation

For changes in `api/`, `web/`, or `homepage/`, follow `docs/agents/coding-principles.md` for simplicity, maintainability, SOLID, guard clauses, and type/file structure. API-specific Kotlin import and feature-architecture rules are in `api/AGENTS.md`; web-specific rules are in `web/AGENTS.md`; Astro-specific rules are in `homepage/AGENTS.md`.

### Web UI

For changes under `web/`, follow `web/AGENTS.md` and `docs/ui-design-system.md`; shadcn/ui is the default component system for interactive primitives.

### Shared product design

Both `web/` and `homepage/` consume the shared tokens in `packages/design-system/` and follow `docs/ui-design-system.md`. Keep colors, typography, accessibility, and interaction principles consistent across services; use the homepage-specific 8-bit visual treatment only where it clarifies the evidence journey.
