# Paper T-Rail Homepage

The public marketing site is a statically rendered Astro service. It introduces the Paper T-Rail evidence route and links into the Next.js research workspace.

## Run locally

```sh
mise exec -- pnpm --dir homepage install
make homepage-dev
```

The local site runs at <http://127.0.0.1:4321>. Setup calls to action link to the Paper T-Rail GitHub repository in a new tab. `PUBLIC_WORKSPACE_URL` controls direct links into the Next.js workspace, such as the review workspace example; it defaults to `http://127.0.0.1:3000`. Set it to the workspace origin for local previews or deployment builds.

`make dev` also starts the homepage in Compose. The production container builds the static Astro output and serves it with Nginx.

## Cloudflare Workers

The homepage can also deploy as a static-assets Worker. Set the Cloudflare project root directory to `homepage/`; Astro builds the files into `dist/`, which `wrangler.jsonc` configures as the Worker assets directory. Set `PUBLIC_WORKSPACE_URL` in the Cloudflare build environment to the deployed workspace URL when it differs from the local default. No server adapter is needed because the site is statically rendered.

### Web Analytics

To enable Cloudflare Web Analytics, add the deployed homepage hostname as a site in Cloudflare Web Analytics and set its token as the `PUBLIC_CLOUDFLARE_WEB_ANALYTICS_TOKEN` build environment variable for the homepage Worker. Astro embeds the public token in the generated HTML, so set it in the build environment (a Wrangler runtime variable alone is not enough). Use this site's token, not the token from another site's analytics property. The beacon is omitted when the variable is unset, including on local builds, and is included on both the homepage and the custom 404 page.

## Design source

Import shared colors, type stacks, spacing, and shape values from `@paper-t-rail/design-system/tokens.css`. Product voice, layout, interaction, and accessibility rules are in [`docs/ui-design-system.md`](../docs/ui-design-system.md); implementation notes are in [`AGENTS.md`](AGENTS.md).
