# Homepage Service Instructions

Use the shared [Paper T-Rail UI design system](../docs/ui-design-system.md) and import its token package from `@paper-t-rail/design-system/tokens.css`. Keep visual foundations shared with `web/`, while the marketing site may use the approved 8-bit transit-poster treatment.

## Astro implementation

- Keep the homepage statically rendered with Astro. Use semantic HTML and native elements such as links, `details`, and `summary`.
- Keep decorative hero motion in CSS. Add browser JavaScript only for essential interactions; keep it small, progressive, keyboard accessible, and respectful of `prefers-reduced-motion`.
- Use the shared token package for colors, typography, spacing, and focus. Use the local pixel-art icon assets from `public/icons/` for the retro accents.
- Use `PUBLIC_WORKSPACE_URL` for links into the Next.js workspace. The local default is `http://127.0.0.1:3000`.
- Use “Set up the workspace” for the homepage's primary setup calls to action; link them to the GitHub project in a new tab. Keep direct application links on `PUBLIC_WORKSPACE_URL`.
- Keep product copy accurate: the Evidence Coverage Report is a human triage aid, candidate passages are for inspection, and researchers make the review decision.
- Follow WCAG 2.2 AA, preserve visible focus, keep text contrast legible, and keep the hero and architecture diagram readable on desktop and mobile.

## Commands

- `mise exec -- pnpm --dir homepage dev`
- `mise exec -- pnpm --dir homepage build`
