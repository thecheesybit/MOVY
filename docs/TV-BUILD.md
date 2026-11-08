# TV build spec (Phase B → C)

Inputs: `docs/TV-PLAN.md` (the user's decisions 1–8, Smart cache, Viewing experience) and `docs/TV-AUDIT.md` (per-site facts).
Draft selectors: `config/apps/movy.tvprofiles.draft.json` (git-ignored). Every rule below is tested **on the TV only**
(debuggable build with a higher versionCode, same key, `install -r`; finish on a release build).

This spec is built in two phases, by two sequential workers:
- **W13, the TV engine:** §1–§6
- **W14, the TV player experience and final pass:** §7–§9

## 1. Profiles in config (W13)
- Add `tvProfiles: Record<sourceId, TvProfile>` to the zod schema (`packages/config`, still `.strict()`, with a typed schema and tests).
- Allow it in the app config *and* as a Remote Config override (merged per source, remote wins per key).
- Move the draft into `config/apps/movy.json`, cleaned up: **selectors only**, with prose moved into a `notes` string field.
- Ship profiles to the WebView as JSON for the active source; re-send on source switch and on a live config update.
- Fields (extend the TV-PLAN shape as needed):
  - `header`, `bottomBar`: elements hidden on idle;
  - `rows`, `cards`, `skip`, `firstFocus`;
  - `playButtons`;
  - `servers { toggle, list, item, active, failed, frame }`;
  - `episodes { list, item, next }`;
  - `player { frame, video, root, fullscreenTarget, fullscreenButton, controls, overlayKill, depth }`;
  - `killOnTv`: heavy decorative media, e.g. autoplaying trailer iframes;
  - `viewportWidth`: see §2.

## 2. Layout width (W13, evaluate first)
- At 960 CSS px, Cinejoy falls back to its *mobile* layout (bottom pill nav, no row arrows).
- Evaluate rendering the TV WebView at **1280 CSS px** (DPR 1.5):
  - `setUseWideViewPort(true)` plus an injected/overridden `<meta name=viewport content="width=1280">`, or `setInitialScale`;
  - per-profile `viewportWidth` (default 1280; a site can opt out).
- Keep 1280 only if text and posters stay readable from the sofa (screenshot every site), Cinejoy gets its desktop layout, and nothing
  breaks. Otherwise stay at 960 and fix Cinejoy through its profile (`skip` the bottom pill and Back-to-top; `rows` per the audit).

## 3. Navigation engine (`tvnav.js`, profile-driven) (W13)
- **Model:** the page is a list of **rows** (profile `rows`, or auto-detected horizontal scrollers) holding **cards** (profile `cards`), plus
  free-standing focusables.
  - LEFT/RIGHT move within a row and scroll it smoothly so the focused card is fully visible, with a little peek of the next.
  - UP/DOWN move to the **nearest card in the previous/next row**, keeping the column as close as possible (Netflix behaviour). Never
    land on rail arrows.
- **Never focusable:**
  - profile `skip` (rail arrows, hero dots, vote buttons, comment sections, Back-to-top, the 7Movies `card-watchlist`);
  - anything not rendered: `display:none`, `visibility:hidden`, zero size, inside a closed `<details>` (7Movies), `aria-hidden=true`
    ancestors and off-screen slider layers (Movy);
  - scroll containers themselves: the Movy `__track` and the Moonflix `[role=region]` rows. Their cards are reachable instead (the two blockers).
- **First focus** on page load or route change goes to profile `firstFocus` (hero Play), else the first card of the first row. Never the
  header. The header is reachable with UP from the top row.
- Movy's rotating hero: resolve Play from the *current* slide (`slideCurrent`) when OK is pressed.
- **Range inputs** (Cinejoy volume): LEFT/RIGHT leave the control instead of changing it, except inside our own player model (§7).
- **Speed:** keep the candidate list cached and update it on a throttled MutationObserver. The **focus-move key-to-paint must
  be under 100 ms** on the TV (W9 measured ~100 ms of collection alone); measure it with `performance.now()`.
- **Focus visuals:** scale 1.08 with a 150 ms ease-out, a 3px white ring with glow, the focused card is raised in z, and a dimmed
  scrim stays off the rest.
- Frames: a per-frame instance (already there), using the `__movyTvExit` message protocol for leaving a frame; extend it with `enter(direction)`.

## 4. Idle, immersive and the first-key rule (W13)
- **Native idle controller:**
  - a 5 s timer reset by any key;
  - on idle, hide the Island, Settings, hints, the cursor and the seek bubble;
  - call `__movyTv.idle(true)` in every frame, which adds `html.movy-idle`: CSS hides profile `header`/`bottomBar`, the player `controls`, the
    focus ring and the cursor.
- **The first key after idle only wakes** (`idle(false)`) and is swallowed. Exception: play/pause (85) acts immediately.
- **Immersive always:** re-apply `hide(systemBars)` with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` on `onResume`,
  `onWindowFocusChanged(true)`, after dialogs, the IME, Settings and fullscreen exit.

## 5. Fullscreen policy (W13)
- **Auto-enter:** when OK activates a profile `playButtons` element (or any element inside the player), and on the first `playing`
  event that follows a user key, call `requestFullscreen()` on the profile `player.fullscreenTarget` **inside the key handler**, so
  it counts as a gesture. Fallback: the frame element in the parent.
  - Per site (audit table): 7Movies `div.unified-player-frame` (parent), Movy `#vp-shell`, Cinejoy `.player-root`, Moonflix the wrapper
    `#video-container`, or the outer iframe in embed mode.
- **Stay fullscreen until BACK:** if the fullscreen element is removed or replaced (autonext, server switch, re-render), re-request
  on the new target. If no gesture is available, the native side keeps its own fullscreen host (`onShowCustomView` state) and the
  next key re-requests it.
- **Hide the site's own fullscreen buttons with CSS only** (audit: all 4 are safe). This includes the 7Movies `#lbFs` inside the embed frame, and
  the Moonflix embed button via that frame's instance.
- **BACK in fullscreen:** hide our bar if it's visible, else exit fullscreen, else history back.

## 6. Smart cache (W13)
Exactly as in TV-PLAN "Smart cache", with the audit's specifics:
- Strip `Set-Cookie` when storing Cinejoy's static assets, which are immutable; never re-emit it.
- Respect the Moonflix `max-age=43200` via revalidation.
- Never cache API JSON, even when it carries a `max-age`.
- Measure cold against warm home load for each source and report it.

## 7. One player control model (W14)
- **The native player bar** is a bottom overlay drawn natively over the fullscreen host (the W8 guard keeps the video composited).
  - It shows the title (from the page), the progress and time (polled from the active `<video>` via JS: `currentTime`, `duration`,
    `paused`), plus buttons: **Servers**, **Episodes** (if the profile has them), **Next episode**, **Subtitles** (if the site has them),
    and **Audio**.
- **Keys:**
  - OK = play/pause, which flashes the bar;
  - LEFT/RIGHT = seek ±10 s; holding repeats with acceleration to 30 s steps, and a seek bubble shows the target time;
  - UP/DOWN = open the bar, focused on Servers;
  - the play/pause key works the same as OK;
  - BACK = close the bar, then exit fullscreen.
  - Idle hides the bar after 5 s.
- **Servers sheet:** native list. Opening it clicks the profile `servers.toggle` (inside the right frame) and reads the item names and the active
  and failed state.
  - Selecting an item clicks it.
  - Show the site's names only.
  - Close the site's own picker afterwards.
  - Re-enter fullscreen after the switch (§5).
- **Episodes sheet:** the same pattern using the profile `episodes`. **Next episode** clicks `episodes.next`, or navigates to the next item.
- **Dead server:** if no `playing` arrives within 12 s after a switch or Play, or the profile `servers.failed` matches, show "This server isn't
  working — try another" with the Servers sheet open on the next untried server.
- Media control works across frames: the JS in the frame that owns the `<video>` answers `__movyTv.media(...)` and status polls via
  postMessage relay. Cross-origin embeds already run our injected script.

## 8. The scripted pass, per source, on the TV (W14)
For each of the 4 sources:
1. launch, then home;
2. first focus is on Play;
3. browse 3 rows down and 5 cards right, then back up (same column);
4. open a title;
5. (series) choose season 2 and episode 3;
6. Play, then auto-fullscreen;
7. 5 s idle hides everything;
8. a key wakes it without acting;
9. seek ±10 s and hold;
10. Servers: switch to a second server, and it plays again in fullscreen;
11. Next episode;
12. BACK chain;
13. search for a title and open it;
14. relaunch, then continue watching works where the site supports it.

Record the timings (warm launch, focus move, cold and warm home load) and screenshots `w14_<source>_<step>.png`, and make a pass/fail table.
Fix what fails, then re-run that row.

## 9. Delivery (W14)
- Release `tv` APK.
- Copy it to `dist/movy/release/Movy-1.0.0-build<N>-tv.apk`.
- Install it on the TV with `install -r`, on 7Movies home.
- The phone flavor must still build and pass its tests. The phone APK in `release/` is replaced only if phone code changed, and is then
  verified on the phone.
