# Site dossier: Moonflix

Studied live at 1920x1080 (2026-11-08). No source URLs here by design.

## Page types and routes
| Page | Route | Notes |
|---|---|---|
| Home | `/browse` | hero with Play/More info, ~12 carousel regions (`[role=region]`) |
| Movies / TV / New / My List | `/movies`, `/tv-shows`, `/new-popular`, `/my-list` | header links |
| Detail | In-page modal (`div.fixed.z-[300]`, 920px wide) | Play, trailer, episodes, more like this, close X |
| Player | Full-viewport cross-origin iframe on player subdomain | `iframe[title="Moonflix player"]` |

React / Next.js SPA with Tailwind CSS.

## Critical Behavioral Findings & Solutions

### 1. In-Page Detail Modal Navigation & Focus Trap
- Clicking a card on the home page opens a Netflix-style detail modal (`div.fixed.z-[300]`).
- The smart navigation engine detects the modal, isolates focus inside it, and lands initial focus directly on the modal's `Play` button.
- Pressing BACK cleanly closes the modal via its close button (`button.absolute.top-4.right-4`) and restores focus to the triggering card on the home page.

### 2. Region-Based Card Grid
- Rows are `[role=region]` containers holding `div.cursor-pointer` cards.
- Column memory is preserved when navigating UP/DOWN across regions.
- "Notifications (F8)" and hero trailer un-mute buttons are skipped.

### 3. Left Rail
- Cyan Teal Left Rail: persistent mini icon rail; a single LEFT at the left edge expands it (Search, Home, Movies, TV Shows, My List, Recently viewed, Sources). Hidden during modal or player playback.

## Player
- Cross-origin iframe (`<moonflix-player>`) with an overlay close button:
  `button.absolute.top-4.right-4.z-50`.
- Remote keys:
  - OK: clicks the center of the iframe to toggle playback.
  - Back: clicks the overlay close button or calls `history.back()` to exit the player and return to `/browse`.
