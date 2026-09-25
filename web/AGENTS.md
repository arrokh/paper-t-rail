# Web UI Instructions

For code-structure, SOLID, guard-clause, and type/file conventions shared with the API, follow [Shared Coding Principles](../docs/agents/coding-principles.md).

For every change under `web/`, use the [Paper T-Rail Web UI Design System](../docs/ui-design-system.md) as the source of truth and preserve the accepted choice in [ADR 0005](../docs/adr/0005-shadcn-web-ui-system.md).

## Component and styling rules

- Build interface primitives from the project-owned shadcn components in `@/components/ui/*`. Use shadcn `Button`, `Card`, `Badge`, `Field`, `Input`, `NativeSelect`, `Checkbox`, `Tabs`, `Collapsible`, `Alert`, `Separator`, `Skeleton`, and `Spinner` for their established patterns.
- When a needed primitive is missing, add it from `web/` with `npx shadcn@latest add <name>`, then review and commit its generated source. Keep `components.json`, aliases, generated component paths, and global CSS configuration aligned.
- Compose product-specific modules in `web/components/` from those primitives. Extend shared variants and semantic theme tokens when a visual state recurs; use `cn()` from `@/lib/utils` to compose Tailwind classes.
- Use Tailwind CSS v4 utilities for layout and spacing and CSS variables for theme colors. Preserve the Paper T-Rail paper/forest palette. Avoid introducing a parallel primitive library, handwritten buttons/fields/tabs, or one-off palette values.
- Keep semantic HTML for page structure and native browser behavior that shadcn does not replace, such as a real file input. Style those elements with the system tokens and pair them with shadcn controls where appropriate.
- Keep Next.js modules server-rendered by default. Add client state only to the smallest module that needs browser interaction.

## Product and accessibility invariants

- Preserve per-run provider consent, privacy disclosures, immutable Analysis Run provenance, and truthful persisted progress states.
- Target WCAG 2.2 AA. Keep visible keyboard focus, labels, semantic headings/landmarks, keyboard operation, and text-plus-color status cues. Respect reduced-motion preferences and verify mobile layouts do not overflow.
- Use links for navigation and buttons for actions. For a link styled as a button, apply `buttonVariants` to the semantic link instead of changing the link role.

## Verification

For UI changes, run `mise exec -- pnpm --dir web test`, `mise exec -- pnpm --dir web run lint`, `mise exec -- pnpm --dir web run typecheck`, and `mise exec -- pnpm --dir web run build`, or run `make validate` from the repository root. Browser-check changed flows at desktop and mobile sizes; run an accessibility audit for significant interaction or layout changes.

<!-- BEGIN:nextjs-agent-rules -->

# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` (resolved from this file's directory; in monorepos the `next` package may not be visible from the repo root) before writing any code. Heed deprecation notices.

This block is written and re-added by `next dev` — verify at `node_modules/next/dist/server/lib/generate-agent-files.js`. Removing it from a diff only re-creates the uncommitted change; committing it with your work keeps the tree clean.

<!-- END:nextjs-agent-rules -->
