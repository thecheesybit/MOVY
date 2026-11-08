# Site dossier: Cinejoy

Studied live at 1920x1080 and 960x540 (2026-11-08). No source URLs here by design.

## Page types and routes
| Page | Route | Notes |
|---|---|---|
| Home | `/` | hero with Play, "Browse by Provider" row, TMDB-driven carousels |
| Movies / Series / My List | `/movies`, `/series`, `/lists` | desktop top nav tabs |
| Detail + player | `/movie/<id>` redirects to `/watch/movie/<id>` | one page: details, cast, then the player on Play |
| Series detail | `/series/<id>` | season selector button (`.season-selector button`), episode cards `a[href^="/watch/tv/"]` |
| Person | `/person/<id>` | cast details |

SvelteKit app (`/_app/immutable/...`), Svelte 5 components, Tailwind classes.

## Critical Behavioral Findings & Solutions

### 1. Modern Framework Event Dispatch (Svelte 5 Click Requirement)
- **Root Cause**: Modern UI frameworks like Svelte 5 on Cinejoy ignore synthetic `.click()` events if not preceded by pointer lifecycle events.
- **Solution**: The adapter dispatches the full interaction sequence in `activate(el)`:
  `pointerdown` -> `mousedown` -> `pointerup` -> `mouseup` -> `click`.
  For client-side router links (`<a href="...">`), an asynchronous navigation fallback guarantees smooth transitions.

### 2. Desktop Hover Preview Popover Suppression
- **Root Cause**: On desktop, hovering a movie card opens an interactive video preview popup ("NEON Anora" with mini-player and progress bar). Dispatching continuous synthetic `mouseover` and `mousemove` on every card focus inadvertently triggered these desktop popovers on Android TV, covering adjacent cards.
- **Solution**: `setCurrent(el)` no longer fires `mouseover`/`mousemove` on regular catalog cards. Focus is driven purely through CSS `:focus` and `.movy-tvf` rings. Mousemove is only dispatched to wake controls when inside an active player.

### 3. Smart Element-Aware Grid Navigation & Traps
- **Row/Carousel Navigation**: Carousel tracks have hidden navigation arrows (`aria-label="Scroll left/right"`), `aria-label="Edit list"` buttons, and row label links (`a.group/label`). These are filtered out in `isSkipped()`.
- **Vertical Navigation**: Keeps column memory `colX` across rows, jumping directly between the hero Play button, provider icons, and movie cards.
- **Left Rail**: persistent mini icon rail; a single LEFT at the left edge expands it into the Cinejoy Blue drawer (Search, Home, Movies, Series, My List, Recently viewed, Sources). Hidden during video playback.

## Player
- In-page `.player-root` (1920x1080) with same-origin `<video>` (blob/MSE).
- Buttons:
  - Back: `button[aria-label="Back"]`
  - Play/Pause: `button[aria-label="Play"]`, `button[aria-label="Pause"]`
  - Seek: `button[aria-label="Rewind 10s"]`, `button[aria-label="Forward 10s"]`
  - Utilities: `button[aria-label="Mute"]`, `button[aria-label="Servers"]`, `button[aria-label="Subtitles"]`, `button[aria-label="Settings"]`, `button[aria-label="Fullscreen"]`
- Auto-hide: Controls fade after 3 seconds. Any DPAD key wakes them via pointer movement.
- Remote keys:
  - OK: toggles play/pause or activates focused control.
  - Left / Right: seeks 10s or moves between control buttons.
  - Up: wakes and focuses controls.
  - Back: closes sub-menus (Servers, Subtitles) if open, or exits player via `button[aria-label="Back"]`.
