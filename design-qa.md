# Design QA

## Source and captures

- Source: the user's browser annotations, including alternating carriage motion, a train directly on the bridge, default right-to-left bridge motion, a circular locomotive light, visible background stars, and a darker sky.
- Desktop visual review: the local Astro preview at `http://127.0.0.1:4321/`, viewed in the Codex In-app Browser at 1762 × 1190 CSS px.
- Mobile visual review: the same page at 390 × 844 CSS px. The route fits all five station labels, and the page and route map have no horizontal overflow.
- Final hero recapture: the local Astro preview was reviewed in the Codex In-app Browser at desktop size. The circular headlamp now casts a soft beam to the right, along the locomotive's facing direction.
- Current hero review: the new landscape connects the right mountain range through forest and shoreline into the lake. The original bridge artwork repeats across the viewport, and the train rests on its deck.

### Architecture game-view update

- Source: the user's request to present the existing architecture flow as an 8-bit game view; the prior diagram supplied the stage order and subject matter.
- Desktop visual review: `http://127.0.0.1:4321/#architecture` in the Codex In-app Browser at 1762 × 1190 CSS px. The document width is 1747 px within the 1762 px viewport.
- Mobile visual review: the same section at 390 × 844 CSS px. All eight stages remain in a two-column layout, and the document width is 375 px within the 390 px viewport.
- The inline desktop and mobile browser captures were reviewed in this session; the browser tool does not persist those captures as files.

## Fidelity and interaction

- **Palette:** The hero and route scene use the shared deep railway blue `--ptr-hero-sky: #153B62`; paper, ink, and station colors remain shared design-system tokens.
- **Sky details:** Pixel stars are visible but sparse, with quiet twinkle; the train sprite is cropped before its rectangular beam and gets a circular headlamp with a soft right-facing glow.
- **Hero composition:** The product name, tagline, description, and actions are centered in the viewport. A compact train sits near the lower edge, directly on the bridge deck. There is no separate track/tie strip.
- **Reference-led landscape:** The hero uses a night landscape inspired by `hero-night-train.webp`, with mountains descending into a forested shoreline and reflective lake. The image is slightly transparent, with a soft blue fog layer over the mountains to reduce their contrast behind the trail and bridge.
- **Previous bridge asset:** The bridge continues to use a repeating arch span from `bridge.png`. The tile is scaled to 219px on desktop and 195px on mobile, with the background crop scaled to keep the repeating edges joined. Sixteen spans cover wide viewports; the track loops left every 40 seconds behind a horizontally anchored train, independent of scroll.
- **Independent layers:** Moon, clouds, stars, bridge, and train remain separate CSS/Astro layers. The star, moon, cloud, bridge, and carriage animations respect `prefers-reduced-motion` while keeping the scene visible.
- **Train animation:** `TrainConsist.astro` slices the train sprite into four segments and staggers their small movement. The hero headlamp gently dims occasionally. The bridge itself scrolls from right to left in the hero and journey scene.
- **Journey interaction:** The route reuses the hero bridge and train artwork. Choosing or scrolling to a station updates the active station and moves the train to its marker while the bridge drifts left behind it. Browser review confirmed the train moves to Evidence and Your next step; the bridge parallax offset progressed from about -54 px to -106 px while scrolling.
- **Responsive layout:** At 390 × 844, all five stations remain visible in the map without horizontal scrolling; the page has no horizontal overflow.
- **Architecture diagram:** Eight pixel-art service landmarks now sit in sequence on a continuous blue stone railway bridge, with a warm gold data path that matches the hero bridge's masonry, pixel scale, and palette. Worker and provider processing remains inside a dashed zone. Stage names and descriptions remain selectable HTML, while consent and human review remain distinct notes.
- **Architecture art review:** The new transparent asset `homepage/public/images/architecture/paper-trail-night-bridge.png` was reviewed in the local preview at desktop size and at 390 × 844 CSS px. The bridge fits the existing wide figure, and the eight stage descriptions remain in the mobile two-column layout. Animated gold packets are aligned with the new bridge-deck path.
- **Console:** No browser console warnings or errors appeared during review.

### Hero scale and atmosphere refinement

- Source: the user's request to enlarge the stars, reduce the bridge scale, and soften the mountain landscape with fog and opacity.
- Stars use larger source dimensions (20–28px) while retaining the existing 8-bit cross shape and blink timing.
- Desktop visual review: `http://127.0.0.1:4321/#top` at 1762 × 1190 CSS px. The bridge deck begins at about 986px and the train ends at about 982px, keeping the train seated on the smaller bridge.
- Mobile visual review: the same hero at 390 × 844 CSS px. The deck begins at about 653px and the train ends at about 651px; the scaled bridge crop keeps the deck aligned.
- Browser console check: no warnings or errors.

## Build checks

- `mise exec -- pnpm --dir homepage build` passed after the star, bridge-scale, and mountain-fog refinement.
- `mise exec -- pnpm --dir web test` passed (44 tests); web lint, typecheck, and production build passed during the shared design-system review.
- `mise exec -- docker compose -f infra/docker-compose.yml config --quiet` passed.
- `git diff --check` passed after the final edits.
- The homepage package has no test script; its production build passed.

final result: passed
