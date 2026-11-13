# Phone player: VLC-style controls and glitch fixes (implementation plan)

Target: Android **phone/tablet** build only (`--target phone`). TV and desktop must behave exactly as today.

## 0. Read this first

**The app has no player of its own.** It is a WebView shell; each of the four sources draws its own web player,
and two of them (7Movies, Moonflix) put the `<video>` in a cross-origin iframe. See `docs/site-dossiers/README.md`.
So "tap on empty area toggles play/pause" is each site's behaviour, and it cannot be fixed per site. The fix is one
shell-owned control layer that sits on top of whichever `<video>` is playing and drives it through the standard
`HTMLVideoElement` API. That works the same on all four sites, including inside the iframes, because the shell
already injects scripts into every frame (`CosmeticBridge.install`, `addDocumentStartJavaScript(..., "*")`).

Rules for the implementer:
- Never commit or print anything from `config/apps/*.json`, `config/remote/*.json`, `google-services.json`,
  `restore-private.mjs` or `movy-friend-setup.txt` (URLs, keys). Do not put site URLs in code, tests or docs.
- Build needs Node 22+ on PATH (Capacitor refuses Node 20) and `ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`.
- Follow the existing style of `packages/inject/*.js`: plain ES5, no dependencies, one IIFE, pure functions
  exported through `module.exports` for `node --test` (copy the pattern at the top/bottom of `scamguard.js`).
- After every phase: `npm test`, then `npm run build -- --app movy --target phone`, install, and run the manual
  checklist in section 6 on a real phone. The glitches cannot be reproduced in unit tests.

## 1. Phase 0: reproduce and capture (do this before changing code)

Nobody has reproduced the subtitle glitch with logs yet. The causes in section 2 are suspects found by reading
the code, ordered by likelihood. Confirm which ones fire before and after the fixes.

1. Install the current build, connect `adb`, run `adb logcat -s MovyAdblock MovyShell`.
2. On **each** of the four sources: start a title, open the subtitle menu, pick a language. Note per source:
   does the picture flicker, does play/pause toggle by itself, does the menu vanish.
3. In the log, look at the lines printed at the moment of the tap:
   - `navigation ... overlay` means `navguard.js` removed an element and re-sent the click (suspect A).
   - `popup ... overlay` means `scamguard.js` removed an element (suspect B).
4. Check the brightness level. If the glitch only happens after swiping brightness above 100%, it is suspect C.
5. Write the findings (source, symptom, log line) at the bottom of this file under "Findings". Fix every suspect
   anyway (each is a real defect), but the findings tell you which one to verify hardest.

## 2. Bug fixes in existing code

### A. `packages/inject/navguard.js` removes legitimate menu backdrops and double-toggles play
`onClickNonAnchor` treats any transparent `position:fixed/absolute` element that is a direct child of `<body>`,
has no text and covers more than 30% of the viewport (or a video) as an ad click-jack. It **removes the element
from the DOM** and **dispatches a second click** on whatever is underneath. A subtitle/quality menu's
"click outside to close" backdrop matches that description exactly. Result: the site's framework loses a node it
owns (rendering breaks, "screen glitches") and the element underneath, usually the player's play/pause surface,
gets a click (play/pause flips).

Fix in `isInvisibleOverlay(el, isAnchor)`, for the non-anchor path only, return `false` when any of these hold:
- `el` is inside the fullscreen element (`document.fullscreenElement && document.fullscreenElement.contains(el)`).
- A menu or dialog is open in this document: a visible element matching
  `[role=dialog],[role=menu],[role=listbox],[aria-modal="true"]` exists.
- `el` carries UI-library state attributes: any attribute whose name starts with `data-state`, `data-radix`,
  `data-headlessui` or `data-melt`, or `aria-hidden`.
- A `<video>` in this document is currently playing or paused mid-way (`currentTime > 0 && !ended`). Click-jack
  overlays matter before playback starts, not during it.
Keep the anchor path (`onPointer`) unchanged. Move the decision into a pure function
`shouldRemoveOverlay(info)` and add `packages/inject/test/navguard.test.mjs` with cases for each exemption and one
positive case (empty transparent body-level div over an iframe, no video, no dialog). Add the test to the `test`
script in `package.json`.

### B. `packages/inject/scamguard.js` "QR" test is too loose
`describe()` sets `hasQr` for **any** `svg[viewBox]`, so a full-screen, top-z-index site dialog that contains a
single icon counts as a QR scam and is deleted. Change `hasQr` to: a `canvas`, an `img[src^="data:image"]`, or an
`svg[viewBox]` whose rendered box is at least 120x120 CSS px and roughly square (aspect between 0.8 and 1.25).
Also return `false` from `isScamOverlay` when `info.hasDialogRole` is true (new field: the element or a
descendant matches `[role=dialog],[role=menu],[role=listbox]`) unless `captchaText` is true. Extend
`scamguard.test.mjs` for both.

### C. Brightness boost filter re-rasters the whole player (`BrightnessMath.gainScript`)
Above 100% brightness the shell injects `:fullscreen{filter:brightness(x)!important}`. A filter on the
fullscreen element makes every menu, subtitle and control inside it repaint through the filter on each video
frame, and it turns the element into the containing block for `position:fixed` children (menus can jump).
Change the generated CSS so that:
- if the fullscreen element contains a `<video>` in the same document, the filter goes on the video only:
  `:fullscreen video, video:fullscreen { filter: brightness(x) !important }` (plus the `-webkit-full-screen` twins);
- otherwise (cross-origin iframe player) keep today's rule on the fullscreen element.
The script already runs in the page, so decide with
`var fs=document.fullscreenElement; var own=fs&&(fs.tagName==='VIDEO'||fs.querySelector('video'));`.
Update the existing `BrightnessMath` unit test for the new string.

### D. `packages/inject/loading.js` spinner flashes on every subtitle/seek action
`seeking` shows the red spinner after only 120 ms. Sites re-seek when a subtitle track or server changes, and the
new HUD seeks constantly while scrubbing. Use 120 ms for `loadstart`/`waiting`/`stalled` and **500 ms** for
`seeking`. While the HUD (section 3) is scrubbing it sets `window.__movyHudScrub = true`; `show()` returns early
when that flag is set.

## 3. New feature: `packages/inject/playerhud.js` (the VLC-style layer)

One new file, injected at document start into every frame on the phone target only. It does nothing until the
frame holds a real, large, playing video; then it puts a transparent touch layer plus a minimal HUD over it.

### 3.1 When it is active (pure function `isMainVideo(info)`)
A `<video>` is the main video when all hold: it is connected and visible; `duration > 60` or `duration === Infinity`;
not `muted` and not `loop` (rules out hero trailers and ad clips); its rect covers at least 60% of the frame's
viewport area; and the frame's viewport (`innerWidth*innerHeight`) is at least 60% of the screen
(`screen.width*screen.height`), **or** the document has a fullscreen element containing the video. Re-evaluate on
`play`, `playing`, `loadedmetadata`, `emptied`, `resize`, `fullscreenchange` (capture listeners on `document`, as
`loading.js` does) and every 1 s while a candidate exists. When no video qualifies, remove the layer completely
and stop all timers. Small inline players in portrait are therefore left to the site.

### 3.2 DOM
- Root `div#__movy_hud`, `position:fixed; inset:0; z-index:2147483646; touch-action:none; user-select:none`,
  appended to `document.fullscreenElement || document.documentElement` and re-parented on `fullscreenchange`
  (same `host()` idea as `loading.js`; the root must live inside the fullscreen element or it is not drawn).
- All styles inline or in one `<style id="__movy_hud_css">`. Use a closed shadow root on the host element so
  site CSS and the cosmetic filter cannot touch it. No external fonts or images: icons are inline SVG paths.
- Keep `z-index` one below `loading.js`'s guard/spinner (2147483647) so the spinner stays visible.
- `scamguard.js` and `navguard.js` must ignore it: give the host `data-movy-hud="1"` and add an early
  `return false` for elements with that attribute in both guards (add to their tests).

### 3.3 Touch handling (the core of the fix)
The root itself receives the touches, so the site's player surface never sees a tap on the video area. Use
Pointer Events on the root (`pointerdown/move/up/cancel`), call `preventDefault()` and `stopPropagation()`, and
also swallow the follow-up `click`/`dblclick`/`contextmenu` on the root.

Gesture classifier, pure function `classify(state, event) -> action`, unit-tested:

| Gesture | Action |
|---|---|
| Single tap on empty area (no second tap within 250 ms) | Toggle HUD visible/hidden. **Never play/pause.** |
| Double tap, left third | Seek -10 s (repeat taps keep adding 10 s; show "-20 s") |
| Double tap, right third | Seek +10 s |
| Double tap, middle third | Play/pause |
| Horizontal drag (dx > slop and dx > 2*dy) | Scrub: show target time and delta, seek on release. Full width = 90 s, or 10% of duration if larger |
| Vertical drag, left half | Brightness. Already native (`BrightnessLayout`), which sends `pointercancel` to the page: on `pointercancel` drop the gesture, do nothing |
| Vertical drag, right half | Volume, native, see 4.3. Same `pointercancel` handling |
| Any touch while locked | Only reveals the unlock button for 2 s |

Slop is 12 CSS px. A tap is down+up within 300 ms and under the slop. To keep single tap responsive, toggle the
HUD on the first tap immediately only when hiding; when showing, also do it immediately and, if a second tap
arrives within 250 ms, treat the pair as a double tap and revert the toggle. (This is what VLC does: the HUD
flashes on a double tap, which is acceptable.)

### 3.4 HUD layout (minimal, VLC-like)
Hidden by default while playing. Shown for 4 s after a single tap, and kept shown while paused, while a finger
is down on a control, or while scrubbing. Fade 150 ms. Controls are at least 44x44 CSS px.

- **Centre row:** rewind 10 s, play/pause (large), forward 10 s.
- **Bottom row:** current time, seek bar (drag to scrub, buffered range drawn from `video.buffered`), duration
  (tap toggles remaining time). Under it, left to right: lock, speed (cycles 0.5x 0.75x 1x 1.25x 1.5x 2x via
  `playbackRate`), aspect (cycles Fit / Fill / Stretch via `object-fit: contain / cover / fill` set with
  `!important` on the video), fullscreen, and **Site menu** (see 3.5).
- **Top row:** nothing of ours. The top 56 px of the root is `pointer-events:none` so the site's own back/close
  button stays reachable (it is drawn by the site, only when the site shows its bar; see 3.5).
- **Lock:** hides everything, ignores all gestures, shows a small unlock button for 2 s on any touch.
- Brightness and volume pills are native; do not draw them here.

Everything reads state from the video's own events (`play`, `pause`, `timeupdate`, `durationchange`, `progress`,
`ratechange`, `seeking`, `seeked`), never from site DOM. Throttle bar updates to `requestAnimationFrame` and only
while the HUD is visible.

### 3.5 Coexisting with the site's own controls
Because the root swallows touches, the site's bars never wake up on their own. Two things remain:

1. **Site menu button** (subtitles, audio, quality, servers, episodes stay the site's job, they cannot be
   reimplemented generically). Tapping it enters *site mode*: our HUD hides, the root gets
   `pointer-events:none`, and a synthetic `mousemove` + `pointermove` is dispatched on the element under the
   screen centre (`document.elementFromPoint`) to wake the site's bars. A small "Done" chip (ours, bottom
   centre, the only part with `pointer-events:auto`) returns to our mode; so does 8 s without any touch
   (track with a capture-phase `pointerdown` listener on `window`, do not swallow there). While in site mode
   the site behaves exactly as it does today, minus the bugs fixed in section 2.
2. **Site bars that stay visible while paused** would sit under our HUD. Hide them while we are in our mode
   using the selectors the TV profiles already hold. At build time (`scripts/android/generate.mjs`, phone target)
   collect `tvProfiles.<source>.player.hide`, `.controls` and `.fullscreenButton` from every source into one flat,
   de-duplicated selector list and pass it to the script as `MOVY_HUD.hide` (wrapper variable, same technique as
   `MOVY_OPTS` in `CosmeticBridge`). In our mode add `html.movy-hud-on <sel>{opacity:0!important;pointer-events:none!important}`;
   remove the class in site mode. Selectors that match nothing on a given site are harmless. Never include
   `player.menu` selectors in that list.

Fullscreen button: if a selector from `player.fullscreenButton` matches, click it with the pointer sequence the
dossiers require (`pointerdown, mousedown, pointerup, mouseup, click`); else call `requestFullscreen()` on the
highest ancestor of the video whose rect equals the video's rect within 2 px (so the site's subtitle overlay
comes along); in a sub-frame fall back to `document.documentElement`. Exit with `document.exitFullscreen()`.

### 3.6 What not to build
No own subtitle renderer, no own quality/server picker, no playlist, no equaliser, no gestures outside the main
video, no desktop/Electron or TV wiring, no remote-config schema changes.

## 4. Native and build wiring

### 4.1 Ship the script (phone target only)
- `scripts/android/generate.mjs` `writeAdblockAssets`: copy `playerhud.js` into `assets/shell` when
  `cfg.target !== 'tv'`, and write the selector list from 3.5 to `assets/shell/playerhud-hide.json`.
- `CosmeticBridge.install`: when `!tv`, read both assets, wrap as
  `(function(){var MOVY_HUD={hide:<json>,enabled:true};<file>\n})();` and register with
  `addDocumentStartJavaScript(webView, hud, all)` after `scam`. Append it to the fallback string returned when
  `DOCUMENT_START_SCRIPT` is unsupported.

### 4.2 Off switch
A site update can break any overlay, so users need a way out without a new build. Add a boolean pref
`playerHud` (default true) in `Prefs`, a row "Movy player controls" in the settings sheet next to the existing
switches (`SettingsModel` / `SettingsRows`), and expose it to the page as `MovyAdblock.hudEnabled()` in
`CosmeticBridge`. `playerhud.js` checks it each time a video becomes the main video. Off = today's behaviour.

### 4.3 Volume gesture (native, mirrors brightness)
`BrightnessLayout` already owns vertical swipes in the left zone of the fullscreen view. Add the mirror image for
the right zone: `BrightnessMath.inZone` gets a sibling `inVolumeZone` (right 40% of the width, same vertical
margins), the drag maps to `AudioManager.STREAM_MUSIC` steps (`setStreamVolume`, flag 0), full height = full
range, and the existing pill is reused with a speaker glyph instead of the sun. Keep the pure maths in
`BrightnessMath` (or a new `VolumeMath`) with a unit test next to `BrightnessMathTest`. TV: unchanged.

### 4.4 BACK key on phone
In `MainActivity`'s back handling, before leaving fullscreen: if the HUD is locked or in site mode, BACK should
only unlock / return to our mode. Ask the page with one `evaluateJavascript("window.__movyHud&&window.__movyHud.back()")`
call that returns `true` when it consumed the key (same pattern as the existing `__movyTv.back()` call a few
lines away). In a cross-origin player frame the top document has no HUD and returns nothing: fall through to
today's behaviour.

## 5. Tests to add (`node --test`, add each file to the `test` script in `package.json`)
- `playerhud.test.mjs`: `isMainVideo` (trailer muted/loop rejected, small inline rejected, fullscreen accepted,
  iframe sized like the screen accepted); `classify` (single tap toggles HUD and never returns play/pause,
  double-tap thirds, horizontal scrub maths incl. clamping to 0..duration, `pointercancel` yields no action,
  locked state); time formatting (`0:07`, `1:02:03`, `Infinity` shows `LIVE`).
- `navguard.test.mjs` and the `scamguard.test.mjs` additions from section 2.
- Java: the gain-script string test, volume maths test.

## 6. Manual acceptance checklist (run on all four sources, portrait inline and landscape fullscreen)
1. Single tap on the picture shows the HUD; a second single tap hides it; playback state never changes.
2. Double tap left/right seeks 10 s; double tap centre toggles play/pause; the icon matches the real state.
3. Horizontal drag scrubs with a time label; release seeks once; no red spinner flashing during the drag.
4. Left vertical drag changes brightness, right vertical drag changes volume, neither moves the HUD or seeks.
5. Lock ignores everything until unlocked; BACK unlocks first.
6. Site menu: the site's bars appear, subtitles can be opened and a language picked **with no flicker and no
   play/pause flip**; the chosen subtitles render; "Done" or 8 s idle returns to our HUD.
7. Brightness at 150%: open the site's subtitle menu, it stays put and does not flicker.
8. Speed, aspect and fullscreen buttons work; aspect resets when the video element is replaced.
9. Home-page hero trailers, small inline players, and the search/browse pages are untouched (no HUD, taps normal).
10. Settings switch off: everything behaves as before this change. `adb logcat` shows no `overlay` kills while
    using any player menu.
11. TV build (`--target tv`) still builds and `npm test` is green.

## 7. Suggested order of work
1. Phase 0 capture. 2. Fixes A, B (with tests) and re-test subtitles in the old UI. 3. Fixes C, D.
4. `playerhud.js` gestures only (3.1 to 3.3) with a debug outline, wire-up 4.1, test taps on all sites.
5. HUD visuals (3.4). 6. Site mode and hiding (3.5). 7. Off switch, volume, BACK (4.2 to 4.4). 8. Checklist.

## Known risks
- A site may detect that its controls never receive pointer events and behave oddly (for example auto-pause
  overlays). The off switch and site mode are the fallbacks.
- Cross-origin players that listen for taps in a **nested** frame below the one holding our layer are fine (our
  layer is in the frame that holds the `<video>`), but a site that draws its controls in a *parent* frame over
  the video frame (Moonflix wrapper) will still receive taps in that parent first. If the checklist fails there,
  the layer for that site must be created in the wrapper frame and talk to the video frame with `postMessage`;
  treat that as a follow-up, not part of this plan.
- Selecting subtitles is still the site's UI. If the glitch survives fixes A to D on some source, record it in
  Findings with the logcat lines; it is then a site-specific issue needing a look with `chrome://inspect`.

## Findings
(fill in during Phase 0)
