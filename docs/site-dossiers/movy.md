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
