# Design QA

## Source and captures

- Source: the user's browser annotations for the night train scene and the follow-up request to keep the bridge visible and correctly aligned across viewports.
- Hero visual review: the local Astro preview at `http://127.0.0.1:4321/` was checked at 1280 × 710, 820 × 1180, 768 × 1024, and 390 × 844 CSS px. The bridge remains visible, its deck stays 4px below the train across the measured sizes, and the page has no horizontal overflow.
- At 1280 × 710 CSS px, the bridge begins at about y=415px and the train ends at about y=607px; the deck is at about y=611px. At 768 × 1024, the deck is at about y=881px and the train ends at about y=877px. At 390 × 844, the deck is at about y=726px and the train ends at about y=722px.
- The circular locomotive headlamp casts a soft beam in the direction the locomotive faces. The landscape connects the mountain range through forest and shoreline into the lake; the repeating bridge artwork spans the viewport beneath the train.
- 2026-10-02 refinement: a mirrored bridge layer now reflects into the lake at low opacity and fades below the waterline; a separate drifting fog layer fills the water beneath the bridge while remaining behind its structure. Astro production build and the layout detector passed. A fresh browser capture is pending because the preview tab was blocked by browser security policy.
- 2026-10-02 follow-up: the bridge reaches the hero viewport bottom and the train stays on its deck at 1280 × 720, 820 × 1180, and 390 × 844 CSS px. Passenger-window and locomotive-headlamp glow are reduced. Section anchors were briefly moved onto the headings, then restored to the section elements in the later section-rhythm revision below.
- 2026-10-02 section-rhythm refinement: homepage sections share the available vertical space below the revealed navigation; mobile navigation anchors still target each section. The minimum section height accounts for the navigation offset, padding scales with viewport height, and the walkthrough width scales on short screens to keep the 16:9 preview visible. Visual review: 390 × 667 and 390 × 844 CSS px show the How it works title and preview together; all FAQ rows fit at 390 × 844; 820 × 1180 shows the Architecture ticket, controls, and notes; 1280 × 720 shows the walkthrough fully in frame. Architecture keeps its natural overflow on short viewports, with its ticket controls visible and notes below. About and FAQ now use natural content height instead of this viewport-height minimum.
- 2026-10-02 desktop spacing refinement: homepage section padding now scales with viewport height (44–72px), and the section content distributes through the available space. At 1280 × 720, the How it works preview fits below its heading without the previous oversized top gap.
- 2026-10-02 scroll-navigation refinement: the revealed header now accounts for a 12px buffer beyond its measured height, matching the mobile section anchor offset. Learn more and About both land with the header visible at 390 × 844 and 820 × 1180; the about section starts at y=96px and y=70px respectively, and the mobile page has no horizontal overflow.
- 2026-10-02 FAQ refinement: questions keep content-sized rows instead of spreading to fill the section. The list is capped at 580px on desktop; at 390 × 844, the heading and all four questions fit without horizontal overflow.
- 2026-10-02 mobile anchor-gap refinement: section anchors now use the measured 86.5px two-row header height instead of 96px. At 390 × 844 after opening Architecture, the section starts at y=86.58px and the header ends at y=86.5px, removing the visible paper strip while retaining the safe-area inset.

### Five-stage pipeline and full-screen section revision

- Source: the user's request to present the five Analysis Run stages as a stack of train tickets, with previous/next controls below it.
- The Architecture section presents Read the PDF, Resolve references, Acquire cited sources, Prepare evidence, and Assess evidence as five layered tickets. The active ticket shows its stage-specific SVG, system, and detail; native Previous and Next buttons sit below the stack, with a live stage indicator.
- Desktop visual review: local Astro preview at `http://127.0.0.1:4321/#architecture`, 1280 × 710 CSS px. The centered ticket stack and both controls are visible. Clicking Next changes 01 to 02; Previous returns to 01, and Previous from 01 wraps to 05.
- Mobile visual review: the same section at 390 × 844 CSS px. The document width is 375 px, the ticket carousel and controls fit within 339 px, and the controls place the stage indicator on its own row.
- Tablet visual review: at 768 × 1024 CSS px, the document width is 753 px and the ticket stack with controls fits within 690 px.
- The five stage-specific SVG assets remain paired with selectable HTML labels and descriptions. Consent and human review remain separate notes below the ticket stack.
- The workspace and homepage use the same Fraunces Paper T-Rail wordmark, white “P” favicon on a sky-blue background, centered one-line footer identity, shared paper palette, and shared font tokens. The homepage hero and header setup CTAs use the same red fill and bright-paper text.
- At 390 × 844 CSS px, Paper Review displays a larger-screen availability note instead of its PDF/review controls; other workspace surfaces remain responsive without horizontal overflow.
- The inline desktop and mobile browser captures were reviewed in this session; the browser tool does not persist those captures as files.

## Fidelity and interaction

- **Palette:** The hero and route scene use the shared deep railway blue `--ptr-hero-sky: #153B62`; paper, ink, and station colors remain shared design-system tokens.
- **Sky details:** Pixel stars are visible but sparse, with quiet twinkle; the train sprite is cropped before its rectangular beam and gets a circular headlamp with a soft right-facing glow.
- **Hero composition:** The product name, tagline, description, and actions are centered in the viewport. A compact train sits near the lower edge, directly on the bridge deck. There is no separate track/tie strip.
- **Reference-led landscape:** The hero uses a night landscape inspired by `hero-night-train.webp`, with mountains descending into a forested shoreline and reflective lake. The image is slightly transparent, with a soft blue fog layer over the mountains to reduce their contrast behind the trail and bridge.
- **Previous bridge asset:** The bridge continues to use a repeating arch span from `bridge.png`. The tile is scaled to 219px on desktop and 195px on mobile, with the background crop scaled to keep the repeating edges joined. Sixteen spans cover wide viewports; the track loops left every 40 seconds behind a horizontally anchored train, independent of scroll.
- **Independent layers:** Moon, clouds, stars, bridge, and train remain separate CSS/Astro layers. The star, moon, cloud, bridge, and carriage animations respect `prefers-reduced-motion` while keeping the scene visible.
- **Train animation:** `TrainConsist.astro` slices the train sprite into four segments and staggers their small movement. Passenger lights switch off at random, with each dark interval lasting no more than 30 seconds. The headlamp and beam animate with the locomotive; the train sprite is cropped before its baked-in beam.
- **Responsive layout:** The bridge and train deck alignment was reviewed at 1280 × 710, 820 × 1180, 768 × 1024, and 390 × 844 CSS px. The measured train-to-deck gap is 4px at each size, and none of the pages has horizontal overflow.
- **Architecture section:** Five pixel-style stage assets map one-to-one to the Analysis Run stages on layered train tickets. Stage labels and descriptions remain selectable HTML; Previous and Next controls navigate the stack and update its live status. Consent and human review remain distinct notes.
- **Architecture art review:** The stage-specific transparent SVGs under `homepage/public/images/architecture/` were reviewed in the local preview at 1280 × 710, 768 × 1024, and 390 × 844 CSS px. The ticket stack and controls fit each reviewed viewport; click review confirmed forward, backward, and wraparound navigation.
- 2026-10-02 ticket refinement: stage art is centered above the stage copy (112px desktop, 88px at widths up to 760px, 72px at widths up to 420px); the stage name is presented as a next-station destination. Next retains the stamp transition, while Previous changes tickets directly. Astro production build and `git diff --check` passed; a fresh visual capture is pending because browser preview access was blocked.
- 2026-10-02 ticket polish: removed the active ticket's dark perimeter, added a subtle paper-fiber stock texture, and increased vertical spacing around the carousel. Homepage production build and `git diff --check` passed; a fresh visual capture is still pending.
- **Console:** No browser console warnings or errors appeared during review.

### Hero scale and atmosphere refinement

- Source: the user's request to enlarge the stars, reduce the bridge scale, and soften the mountain landscape with fog and opacity.
- Stars use larger source dimensions (20–28px) while retaining the existing 8-bit cross shape and blink timing.
- Earlier hero-scale captures, before the current responsive deck-anchor adjustment: at 1762 × 1190 CSS px, the deck was at about 986px and the train ended at about 982px; at 390 × 844 CSS px, the deck was at about 653px and the train ended at about 651px. Current measurements are listed in Source and captures above.
- Browser console check: no warnings or errors.

### Homepage annotation revision

- Passenger-car dimming overlays are oversized slightly around each sprite window so no lit edge remains visible. The train sprite crop ends before its baked-in headlight beam; the CSS beam and round headlamp bob with the locomotive. Motion is disabled for reduced-motion preferences.
- The loading cover shows five 12px square train-window lights across the full-width indicator, turning on and off in sequence from left to right with no line between them; reduced-motion mode keeps all five softly lit and steady. The five Under the Hood cards have 44px row spacing on desktop and 26px on mobile.
- Cloud loops retain varied speeds and the farthest layer stays slowest; durations are now 215 to 700 seconds, about 60% slower than before. The title, lake, and train fog layers drift slowly from right to left and respect reduced-motion preferences.
- The hero setup CTA matches the navbar CTA's red fill, paper text, and hover color. The closing setup section is removed, and the FAQ height follows its content instead of filling the viewport.
- Mobile anchor review at 390 × 844 CSS px places section content below the revealed navigation. About and FAQ use content-driven heights at desktop and mobile; FAQ rows remain content-sized and the page has no horizontal overflow. Below 760px, homepage sections use 24–32px vertical padding; desktop section padding is unchanged.
- On the How it works section, the 390 × 844 CSS px mobile layout raises the heading and walkthrough together by 40px; after anchor navigation their tops are 176.9px and 495.9px. At 390 × 667 CSS px, the transform is off and their tops are 158.5px and 416.8px.
- At 1416 × 972 CSS px, About measures 562px tall and FAQ 378px. At 390 × 844, they measure 843px and 574px; at 390 × 667, 816px and 555px. Both sections compute to `min-height: 0`, and desktop/mobile captures show their content without horizontal overflow.
- The loading cover uses system fonts for its serif brand and sans-serif byline, so neither shifts when web fonts load. The landing content remains covered until page fonts and images settle.
- The sample journey is replaced by a lazy, non-autoplaying privacy-enhanced YouTube embed under “How it works.” The About section includes a small claim-to-evidence pixel trail without changing the content hierarchy.
- Visual review at 390 × 844 CSS px confirmed the About trail fits in four equal columns (89px each), with no horizontal overflow. The How it works iframe measures 352 × 197px (1.79:1) and stays inside the viewport; the player shows a Play control and uses `autoplay=0`.
- The mobile hero at 390 × 844 CSS px keeps the train within the viewport and seated on the bridge deck. Randomly darkened carriage windows are fully opaque, and the bright headlamp and beam follow the locomotive's staggered vertical animation.
- PDF review verified the selected claim and its Aghzal citation marker on page 10. Other citation markers in the same sentence, including Valmeekam and Wei et al., remain unhighlighted. Switching to Paper Review and clicking Focus Paper Review both placed the review card at the same viewport position (8px from the top).
- Analysis Pipeline quick navigation uses each stage's status color, with a visible selected-stage outline. Local web, homepage, and API endpoints returned HTTP 200 and remain running for continued review.

## Build checks

- `mise exec -- pnpm --dir homepage build` passed after the star, bridge-scale, and mountain-fog refinement.
- `mise exec -- pnpm --dir homepage build` passed after the ticket-stack and responsive bridge changes.
- `mise exec -- pnpm --dir homepage build` passed after the viewport-aware homepage section-rhythm refinement; `git diff --check` passed.
- `mise exec -- pnpm --dir web run build` passed after the Analysis Run pipeline outcome summary changes.
- Manual browser review verified the bridge alignment at 1280 × 710, 820 × 1180, 768 × 1024, and 390 × 844 CSS px, plus ticket-stack rendering at 1280 × 710, 768 × 1024, and 390 × 844 CSS px. Ticket navigation advanced, reversed, and wrapped between stages.
- `mise exec -- pnpm --dir web test` passed (44 tests); web lint, typecheck, and production build passed during the shared design-system review.
- `mise exec -- docker compose -f infra/docker-compose.yml config --quiet` passed.
- `make local web homepage` reused the existing host processes for this worktree and returned without spawning duplicate servers; web, homepage, and API health endpoints responded with HTTP 200.
- PDF review verified the selected claim and Aghzal citation marker while excluding unrelated citations, and the Paper Review tab and focus button landed at the same position.
- `git diff --check` passed after the final edits.
- The homepage package has no test script; its production build passed.

final result: passed
