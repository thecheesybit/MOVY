# Site dossier: Movy

Studied live at 1920x1080, dpr 1, desktop UA (2026-11-08). No source URLs here by design.

## Page types and routes
| Page | Route | Notes |
|---|---|---|
| Home | `/` | fixed header, rotating hero (Play + More info per slide), 8 `ScrollRow` rows |
| Browse | `/browse/movie`, `/browse/tv`, `/browse/anime`, `?genres=<g>` | genre grids |
| Detail | `/movie/<id>`, `/tv/<id>` | hero Play button, episodes list, top-left back button (`buttonTopLeftGoBack`) |
| Player | same URL, opened in place by Play (`?play=true` also exists) | `#vp-shell` overlay, `position:fixed`, 1920x1080 |

React SPA with hashed SCSS module class names.

## Critical Behavioral Findings & Solutions

### 1. High Priority Candidate Layer for Player
- **Issue**: Large catalog rows with dozens of cards exhausted the 700-candidate budget before `buildCache()` reached the player controls at the bottom of the DOM.
- **Solution**: High-priority scraping order collects `#vp-shell` candidates (including `svg.cursor-pointer` Back arrow) first before main catalog nodes.

### 2. Fullscreen Coordinate Invariant
- **Issue**: The full-viewport `#vp-shell` overlay had its center near the vertical midpoint, causing older fixed-element logic to misclassify it as a bottom fixed bar (`fix: 1`).
- **Solution**: Elements occupying >= 75% of viewport width and height receive `fix: 0` without artificial offset.

### 3. Left Rail & Episode Navigation
- **Left Rail**: persistent mini icon rail; a single LEFT at the left edge expands it into the Movy Red drawer (Search, Home, Movies, TV Shows, Anime, Recently viewed, Sources). Hidden during video playback.
- **TV Episodes**: On `/tv/<id>`, episodes are direct links `a[href^="/tv/<id>/<season>/<episode>?play=true"]` with inline Play buttons.

## Player
- In-page `#vp-shell` overlay with same-origin `<video>` (blob/MSE).
- Controls:
  - Back: `svg.cursor-pointer` in top-left bar
  - Quality: `button.tabbable` (opens 2160p, 1080p, 720p, 480p popover)
  - Servers: ~15 US city buttons (Miami, Boise, Nashville, Chicago, etc.)
  - Subtitles: Language selector and OpenSubtitles button
  - Playback: `#ButtonPlay`, `#ButtonFullscreen`
- Remote keys:
  - OK: toggles play/pause or activates focused control.
  - Left / Right: seeks 10s or navigates between controls.
  - Up: opens quality/server controls.
  - Back: closes quality/server menu if open, or clicks `svg.cursor-pointer` to exit player.

## Round 4 findings (player, menus, rail)
- Player: same-origin `#vp-shell`, `<video>` blob/MSE. Top bar: back arrow (an `<svg>`, no `click()` method: press it with a pointer sequence), `Quality`, `Audio & Subtitles` (`button.tabbable`).
- Menus are mounted all the time and hidden with `opacity-0 pointer-events-none` on a wrapper: "open" means `.player-surface` is visible. Audio and Subtitles are two `.custom-vertical-scroll` columns; footer rows `Search on OpenSubtitles` / `Customize appearance`; `Upload subtitles` is a label over `input[type=file]` (never focus). Selected row = `bg-white/[0.14]`.
- Escape closes a menu. The quality popover lists servers with a `Favorite server` star (skipped).
- Content starts at x=96 on the home page, so it clears the 60 px rail without an inset.
- HTML is server-rendered (Next.js, buildId in the document, 100-390 KB per shell route), so the local shell snapshot uses a 6 h maximum age.
