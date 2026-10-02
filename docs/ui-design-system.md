# Paper T-Rail UI Design System

This document defines the shared product voice, visual language, accessibility, and responsive behavior for the public Astro homepage and the Next.js research workspace. The token source is [`packages/design-system/tokens.css`](../packages/design-system/tokens.css); `homepage/AGENTS.md` and `web/AGENTS.md` apply the rules to their services. The technical design links here from §41.

## Product posture

Paper T-Rail is an academic evidence engine, not a generic analytics dashboard. It helps researchers trace a claim through its citation and cited paper to candidate evidence, then decide what to review. Use clear, precise language and show provenance and system boundaries. The public homepage may feel like a modern 8-bit railway game; the research workspace stays calm and reading-first while sharing the same colors, typography, and interaction rules.

The brand connects a research paper trail to a **Traceable Rail**. The paper trail is the source path behind a citation-backed research claim; the train and track represent a route a researcher can follow through the citation, cited paper, and candidate evidence. Keep this route visible in the product story: **Claim → Citation → Cited paper → Evidence passage → Your next step**.

Product copy must preserve these boundaries:

- Candidate passages are surfaced for researcher inspection; retrieval is not an automatic match or verdict.
- The Evidence Coverage Report is a human triage aid, not truth certification, paper grading, or an assessment of the whole paper.
- V1 supports English, text-based academic PDFs. State that scope where a user chooses whether to begin.
- External-provider use requires disclosure and the applicable per-run consent. Keep machine results distinct from Human Review.

## Service boundaries

- **`homepage/`:** Astro static public site. It explains product scope, a claim-to-evidence example, and system architecture; setup calls to action link to the GitHub project, while direct app links use `PUBLIC_WORKSPACE_URL`. Keep HTML semantic and statically rendered; use CSS for decorative hero motion and avoid scroll-linked animation scripts.
- **`web/`:** Next.js App Router research workspace. Use React, strict TypeScript, Tailwind CSS v4, the same-origin API proxy, and TanStack Query for remote state.
- **Shared design package:** both services import `@paper-t-rail/design-system/tokens.css`. Put shared palette, fonts, spacing, and shape values there. Components use semantic tokens instead of local hex values.

In `web/`, shadcn/ui uses the accepted `base` (Base UI) component set. Components are generated into `web/components/ui/` and committed as project-owned source; there is no opaque runtime shadcn component package. Use the established `Button`, `Card`, `Badge`, `Alert`, `Checkbox`, `Field`, `Input`, `NativeSelect`, `Tabs`, `Collapsible`, `Separator`, `Skeleton`, and `Spinner` patterns. Astro uses native links, buttons, `details`, and `summary` where needed; do not add React or a component runtime just for static content.

Keep web route composition and its same-origin `/api/v1/*` proxy in `web/app/`. Keep product UI, remote-state queries/mutations, and feature types together under `web/features/<feature>/`; shared `web/lib/` modules must be genuinely cross-feature. Keep route/layout modules server-rendered and add client state only to the smallest module that needs browser interaction. Do not use Effects or hand-managed intervals for remote fetching, polling, or mutation refresh; Effects remain appropriate for genuine synchronization with external systems.

## Shared identity and art direction

The services share one brand foundation, not one page layout. Keep the Paper T-Rail wordmark, Fraunces display type, IBM Plex Sans interface type, paper-and-ink reading palette, red primary action, fine paper-edge rules, compact shapes, and clear evidence-route language consistent. The homepage may use the 8-bit night-rail illustration to explain the product; the workspace should remain a quiet reading surface for documents, claims, references, and evidence.

The homepage's pixel art is a specific illustration system, not a general UI skin. Its active scene is assembled from independent layers: the fogged mountain-and-lake backdrop (`homepage/public/images/hero/hero-landscape-lake-fog.webp`), foothills, moon, clouds, stars, the repeating stone bridge, and the train. Its five Analysis Run stages use the stage-specific SVGs in `homepage/public/images/architecture/`, shown as a navigable stack of train tickets. Keep each stage name and explanation as selectable HTML text. Small route icons live in `homepage/public/icons/`; their license and source notes are in that directory's README. The Astro markup and CSS identify which files are active; other files in the asset folders may be alternate art or source material.

Apply the illustration rules when adding or revising art:

- Keep pixel edges crisp and use the shared night blue, paper, ink, and route accents. Do not mix photographic textures, glossy gradients, or unrelated icon styles into the pixel scene.
- Keep the hero's foreground name, message, and train route readable. Keep the bridge under the train, the headlamp circular and facing right, and the fog subtle enough to soften the landscape without obscuring the evidence route.
- Keep the full-screen loading cover in the same night-to-sky-to-ground palette as the hero, using the shared `--ptr-night`, `--ptr-hero-sky`, and `--ptr-hero-ground` tokens instead of a separate flat blue.
- Use decorative imagery as a visual explanation. Keep labels, stage names, status, consent details, and evidence copy as selectable HTML with meaningful accessible text; do not bake essential information into an image.
- Keep the full night landscape, animated clouds, moon, fog, train, and bridge in `homepage/`. In `web/`, use a small route illustration only when it explains a research task or provenance relationship. Never put the game scene behind uploaded documents or dense evidence text.
- The homepage wordmark is text, without a map-pin mark. Use Fraunces and the ink color for the in-product wordmark as well; reserve `web/public/rail.svg` for the browser tab icon. Static SVG icon fills must mirror the matching shared token values because an external CSS token cannot style a browser tab icon.

The workspace can use the same editorial headings, warm canvas, paper cards, route colors, line dividers, and compact controls. Use cards to group real tasks or related evidence, not as decorative containers around every block. Keep workspace data more prominent than the brand illustration.

The implementation-specific desktop/mobile captures and accepted hero behaviors are recorded in [`design-qa.md`](../design-qa.md). Update those notes when a change affects art assets, scene geometry, animation, or responsive overflow; the design system remains the normative source for future work.

## Visual foundations

### Color tokens

The palette lives in `packages/design-system/tokens.css`. Services import the package and map their components to semantic roles; they do not define competing brand palettes.

| Token | Value | Use |
|---|---:|---|
| `--ptr-paper` | `#F7F0DE` | Main reading canvas and warm paper ground |
| `--ptr-paper-bright` | `#FFFAF0` | Cards and foreground text on dark hero art |
| `--ptr-paper-soft` | `#EEE3CC` | Quiet warm panels and secondary surfaces |
| `--ptr-paper-muted` | `#F1E9D8` | Low-emphasis section backgrounds |
| `--ptr-paper-gold` | `#F3E5BD` | Highlight surfaces paired with ink text |
| `--ptr-paper-blue` | `#E4EDF0` | Blue-tinted citation markers and notes |
| `--ptr-paper-teal` | `#DCE5DB` | Teal-tinted human-review notes |
| `--ptr-ink` | `#08283B` | Main body text and outlines |
| `--ptr-night` | `#06283B` | Train-night hero and dark surfaces |
| `--ptr-hero-sky` | `#153B62` | Deep railway blue for the homepage's pixel-train scene |
| `--ptr-hero-ground` | `#13203D` | Dark ground beneath the homepage railway |
| `--ptr-muted` | `#52616A` | Secondary text and metadata |
| `--ptr-red` | `#C23B2B` | Primary actions and claim station; accessible on paper |
| `--ptr-gold` | `#EAAA38` | Route markers and warm accents; use ink text over it |
| `--ptr-gold-ink` | `#8E5D0E` | Gold station text on light surfaces |
| `--ptr-teal` | `#12666B` | Cited-paper station and secondary actions |
| `--ptr-blue` | `#2B6A91` | Evidence station and keyboard focus |
| `--ptr-sage` | `#87977E` | Quiet route details |
| `--ptr-border` | `#D2C7AE` | Paper-edge borders and separators |

Keep text contrast legible: the primary red has at least 4.5:1 contrast with the paper canvas and bright paper text; gold is an accent fill and takes dark ink text. Status and station selection always use a label or shape as well as color. V1 uses the light paper workspace and the night hero; do not add a theme toggle without a separate product decision.

In `web/`, map `info` to the paper-blue surface with blue text, `success` to the paper-teal surface with deep-teal text, `warning` to the paper-gold surface with ink text, and `destructive` to the red error treatment. Use these semantic roles for notices and result states so action red does not imply success; retain a visible label or icon alongside each color.

### Type and spacing

- Use **Fraunces** for the brand and editorial display headings and **IBM Plex Sans** for interface and body copy; use the shared monospace stack for hashes, versions, offsets, and compact technical metadata.
- **Press Start 2P** is reserved for short 8-bit labels and station markers on the homepage. Never use pixel type for paragraphs, instructions, or dense workspace data.
- Use a consistent type scale with comfortable body line-height. Long filenames, hashes, reference text, and other untrusted content must wrap instead of widening the workspace.
- Use a 4px base spacing unit and a compact radius/elevation scale. Prefer clear borders and occasional pixel-style offset shadows; avoid stacked card-within-card decoration.
- Use the pixel-art icon set only for small, named route and architecture accents. Pair meaningful icons with text; hide decorative icons from assistive technology.

| Foundation | Shared token | Application |
|---|---|---|
| Display and wordmark | `--ptr-font-display` | Fraunces for the product wordmark and editorial headings; keep long body copy in the sans-serif face. |
| Interface and body | `--ptr-font-body` | IBM Plex Sans for paragraphs, controls, labels, navigation, and workspace content. |
| Technical metadata | `--ptr-font-mono` | Monospace for IDs, hashes, source offsets, and compact provenance labels. |
| Pixel labels | `--ptr-font-pixel` | Short decorative labels on the homepage only; never use for instructions or workspace data. |
| Spacing rhythm | `--ptr-space-unit: 4px` | Compose spacing in 4px increments; Tailwind's default spacing scale already follows this rhythm. |
| Control corner | `--ptr-radius-control: 4px` | Base radius for inputs and buttons. Web's shadcn radius aliases derive from this value; full pills are reserved for compact status badges. |
| Keyboard focus | `--ptr-focus` | Visible focus outline/ring on links, buttons, fields, tabs, and disclosure controls. |

The shared CSS file defines foundations rather than page-specific component styles. Keep the Astro hero and marketing layout in `homepage/src/styles/landing.css`; do not import it into Next.js. Keep Next.js component treatment in project-owned shadcn primitives and feature composition, using the shared tokens through `web/app/styles.css`.

### Applying the system in `web/`

`web/app/styles.css` imports `@paper-t-rail/design-system/tokens.css`, assigns the shared type stacks in Tailwind's `@theme`, and maps shadcn's semantic variables (`--background`, `--foreground`, `--primary`, `--muted`, `--border`, `--ring`, and related roles) onto Paper T-Rail tokens. Preserve this mapping when editing the web theme.

- Prefer semantic classes such as `bg-background`, `text-foreground`, `bg-card`, `text-muted-foreground`, `border-border`, `bg-primary`, `text-primary-foreground`, and `ring-ring`. Custom CSS must reference `--ptr-*` or the mapped semantic variables instead of adding a competing hex palette.
- Use `font-heading` or `font-serif` for editorial headings and `font-sans` for UI text. Use `font-mono` only for technical identifiers and provenance. Do not load or apply the pixel font in the workspace.
- Use the local `Button`, `Card`, `Badge`, `Alert`, `Field`, `Input`, `NativeSelect`, `Checkbox`, `Tabs`, `Collapsible`, `Separator`, `Skeleton`, and `Spinner` primitives for their established interaction patterns. Use Tailwind for layout and spacing; extend a primitive variant when a visual state recurs instead of inventing one-off controls.
- Use the shared red as the primary action with bright-paper text on paper surfaces and the dark blue homepage hero. Keep the hero and navbar setup calls to action visually consistent. Keep secondary actions quiet and outlined or textual. Do not put white text on gold.
- Use paper, bright paper, and soft paper for the page, card, and quiet-section surfaces. Reserve blue, teal, gold, and red for route meaning, focus, notice, and action states. A state must include a text label or icon with a name, not color alone.
- Keep the web app light and reading-first. Use the same compact radius and visible dividers, but preserve enough space around source text and evidence passages. Avoid game scenery, blinking decoration, and pixel labels in upload, parsing, and review flows.
- Keep buttons, links, and fields visibly focused and comfortably operable by keyboard and touch. Respect reduced motion; never animate a status or progress value that the server has not reported.

### Homepage composition

- Lead with the night-train image, clear product name, the tagline “A traceable paper trail from claim to cited evidence,” V1 scope, and a “Set up the workspace” link to the GitHub project that opens in a new tab. Hide the header over the initial hero; reveal it with a short slide-in after the hero leaves the viewport.
- Explain the name as the paper trail behind a research claim and the Traceable Rail that carries the researcher through its sources. Keep the conceptual order clear in copy and examples: Claim, Citation, Cited paper, Evidence passage, Your next step.
- Keep the night-train hero as the main illustration and use an otherwise minimal editorial layout: generous paper space, fine separators, restrained color, and no stacked decorative panels. The hero fills the viewport below the navigation; keep the train and bridge compact near the lower edge so the name and message have room to breathe. Render moon, clouds, blinking stars, right-side mountains, bridge, lake, and train as independent pixel-art layers over the shared deep-blue hero-sky token. Keep the locomotive's headlamp circular and soft, not a rectangular beam. The train sits directly on the bridge deck; do not add a second track or tie layer. Preserve clear sky to the left of the mountains. Keep its typography editorial and its scenery sparse; avoid game-franchise motifs, excessive scenery, or arcade-like UI. Remove the hero background from the closing setup call to action.
- Run the bridge loop continuously from edge to edge using a tile whose left and right edges meet cleanly. Move cloud layers on slow repeating loops at different speeds, with a very subtle mountain drift. Keep the train horizontally anchored while its carriages bob in a gentle staggered sequence; add a low-contrast reflection in the lake and keep the headlamp's soft dimming occasional. Keep all motion nonessential and disable it for `prefers-reduced-motion`.
- Show one clearly illustrative claim-to-candidate-passage example, then a five-stage Analysis Run ticket stack with accessible previous/next controls and clear consent/human-review boundaries. Keep the sequence understandable as public homepage, workspace, API, queue, worker/providers, evidence record, coverage report, and researcher review.
- Keep the retro treatment in the train, route, selected pixel-art icons, and precise interactions; set labels and body copy in the shared sans-serif UI type. Maintain generous reading space and avoid arcade-like sound, flashing, or forced scroll effects.

## Workspace composition

The Next.js upload workspace keeps three task-oriented cards: **01 Source Document** and **02 Persisted Progress** share the desktop row; **03 Parsed Document** spans the full width below. At narrow widths, cards stack in that reading order. Use a centered, readable content width and preserve generous space for source text and references.

- **Source Document:** provider choices, exact per-run external-data disclosure and consent, file selection, upload action, and concise privacy/error feedback. Use a project-owned shadcn Button to activate the native file input and display its selected filename in a consistently aligned field.
- **Persisted Progress:** cursor-paginated Analysis Runs, explicit selection, current status, and loading/empty states. Keep the page indicator at the lower left and navigation controls at the lower right; omit Previous on the first page.
- **Parsed Document:** selected run status, current persisted progress and provenance, then the parsed sections, Citation Contexts with their Atomic Claims and inferred/provisional Citation Target links, and Bibliography Entries. Keep the source context and source span visible with each claim; target links navigate to their bibliography entries, and each entry links back to every citing Citation Context. Briefly highlight anchor destinations after navigation, respecting `prefers-reduced-motion`. Tabs and arrow controls expose only states supported by the selected run; do not imply stage history that is not stored.

Keep product copy factual and non-alarmist. Distinguish privacy/consent decisions from ordinary validation. Error, loading, empty, disabled, and success states are first-class designs, not afterthoughts.

## Interaction and accessibility

Target WCAG 2.2 AA for the rendered interface.

- Use the shadcn primitive's built-in keyboard and focus behavior in `web/`. Keep visible `:focus-visible` styling and ensure all workflows work without a pointer; the homepage route anchors and FAQ must also work by keyboard.
- Associate every control with a visible label. Use native buttons for actions and links for navigation; when styling a Next.js link as a button, compose `buttonVariants` onto the link rather than changing its role.
- Preserve semantic heading order, landmarks, lists, field groups, and tab/tabpanel relationships. Use live announcements only for meaningful asynchronous status changes.
- Never communicate status, selection, or validation solely through color or iconography. Keep text contrast legible on all token surfaces.
- Respect `prefers-reduced-motion`; animations must be nonessential and never block feedback. Hero scenery and architecture data-flow motion must stop when reduced motion is preferred.
- On small screens, maintain readable content widths, minimum touch targets, and no horizontal page overflow. Test the workspace at desktop and mobile sizes.

## Change and verification rules

1. Look for an existing shadcn primitive before writing a new interactive element. Add missing primitives with `npx shadcn@latest add <name>` from `web/`, review the generated source, and keep the project aliases and theme configuration aligned with `components.json`.
2. Prefer semantic tokens and reusable shadcn variants over one-off colors, repeated style strings, or raw HTML controls. Extend a local variant when a product state recurs.
3. Keep shadcn-generated files locally owned and reviewable. Do not replace a whole component with a registry snippet without checking its React, accessibility, and Tailwind versions.
4. Keep accessibility behavior, provider consent, run provenance, and truthful persisted progress intact while changing appearance.
5. For a UI change, run `mise exec -- pnpm --dir web test`, `mise exec -- pnpm --dir web run lint`, `mise exec -- pnpm --dir web run typecheck`, and `mise exec -- pnpm --dir web run build` (or `make validate` for the full stack). Exercise changed flows in a browser at desktop and mobile widths and run an accessibility audit for significant interaction/layout changes.

### Official references

- [shadcn/ui CLI](https://ui.shadcn.com/docs/cli)
- [Next.js installation](https://ui.shadcn.com/docs/installation/next)
- [Tailwind CSS v4](https://ui.shadcn.com/docs/tailwind-v4)
- [Theming](https://ui.shadcn.com/docs/theming)
- [Components](https://ui.shadcn.com/docs/components)
- [Astro client-side scripts](https://docs.astro.build/en/guides/client-side-scripts/)
- [Astro build and preview](https://docs.astro.build/en/reference/cli-reference/)
