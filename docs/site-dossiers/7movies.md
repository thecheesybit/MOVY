# Site dossier: 7Movies

Studied live at 1920x1080, dpr 1, desktop UA (2026-11-08). No source URLs here by design.

## Page types and routes
| Page | Route | Notes |
|---|---|---|
| Home | `/` | topbar, hero (Play / More info), ~16 rails |
| Movies / TV / Discover | `/movies`, `/tv`, `/discover` | genre rails and catalog grids |
| Detail | `/movie/<id>`, `/tv/<id>` | Back button, Play (`a.btn-primary` -> `/…/watch`), episode list in `section.dp-episodes` |
| Watch | `/movie/<id>/watch` | full-viewport cross-origin embed iframe (`div.unified-player-frame`) |

## Critical Behavioral Findings & Solutions

### 1. Episode Cards & Download Suppression
- **TV Episodes**: In `section.dp-episodes`, episode cards are `<article class="unified-episode-card">` elements, not links. These are explicitly included in `buildCache()` focusables.
- **Trap Suppression**: `.unified-episode-download` buttons inside each card are filtered out via `isSkipped()` to prevent accidental download clicks on TV.

### 2. Promo & Ad Button Neutralization
- The top bar contains Discord promo links (`a[href*="discord"]`) and an `Ads` button (`button.pref-pill`). These are filtered out in `isSkipped()` so D-pad navigation never focuses them.

### 3. Left Rail
- Amber Gold Left Rail: persistent mini icon rail; a single LEFT at the left edge expands it (Search, Home, Movies, TV Shows, Discover, Recently viewed, Sources). Hidden during video playback on `/watch`.

## Player
- Watch page uses a cross-origin embed iframe (`https://embed.vidrift.net/...`) filling `div.unified-player-frame`.
- Parent controls:
  - `button.unified-server-toggle` ("Servers & Mode") opens the server picker `<details>`.
- Remote keys:
  - OK: clicks the center of the iframe to toggle playback, or activates `Servers & Mode` when focused.
  - Back: calls `history.back()` to return to the detail page.
