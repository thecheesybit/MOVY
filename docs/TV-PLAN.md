# Android TV — the full plan (audit → design → build)

The user's goal: a polished TV app. Each of the 4 sources has a different UI: home layout, title page, **server
picker**, player, and animations in front of the player. Every part of each one must be reachable and make sense with the
Sony remote (D-pad, OK, BACK, play/pause, CH±; MENU, ⚙ and ☰ never reach apps).

## User decisions (2026-11-07)
1. **Auto-fullscreen:** pressing Play on any site goes straight to fullscreen video. BACK leaves fullscreen.
2. **Auto-hide after 5 s with no input:** everything hides, and the first key press brings it back **without acting**:
   - Movy's own panels (Island, Settings, hints, pointer, seek bubble);
   - the site player's controls and server buttons;
   - the site's menus and headers (only the content shows).
3. **Always immersive on TV:** no system bars, ever. Re-apply immersive mode on every focus or resume, dialog, IME close and
   fullscreen exit, so nothing can bring the bars back.
4. **Video always fullscreen on TV:** once playback starts, the video stays fullscreen until the user presses BACK. If the site
   or player exits fullscreen by itself (episode change, server switch, ad, re-render), re-enter automatically.
5. **Hide the site's own fullscreen/exit-fullscreen button on TV**, but only where it is safe. Per source, the profile lists
   `player.fullscreenButton` selectors that are hidden with CSS (never removed from the DOM). A source keeps its button if
   hiding it breaks the player (verified per site on the TV). BACK is the only way out of fullscreen.

6. **Smart cache (TV), 30 days:** images and static site files are kept on the TV so browsing is instant. Changed content
   is picked up; anything older than 30 days is deleted and fetched fresh. See "Smart cache" below.
7. **A Netflix / Prime-grade viewing experience**, defined in "Viewing experience" below.
8. **Test on the TV only.** No PC-side browser inspection. Inspect the TV's own WebView over CDP with a debuggable build
   (same signing key, versionCode above the installed one, `install -r`), then finish with a release build.

## Lean by design (user's top TV priority, 2026-11-07)

The TV app must **never store the movie itself** and must never get heavier or slower over time.
- **No media on disk, ever.**
  - AssetCache never stores media (as below).
  - Chromium's own HTTP cache can keep fetched HLS/DASH segments, so on TV:
    - on every cold start, before the WebView is created, measure the WebView HTTP cache directory and, if it is over **100 MB**,
      clear only that disk cache (`clearCache(true)` on a throwaway WebView or delete the cache dir). Never touch cookies,
      localStorage or IndexedDB: logins are kept;
    - the same trim runs when the app goes to the background after playback.
  - No offline or download features on TV: block `DownloadManager`/downloads entirely in the tv flavor.
- **RAM only when needed:**
  - **Background (`onStop`):**
    - pause media in all frames;
    - `webView.onPause()` + `pauseTimers()`;
    - stop the idle, health and remote-config timers;
    - `clearCache(false)` (RAM cache only);
    - release the native overlays (cursor, bar bitmaps);
    - flush cookies.
  - **Background for more than 60 s:** destroy the WebView and finish the activity (state is restored on return by reopening the last
    URL of the active source). **Exit with BACK:** flush everything, `finishAndRemoveTask()`, then end the process, so nothing lingers in
    RAM.
  - `onTrimMemory`: on `TRIM_MEMORY_RUNNING_LOW` and above, drop the AssetCache in-memory index and the RAM caches; on `UI_HIDDEN`,
    behave as background.
  - Kill heavy decorative media on TV through the profile `killOnTv` (the 7Movies title-page YouTube trailer, the Moonflix hero trailer):
    no autoplay trailers.
  - No leaks: listeners and handlers are removed on destroy. Verify that `dumpsys meminfo` PSS returns to near-baseline after 10
    open/play/close cycles.
- **Acceptance (measured on the TV):**
  - PSS after 1 h of mixed use stays within +15% of the PSS after the first load;
  - the app's total data size (`du` of the app dirs, `dumpsys diskstats`) stays under ~400 MB (300 MB AssetCache + ≤100 MB WebView cache), and media
    segments are never found on disk (`find` for `.ts/.m4s/.mp4` or large blobs);
  - after BACK-exit the process is gone (`pidof`);
  - after 60 s in the background the WebView is destroyed (logcat).

## Smart cache (TV; the module is shared but enabled by config `cache.enabled`, default on for tv)

Implemented in `shouldInterceptRequest` (after adblock and DoH), in a new `AssetCache` class (disk LRU in
`getCacheDir()/movy-assets`).
- **Cached:** GET responses of type image (`image/*`), font, and versioned static JS/CSS (a hashed filename or `?v=` query) from the
  source hosts and their CDNs. Status 200 only. No `Set-Cookie`. Respect `Cache-Control: no-store/private`.
- **Never cached:** HTML documents, XHR/fetch API JSON, video and audio segments (`.m3u8`, `.ts`, `.m4s`, `.mp4`, media MIME),
  anything with a `Range` header, POST, and any other auth-bearing request.
- **Freshness:**
  - an entry is served instantly from disk, and revalidated in the background at most once per 24 h with
    `If-None-Match`/`If-Modified-Since`;
  - a 304 refreshes the timestamp, a 200 with a new body replaces it, a 404 evicts it;
  - this way "if something changes it gets added".
- **Expiry:** an entry is deleted 30 days after it was first stored, whatever the server says. On startup, and daily, sweep
  expired entries.
- **Size cap:** 300 MB on TV, with LRU eviction. Writes are atomic (temp file plus rename); a corrupt entry is dropped.
- **CORS:** keep the original response headers (`Access-Control-Allow-*`, `Content-Type`), so cached fonts and images still
  work cross-origin.
- **Settings → Storage:** shows the cache size; "Clear Cache" also clears AssetCache.
- **Tests:** unit-test the cacheability rules, the expiry and revalidation decision, and the LRU accounting. On the TV, measure
  the second-visit load of each home page (cold against warm) and report the speed-up.

## Viewing experience (the Netflix / Prime bar)

Measurable acceptance criteria for every source on the TV:
- **Launch:** splash, then the default source's home page **in under 2.5 s warm**. No white flash anywhere: the WebView background is black, and so is
  every transition.
- **Browsing:**
  - focus moves in **under 100 ms** per key (the tvnav candidate cache must beat the 100 ms seen in W9; cache candidates, recompute
    on mutation);
  - smooth scroll, a scale-up focus animation, and rows that scroll horizontally like a TV app;
  - the focused card is always fully on screen.
- **Play:** OK on Play or a title goes straight to fullscreen video. The spinner shows until the first frame; **no player chrome, ads or
  pop-ups** are visible.
- **In playback** (one consistent control model on all 4 sites, whatever the site player is):
  - OK = play/pause, with a brief native overlay showing title, progress and time;
  - LEFT/RIGHT = −10/+10 s (hold to accelerate: 10 s, then 30 s steps), with a native progress bubble;
  - UP = show the native info/controls bar, which includes **Servers**, **Episodes** (if the site has them) and **Next episode**;
  - DOWN = the same bar;
  - play/pause key = play/pause;
  - BACK = hide the bar, then leave fullscreen, then go back to the title page.
  - After 5 s idle everything hides, and only the picture remains.
- **Continue watching:** relaunching or re-opening a title resumes where the site's own resume feature leaves off. Don't break it, and
  check it per site.
- **Errors:** a dead server shows a native "This server isn't working — try another" with the Servers list focused; the user is never stuck.

## Architecture: generic engine plus per-source TV profiles (live-updatable)

Don't hard-code the sites in Java/JS. Add `tvProfiles` to the app config, overridable from Remote Config, so a site redesign
is fixed over the air:

```jsonc
"tvProfiles": {
  "<sourceId>": {
    "header":        ["css selector", ...],   // hidden after 5 s idle, shown on input
    "focusables":    ["extra selectors made focusable (cards, server chips)"],
    "skip":          ["decorative/animated elements never focused"],
    "playButtons":   ["selectors that start playback → trigger auto-fullscreen"],
    "servers":       { "list": "selector", "item": "selector", "active": "selector" },
    "player":        { "frame": "iframe/container selector", "video": "video selector (same-origin)",
                       "fullscreenButton": ["selectors of the site's fullscreen toggle, hidden on TV if safe"],
                       "overlayKill": ["animations/intro layers in front of the player to remove"] },
    "rows":          ["horizontal carousels: LEFT/RIGHT scroll inside, UP/DOWN between rows"]
  }
}
```

- `packages/inject/tvnav.js` becomes profile-driven: generic spatial navigation, plus row-aware navigation for carousels.
  Profile selectors win over heuristics.
- **The server picker** gets a native **Servers sheet**: OK on the player, or a dedicated key (CH− in fullscreen?), lists the
  servers the profile finds (names only), and selecting one clicks it inside the page or frame. That makes
  "switch server" reachable even when the site hides it behind hover.
- **Cross-origin player iframes:** our injected scripts run in all frames (`addDocumentStartJavaScript` with "*"), so the
  player frame gets its own tvnav/autofullscreen instance. Coordinate through `postMessage` with a small protocol.
- **Auto-fullscreen:** when playback starts after a user OK (the `playing` event in any frame), request fullscreen on the
  best container (the profile `player.frame`, else the video's nearest player container, else the iframe element in the
  parent). The W8 fullscreen guard stays.
- **Idle controller** (native plus JS): a single 5 s idle timer in native. On idle, native hides its views and calls
  `__movyTv.idle(true)` in all frames, which adds a class that hides the profile `header`, the player controls (generic:
  `[class*=control]`, `.vjs-control-bar`, `.jw-controlbar`, `.plyr__controls`, …, plus the profile) and the cursor.
  The first key after idle calls `idle(false)` and is **swallowed**.
- **The native Island and Settings on TV** stay as W9 and W10 built them: hold-OK, ▶ at the edge, CH±.

## Phase A — audit (one worker, read-only plus screenshots)

For **each of the 4 sources**, on the TV (and in desktop Chrome via CDP for the DOM), document:
- home: layout, header/menus, carousels, which elements are clickable, what tvnav focuses today, and what's unreachable;
- the title/detail page: Play, seasons/episodes, the server picker (where it is, how it's labelled, how many servers, hover-only?);
- the player: same-origin or cross-origin iframe, the player library (video.js/jwplayer/plyr/custom), its control selectors, the
  fullscreen button, autoplay, intro animations or overlays in front of it, and ads/popups at play time;
- search: the input, results, keyboard behaviour;
- the profile selectors: a **draft `tvProfiles` entry** per source;
- a gap list: everything that's unreachable, confusing or broken with the remote today.

Output: `docs/TV-AUDIT.md`, using source **names** only (no hosts), with screenshots in the scratchpad. Real selectors go in the draft
profiles inside git-ignored `config/apps/movy.json`.

## Phase B — orchestrator design review

The orchestrator reviews the audit and finalises the per-source flows, profiles and any extra native UI.

## Phase C — build (one worker)

Implement the architecture above:
- the profile schema in `packages/config` (zod + tests) and remote override;
- the profile-driven tvnav, the Servers sheet, auto-fullscreen, the idle controller and permanent immersive mode.

Then run a full scripted TV pass per source: home → browse → title → change server → play → auto-fullscreen → idle-hide → wake →
seek → BACK chain. Screenshots for each step, with a pass/fail table.

## Phase D — final release APKs

- **Phone + tablet:** one APK (`movy-phone.apk`, package `com.ayushkumar.movy`). It adapts at runtime by screen size.
- **TV:** `movy-tv.apk` (`com.ayushkumar.movy.tv`).

Both are signed with the stable key and copied to `dist/movy/release/` with clear names and version.
