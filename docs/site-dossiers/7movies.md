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

## Round 4 findings (player, menus, rail)
- The watch page hosts a cross-origin embed iframe (`/embed2/play`); the injected scripts do run inside it (`window.__movyTv` is present, profile found through the ancestor origin). Frames of the page: a tiny trace frame and the player frame.
- Top bar ids: `lbBack`, `lbQuality`, `lbServer`, `lbSubs`, `lbNext`; bottom: `playBtn`, `rewindBtn`, `forwardBtn`, `muteBtn`, `lockBtn`, `castBtn`, `pipBtn`, `episodesBtn`, `exitBtn`, `fullscreenBtn`. `centerToggle`/`centerBack`/`centerFwd` cover the video (never candidates).
- Menus `.lb-menu.show` (`#lbSubsMenu`, `#lbQualityMenu`): two `.lb-col` columns, rows `.lb-opt` (selected = `.active`), footer drill rows `.lb-opt.lb-drill` (`Customize appearance`, `Sync to audio`). Escape closes. Legacy hidden panels (`.panel`, `#subtitleSearch`, `#subtitleUploadBtn`) stay in the DOM: never.
- Content starts at x=58 (rails: `section.rail` has padding 57.6 and a negative margin), hero at 90, detail hero text `left: 51`, detail body `margin-left: 51`: all get `railInset`.
- Server-rendered pages (about 240-270 KB, no build marker: the asset list is hashed), 6 h maximum age; Last-Modified present, no ETag.
