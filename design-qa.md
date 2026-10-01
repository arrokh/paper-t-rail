# Design QA

## Source and captures

- Source: the user's browser annotations, including alternating carriage motion, a train directly on the bridge, default right-to-left bridge motion, a circular locomotive light, visible background stars, and a darker sky.
- Desktop visual review: the local Astro preview at `http://127.0.0.1:4321/`, viewed in the Codex In-app Browser at 1762 × 1190 CSS px.
- Mobile visual review: the same page at 390 × 844 CSS px. The route fits all five station labels, and the page and route map have no horizontal overflow.
- Final hero recapture: the local Astro preview was reviewed in the Codex In-app Browser at desktop size. The circular headlamp now casts a soft beam to the right, along the locomotive's facing direction.
- Current hero review: the new landscape connects the right mountain range through forest and shoreline into the lake. The original bridge artwork repeats across the viewport, and the train rests on its deck.

### Five-stage pipeline and full-screen section revision

- Source: browser annotations asking for full-viewport homepage sections with alternating paper backgrounds, and an 8-bit zigzag diagram that matches the five stages in the web Analysis Run pipeline.
- The homepage diagram now shows Read the PDF, Resolve references, Acquire cited sources, Prepare evidence, and Assess evidence in that order. Each stage has its own SVG asset and staggered animation; the connector takes a right-angle route through the cards on desktop and a vertical route on mobile.
- Desktop visual review: local Astro preview at `http://127.0.0.1:4322/#architecture`, 1280 × 720 CSS px. The 01→02 top row and 03→04 return row follow the right-angle track; stage 05 continues below the fold. The section remains readable as the user scrolls through its full content.
- Mobile visual review: local Astro preview at `http://127.0.0.1:4322/#architecture`, 390 × 844 CSS px. The five cards stack on a centered gold route, and the document width is 375 px within the 390 px viewport.
- Hero review at 1280 × 720 and 390 × 844 CSS px confirmed the clouds sit above the title, the title and train remain fixed while separate fog layers parallax around them, and the train meets the bridge deck. The headlamp was observed in its off state; its runtime switches randomly between on and off at intervals no longer than 15 seconds.
- The workspace and homepage use the same Fraunces Paper T-Rail wordmark, white “P” favicon on a sky-blue background, centered one-line footer identity, shared paper palette, and shared font tokens. The homepage hero and header setup CTAs use the same red fill and bright-paper text.
- The mobile page and the Paper Review workspace have no horizontal overflow at 390 CSS px.
- The inline desktop and mobile browser captures were reviewed in this session; the browser tool does not persist those captures as files.

## Fidelity and interaction

- **Palette:** The hero and route scene use the shared deep railway blue `--ptr-hero-sky: #153B62`; paper, ink, and station colors remain shared design-system tokens.
- **Sky details:** Pixel stars are visible but sparse, with quiet twinkle; the train sprite is cropped before its rectangular beam and gets a circular headlamp with a soft right-facing glow.
- **Hero composition:** The product name, tagline, description, and actions are centered in the viewport. A compact train sits near the lower edge, directly on the bridge deck. There is no separate track/tie strip.
- **Reference-led landscape:** The hero uses a night landscape inspired by `hero-night-train.webp`, with mountains descending into a forested shoreline and reflective lake. The image is slightly transparent, with a soft blue fog layer over the mountains to reduce their contrast behind the trail and bridge.
- **Previous bridge asset:** The bridge continues to use a repeating arch span from `bridge.png`. The tile is scaled to 219px on desktop and 195px on mobile, with the background crop scaled to keep the repeating edges joined. Sixteen spans cover wide viewports; the track loops left every 40 seconds behind a horizontally anchored train, independent of scroll.
- **Independent layers:** Moon, clouds, stars, bridge, and train remain separate CSS/Astro layers. The star, moon, cloud, bridge, and carriage animations respect `prefers-reduced-motion` while keeping the scene visible.
- **Train animation:** `TrainConsist.astro` slices the train sprite into four segments and staggers their small movement. Passenger lights switch off at random, with each dark interval lasting no more than 30 seconds. The headlamp and beam animate with the locomotive; the train sprite is cropped before its baked-in beam.
- **Responsive layout:** At 390 × 844, all five stations remain visible in the map without horizontal scrolling; the page has no horizontal overflow.
- **Architecture diagram:** Five pixel-style stage assets now map one-to-one to the five Analysis Run pipeline stages. Their labels and descriptions remain selectable HTML, while consent and human review remain distinct notes.
- **Architecture art review:** The stage-specific transparent SVGs under `homepage/public/images/architecture/` were reviewed in the local preview at desktop and mobile sizes. The desktop cards follow the alternating horizontal route; mobile uses a vertical gold connector without horizontal overflow.
- **Console:** No browser console warnings or errors appeared during review.

### Hero scale and atmosphere refinement

- Source: the user's request to enlarge the stars, reduce the bridge scale, and soften the mountain landscape with fog and opacity.
- Stars use larger source dimensions (20–28px) while retaining the existing 8-bit cross shape and blink timing.
- Desktop visual review: `http://127.0.0.1:4321/#top` at 1762 × 1190 CSS px. The bridge deck begins at about 986px and the train ends at about 982px, keeping the train seated on the smaller bridge.
- Mobile visual review: the same hero at 390 × 844 CSS px. The deck begins at about 653px and the train ends at about 651px; the scaled bridge crop keeps the deck aligned.
- Browser console check: no warnings or errors.

### Homepage annotation revision

- Passenger-car dimming overlays are oversized slightly around each sprite window so no lit edge remains visible. The train sprite crop ends before its baked-in headlight beam; the CSS beam and round headlamp bob with the locomotive. Motion is disabled for reduced-motion preferences.
- Cloud loops retain varied speeds and the farthest layer stays slowest; durations are now 215 to 700 seconds, about 60% slower than before. The title, lake, and train fog layers drift slowly from right to left and respect reduced-motion preferences.
- The hero setup CTA matches the navbar CTA's red fill, paper text, and hover color. The closing setup section is removed, and the FAQ height follows its content instead of filling the viewport.
- The loading cover uses a system serif so it cannot flash from fallback to the downloaded display font. The landing content remains covered until page fonts and images settle.
- The sample journey is replaced by a lazy, non-autoplaying privacy-enhanced YouTube embed under “How it works.” The About section includes a small claim-to-evidence pixel trail without changing the content hierarchy.
- Visual review at 390 × 844 CSS px confirmed the About trail fits in four equal columns (89px each), with no horizontal overflow. The How it works iframe measures 352 × 197px (1.79:1) and stays inside the viewport; the player shows a Play control and uses `autoplay=0`.
- The mobile hero at 390 × 844 CSS px keeps the train within the viewport and seated on the bridge deck. Randomly darkened carriage windows are fully opaque, and the bright headlamp and beam follow the locomotive's staggered vertical animation.
- PDF review verified the selected claim and its Aghzal citation marker on page 10. Other citation markers in the same sentence, including Valmeekam and Wei et al., remain unhighlighted. Switching to Paper Review and clicking Focus Paper Review both placed the review card at the same viewport position (8px from the top).
- Analysis Pipeline quick navigation uses each stage's status color, with a visible selected-stage outline. Local web, homepage, and API endpoints returned HTTP 200 and remain running for continued review.

## Build checks

- `mise exec -- pnpm --dir homepage build` passed after the star, bridge-scale, and mountain-fog refinement.
- `mise exec -- pnpm --dir web test` passed (44 tests); web lint, typecheck, and production build passed during the shared design-system review.
- `mise exec -- docker compose -f infra/docker-compose.yml config --quiet` passed.
- `make local web homepage` reused the existing host processes for this worktree and returned without spawning duplicate servers; web, homepage, and API health endpoints responded with HTTP 200.
- PDF review verified the selected claim and Aghzal citation marker while excluding unrelated citations, and the Paper Review tab and focus button landed at the same position.
- `git diff --check` passed after the final edits.
- The homepage package has no test script; its production build passed.

final result: passed
