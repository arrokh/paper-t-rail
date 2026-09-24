# Paper T-Rail Web UI Design System

This is the source of truth for web visual language, component selection, accessibility, and responsive behavior. `web/AGENTS.md` turns these decisions into implementation rules; the technical design links here from §41.

## Product posture

Paper T-Rail is a focused research workspace, not a generic analytics dashboard. Its interface should feel calm, editorial, precise, and privacy-conscious. Prioritize document legibility, explicit provenance, and clear status over decoration. Preserve the existing paper-and-forest identity: warm paper canvas, white reading surfaces, ink text, forest-green primary actions, and restrained amber/red status accents.

## UI architecture

- **Framework:** Next.js App Router, React, strict TypeScript, and Tailwind CSS v4.
- **Primitives:** shadcn/ui using its `base` (Base UI) component set. Components are generated into `web/components/ui/` and committed as project-owned source; there is no opaque runtime shadcn component package.
- **Composition:** product-specific UI belongs in `web/components/`; route composition belongs in `web/app/`. Keep primitive behavior and variants in `components/ui`, compose them for the product rather than creating parallel button, field, tab, or disclosure systems.
- **Styling:** semantic CSS custom properties in `web/app/styles.css` are the palette and surface source of truth. Tailwind utilities express layout and composition. `cn()` from `web/lib/utils.ts` composes classes and resolves conflicting utilities.
- **Icons:** Lucide React, sized consistently and hidden from assistive technology when adjacent text already names the action.
- **Server/client split:** keep route/layout modules server-rendered; put client state and browser APIs in the smallest interactive product module.

Use shadcn `Button`, `Card`, `Badge`, `Alert`, `Checkbox`, `Field`, `Input`, `NativeSelect`, `Tabs`, `Collapsible`, `Separator`, `Skeleton`, and `Spinner` for the corresponding interface patterns. Add another shadcn primitive through the CLI when a need arises. Use semantic HTML for document structure and native behavior that shadcn does not replace (for example, a real file input); keep its visual treatment composed from system tokens and shadcn primitives.

## Visual foundations

### Color tokens

Define colors once as semantic CSS variables and map them in Tailwind with `@theme inline`. Components consume token names, not ad hoc palette values.

| Token role | Paper T-Rail intent |
|---|---|
| `background` / `foreground` | warm paper canvas / dark ink |
| `card` / `card-foreground` | white reading surfaces / ink |
| `primary` / `primary-foreground` | forest green action / white |
| `secondary`, `muted`, `accent` | quiet green-gray surfaces for secondary information and selection |
| `muted-foreground` | readable subdued copy and metadata |
| `border`, `input`, `ring` | low-noise separators, control edges, and visible forest focus |
| `destructive` | accessible red for errors and destructive actions |
| status variants | distinct text-and-surface pairs for queued, processing, parsed/completed, and failed; never encode status by color alone |

V1 ships a light theme. Keep components semantic-token based so a separately designed dark theme can be added without rewriting component styles; do not add an unreviewed theme toggle.

### Type and spacing

- Use a legible system sans-serif for controls and body copy; reserve a serif face for the editorial hero/brand accent and monospace for hashes, parser/version metadata, offsets, and compact status labels.
- Use a small, consistent type scale with comfortable body line-height. Long filenames, hashes, reference text, and other untrusted content must wrap instead of widening the workspace.
- Use Tailwind's spacing scale (4px base) and a compact radius/elevation scale. Prefer subtle borders and restrained shadows; avoid stacked card-within-card decoration.
- Use Lucide icons with a consistent 16–20px size. Pair meaningful icons with a text label or accessible name.

## Workspace composition

The upload workspace keeps three task-oriented cards: **01 Source Document** and **02 Persisted Progress** share the desktop row; **03 Parsed Document** spans the full width below. At narrow widths, cards stack in that reading order. Use a centered, readable content width and preserve generous space for source text and references.

- **Source Document:** provider choices, exact per-run external-data disclosure and consent, file selection, upload action, and concise privacy/error feedback. Use a project-owned shadcn Button to activate the native file input and display its selected filename in a consistently aligned field.
- **Persisted Progress:** cursor-paginated Analysis Runs, explicit selection, current status, and loading/empty states. Keep the page indicator at the lower left and navigation controls at the lower right; omit Previous on the first page.
- **Parsed Document:** selected run status, current persisted progress and provenance, then the parsed sections, Citation Contexts, and Bibliography Entries. Citation markers link to their bibliography entries, and each entry links back to every citing Citation Context. Briefly highlight anchor destinations after navigation, respecting `prefers-reduced-motion`. Tabs and arrow controls expose only states supported by the selected run; do not imply stage history that is not stored.

Keep product copy factual and non-alarmist. Distinguish privacy/consent decisions from ordinary validation. Error, loading, empty, disabled, and success states are first-class designs, not afterthoughts.

## Interaction and accessibility

Target WCAG 2.2 AA for the rendered interface.

- Use the shadcn primitive's built-in keyboard and focus behavior. Keep visible `:focus-visible` styling and ensure all workflows work without a pointer.
- Associate every control with a visible label. Use native buttons for actions and links for navigation; when styling a Next.js link as a button, compose `buttonVariants` onto the link rather than changing its role.
- Preserve semantic heading order, landmarks, lists, field groups, and tab/tabpanel relationships. Use live announcements only for meaningful asynchronous status changes.
- Never communicate status, selection, or validation solely through color or iconography. Keep text contrast legible on all token surfaces.
- Respect `prefers-reduced-motion`; animations must be brief, nonessential, and never block feedback.
- On small screens, maintain readable content widths, minimum touch targets, and no horizontal page overflow. Test the workspace at desktop and mobile sizes.

## Change and verification rules

1. Look for an existing shadcn primitive before writing a new interactive element. Add missing primitives with `npx shadcn@latest add <name>` from `web/`, review the generated source, and keep the project aliases and theme configuration aligned with `components.json`.
2. Prefer semantic tokens and reusable shadcn variants over one-off colors, repeated style strings, or raw HTML controls. Extend a local variant when a product state recurs.
3. Keep shadcn-generated files locally owned and reviewable. Do not replace a whole component with a registry snippet without checking its React, accessibility, and Tailwind versions.
4. Keep accessibility behavior, provider consent, run provenance, and truthful persisted progress intact while changing appearance.
5. For a UI change, run `npm test`, `npm run lint`, `npm run typecheck`, and `npm run build` from `web/` (or `make validate` for the full stack). Exercise changed flows in a browser at desktop and mobile widths and run an accessibility audit for significant interaction/layout changes.

### Official references

- [shadcn/ui CLI](https://ui.shadcn.com/docs/cli)
- [Next.js installation](https://ui.shadcn.com/docs/installation/next)
- [Tailwind CSS v4](https://ui.shadcn.com/docs/tailwind-v4)
- [Theming](https://ui.shadcn.com/docs/theming)
- [Components](https://ui.shadcn.com/docs/components)
