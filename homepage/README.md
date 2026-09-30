# Paper T-Rail Homepage

The public marketing site is a statically rendered Astro service. It introduces the Paper T-Rail evidence route and links into the Next.js research workspace.

## Run locally

```sh
mise exec -- pnpm --dir homepage install
make homepage-dev
```

The local site runs at <http://127.0.0.1:4321>. Setup calls to action link to the Paper T-Rail GitHub repository in a new tab. `PUBLIC_WORKSPACE_URL` controls direct links into the Next.js workspace, such as the review workspace example; it defaults to `http://127.0.0.1:3000`. Set it to the workspace origin for local previews or deployment builds.

`make dev` also starts the homepage in Compose. The production container builds the static Astro output and serves it with Nginx.

## Design source

Import shared colors, type stacks, spacing, and shape values from `@paper-t-rail/design-system/tokens.css`. Product voice, layout, interaction, and accessibility rules are in [`docs/ui-design-system.md`](../docs/ui-design-system.md); implementation notes are in [`AGENTS.md`](AGENTS.md).
