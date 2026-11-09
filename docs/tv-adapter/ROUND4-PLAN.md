# Movy TV — Round 4 plan: speed, caching, players, subtitle panels, rail spacing

Written 2026-11-08 after reading the native load/cache code and inspecting all four sites live in a desktop
browser at 1280×720 (the same CSS viewport the TV lays the page out at). **No source URLs in this file, in commits,
in logs or in UI, by design** — sites are named Movy, Cinejoy, 7Movies, Moonflix.

Read this whole file before touching code. Work the phases in order; each phase ends with a verification gate that
must pass before the next one starts.

---

## 0. Ground rules (DO / DON'T)

**DO**
- Keep the TV app lean (user's top priority): never store movie media on disk, release RAM in background, no slowdown
  over time. Media segments, `.m3u8`, `.ts`, `.m4s`, `.vtt` stay out of every cache we own.
- Keep every change profile-driven where it is site-specific: selectors go in `config/apps/movy.json` →
  `tvProfiles.<site>.player.*` (and the built asset copy), not hard-coded `if (host === …)` branches in JS.
- Re-use the existing machinery: `TV.menuNow()` / `openPlayerMenu(PF)` / `TV.back` (tvfs.js:663) /
  `controlVisible()` (tvfs.js:634) / `isSkipped()` (tvnav.js:133) / `AssetCache` + `CachePolicy`.
- Add a unit test for every pure rule you change (`CachePolicy`, `LeanPolicy`, `tvnav-pure.js`). `npm test` and the
  Gradle unit tests must stay green.
- Verify on the emulator with the harness (`scripts/tv-test/b.ps1`, `t.sh`, `cdp.mjs`) after every phase; take a
  screenshot per site into `docs/tv-adapter/screenshots/round4-*`.
- Keep files under ~800 lines. `tvnav.js` is already 1420 lines: put new player-menu logic in a new
  `packages/inject/tvmenu.js` (and register it in `CosmeticBridge` + `b.ps1` copy step) rather than growing tvnav.js.
- Log with existing tags (`MovySourcePreload`, `MovyLifecycle`, new `MovyAssetCache`), never with URLs in release
  builds (use `KillLog.describe(url, debug)` style redaction).

**DON'T**
- Don't block the UI thread or a WebView intercept thread on network I/O (see §2.1 — this is the current #1 slowness).
- Don't add a second hidden WebView, and never keep one alive during playback.
- Don't dispatch synthetic `mouseover`/`mousemove` on catalog cards (Cinejoy opens hover-preview popups; dossier).
- Don't click anything whose effect is navigation to an ad (first click on every site fires a popunder; §4.6).
- Don't commit or push. Leave the work tree with the changes for the user to review.
- Don't change the phone flavor or Electron unless a shared file forces it; if so, keep their behaviour identical.

---

## 1. Findings (why it is slow, why it doesn't cache, why players feel buggy)

### 1.1 Load path / caching (native)
| # | Finding | Where | Effect |
|---|---|---|---|
| C1 | **Asset cache miss = synchronous `HttpURLConnection` fetch on the WebView intercept thread** (`AssetCache.serve → fetchAndStore → HttpFetcher.fetch`). Every uncached poster/font/JS goes through a separate HTTP/1.1 client with no connection reuse across Chromium, no HTTP/2 multiplexing, 8 s/15 s timeouts, and Chromium only has a small pool of intercept threads. | `AssetCache.java:~115-210`, `HttpFetcher.java` | First run is **slower than with no cache at all**; 300–400 posters per home page (Cinejoy 376 imgs, Moonflix 401) queue behind a handful of threads. |
| C2 | **Hidden preload WebView bypasses our disk cache.** `SourcePreloader.Client.shouldInterceptRequest` only runs the ad filter; it never calls `AssetCacheInterceptor`. It warms Chromium's HTTP cache only. | `SourcePreloader.java` | The visible WebView then intercepts the same images through AssetCache → miss → re-download (C1). Preload gives ~no benefit. |
| C3 | **Chromium HTTP cache is wiped whole when it passes 100 MB** — at cold start (`TvLifecycle.trimColdStart`) and after backgrounding (`trimHttpCacheAfterPlayback`). One movie's MSE segments fill it, so after any watch session the *entire* cache (JS, CSS, images of all 4 sites) is deleted. | `TvLifecycle.java`, `LeanPolicy.HTTP_CACHE_LIMIT_BYTES` | "It is not caching": after every watch, next launch is a cold network load. |
| C4 | **Most JS chunks never qualify as `STATIC`.** `CachePolicy.HASHED` needs a `.`/`-`/`_` before an 8+ char hash. Real names: Movy `/_next/static/chunks/1f1lweywc0f8c.js`, `0l86xu2682u-8.js`; Cinejoy `/_app/immutable/chunks/Dr38vCwT.js` (78 modulepreloads, ~1 MB JS). These miss. 7Movies (`2619-38012e79151e370a.js`) and Moonflix (`/assets/index-C_jkdOQv.js`) match. | `CachePolicy.java` | Cinejoy and Movy re-download their JS bundles on every cold start once C3 wipes Chromium's cache. |
| C5 | `readEntry` re-parses the JSON meta file from disk on every hit, and `validatedAt()` reads it again. | `AssetCache.java` | Extra disk I/O per image on the intercept thread. |
| C6 | Preloader is one-shot per process and `stop()` is permanent (`stopped=true`) — after the first fullscreen it never resumes; it also starts only 3.5 s after the first page finished. On TV every launch is a cold process (BACK kills it; 60 s background kills it). | `SourcePreloader.java`, `TvLifecycle.exitApp` | Warm-up rarely completes; nothing is "ready" when the user switches. |
| C7 | Moonflix loads 6 analytics/session-replay stacks (Clarity, Heap, PostHog, Yandex Metrika, GA, Umami) plus Cloudflare insights. Session replay is heavy on an ARMv7 TV. Movy/7Movies carry several rotating ad hosts. | live network log | CPU/RAM on the Bravia; verify the ad engine blocks all of them (BlockStats). |

### 1.2 Left rail spacing
Mini rail is `position:fixed; left:0; width:60px; padding:16px 8px` (tvnav.js:1276). Where each site's content
starts at 1280 px layout width:

| Site | First content x | Gap after 60 px rail |
|---|---|---|
| Movy | 96 | 36 px (ok-ish) |
| Cinejoy | 48 (logo/back button at 48) | **−12 px — overlaps** |
| 7Movies | ~51–55 | **≈ −8 px — overlaps** |
| Moonflix | ~50 (logo), rows 80 | **overlaps / touches** |

That is the "pushed to the left, no breathing space" look: the rail's gradient sits on top of the hero title and
the first card on three of four sites.

### 1.3 Players and subtitle panels (inspected live)

**Movy** — same-origin `#vp-shell` (fixed inset-0), `<video>` blob/MSE.
- Top-right toggles: `Quality` and `Audio & Subtitles` (`.tabbable`), each wrapped in `div.relative`.
- Popover: `div.fixed…md:absolute…` → `.player-surface` (this is `player.menu`). **Closed popovers stay mounted** with
  `opacity-0 pointer-events-none` on the wrapper.
- Audio & Subtitles popover = **two columns** (Audio | Subtitles); each column is its own scroll list
  `.custom-vertical-scroll` (subtitles 337 px tall, 40+ buttons such as `eng (SDH)`), footer buttons
  `Search on OpenSubtitles` and `Customize appearance`. Quality popover = Quality | Server columns + Speed row;
  every server has a `Favorite server` star button (already skipped).
- **Escape keydown on `document` closes it.**

**Cinejoy** — same-origin `.player-root`, controls bar `absolute bottom-0`, auto-hide ≈ 3 s, cue overlay `.lp-subtitle`.
- Bottom-right buttons: `aria-label` = `Servers`, `Subtitles`, `Settings`, `Picture in Picture`, `Fullscreen`,
  also `Cast to Device` top-right.
- One popover `.settings-popover[role=dialog]` (Svelte, **mounted on open / removed on close**), drill-in pages, the
  subtitle page has an accordion of languages (`English 73`, `Arabic 22`…) inside `.menu-card-scroll-body`
  (437 / 2250 px), **a text `input[placeholder=Search]` and an `input[type=file]` (upload)**.
- **Escape does NOT close it; outside pointer/click does NOT close it. Only clicking its toggle button again does.**
- Detail page has a `Download` button (should be skipped on TV). Some titles return "Server error" even on desktop —
  test with a well-known title.

**7Movies** — the watch page `/movie/<id>/watch` hosts a **cross-origin embed iframe** (third-party player host).
Inside it (`#player.player`):
- Top: `#lbBack`, `#lbQuality` ("Quality Auto · <server>"), `#lbSubs` ("Audio & Subtitles"). Bottom: `#playBtn`,
  `#rewindBtn`, `#forwardBtn`, `#muteBtn`, `#lb_lockBtn`, `#lb_pipBtn`, `#lb_castBtn`, `#lbFs`; `#introSkip`.
- **`#centerToggle` is a 1280×720 full-cover button** — must never be a spatial-nav candidate (it swallows
  directional moves); OK already maps to play/pause.
- Open menu = `.lb-menu.show` (`#lbQualityMenu`, `#lbSubsMenu`), two columns `.lb-col` (subtitles 360 / 2730 px
  scroll), drill rows `.lb-opt.lb-drill` ("Customize appearance", "Sync to audio"). Player gets `hold-controls`
  while a menu is open. **Escape closes it.**
- **Legacy hidden panels stay in the DOM**: `#subtitlesPanel`, `#settingsPanel`, `#serverPanel`, `#episodesPanel`
  (`.panel`, opacity 0, pointer-events none) containing `#subtitleSearch` (text input) and `#subtitleUploadBtn`.
  These must never be candidates.
- The embed URL already carries `seek=10`, `fs=parent`, `mobileSheets=false`, `exit=1`.
- Outer page has an `Ads` preference pill (`.pref-pill`, skipped already) and a detail-page muted trailer with its
  own `Unmute trailer` button (target for the volume-key unmute).

**Moonflix** — detail is an in-page modal; Play opens a **cross-origin iframe** `iframe[title="Moonflix player"]`
on the player subdomain. Inside: `#video-container`, `video#video`, `#controls-bar` (gradient), buttons by id:
`#back-button`, `#custom-play-button`, `#skip-backward-button`, `#skip-forward-button`, `#mute-button`,
`#audio-subtitle-toggle`, `#speed-button`, `#servers-button`, `#fit-button`, `#fullscreen-button`,
`#skip-prompt-btn`, `#center-play-pause-button` (96×96 centre).
- Menus `#audio-subtitle-menu`, `#speed-quality-menu`, `#server-picker-menu`, shown/hidden with the **`hidden`
  class**. Subtitle menu = Audio | Subtitles columns of **`li.track-selector` with `tabindex=-1`** (not focusable),
  then **`Cancel` / `Apply` buttons — a choice only takes effect after Apply**.
- **Escape does NOT close; `Cancel` does.**
- `#episode-panel` is `display:flex` but parked off-screen at x=1280 for movies — exclude unless on-screen.
- Subtitles are native text tracks (`mode: showing`), so they render as `::cue`.

**Cross-site facts**
- Only **Movy** has `player.menu` / `player.controls` in its profile. Cinejoy, 7Movies, Moonflix have none →
  `TV.menuNow()` is always null there → BACK in an open panel exits the player instead of closing the panel, focus is
  not confined to the panel, and the "back to the toggle" logic never runs. **This is the root of "I need to move out
  of the subtitle panel".**
- Three of four subtitle UIs contain a free-text search input (+ a file input). On Android TV a focused text input
  takes LEFT/RIGHT as caret moves and OK opens the IME → focus feels trapped.
- All four use **inner scroll lists**; focus moves must `scrollIntoView({block:'nearest'})` on the list, not the page.
- The **first click on every site fires a popunder** (window.open to a rotating ad host) and the site action is lost.
  On TV this is "first OK does nothing".

---

## 2. Phase A — Speed and caching (native, Java)

### 2.1 Make the asset cache never slower than the network
1. In `AssetCache.serve`, on a **miss return `null` immediately** (Chromium loads it itself, at full speed) and
   enqueue an **async fill**: a bounded background executor (2–3 threads, low priority) fetches and stores it. Dedupe
   in-flight keys. Hits stay synchronous from disk.
2. Keep the conditional revalidation in the background as today.
3. Keep `HttpFetcher` but give it a shared keep-alive (default `HttpURLConnection` pool is fine once calls are off the
   intercept path). Consider OkHttp only if already on the classpath (check Capacitor deps); do not add a heavy dep.
4. Hold meta (mime, kept headers, storedAt, validatedAt) in the in-memory `CacheIndex`; read the meta file only when
   the index entry is missing (C5). Rebuild index lazily after `releaseMemory()` (already flagged by `indexDropped`).
5. Tests: `AssetCacheTest` — miss returns null and schedules exactly one fill; second identical miss while in flight
   schedules none; hit after fill is served from disk; expired entry → null + refill.

### 2.2 Cache the bundles that matter
1. `CachePolicy.classifyRequest`: treat as `STATIC` any `.js/.mjs/.css` whose path contains an **immutable build
   directory** — `/_next/static/`, `/_app/immutable/`, `/assets/` (Vite), `/static/js/`, `/static/css/` — even without
   a delimiter-hash. Keep the old hash rule as a fallback. Response side already requires a JS/CSS content type.
2. Also accept extension-less image URLs when `Accept` starts with `image/` (already) — confirm Movy's
   `/img/w500/<id>.png/q50.webp` and TMDB `w300/w500/w1280` hit `IMAGE`.
3. Tests: one per real pattern above (no site hosts in test strings; use `https://example.test/_next/static/chunks/1f1lweywc0f8c.js` etc.).

### 2.3 Stop wiping everything after a movie
1. Our own `AssetCache` (300 MB, 30 days, LRU) is now the durable store for JS/CSS/images/fonts. Chromium's HTTP cache
   can stay a throwaway — but do not wipe it on **every** cold start: raise `HTTP_CACHE_LIMIT_BYTES` only if needed,
   and prefer `WebView.clearCache(true)` *only when backgrounded after playback* (existing path). Keep cold-start trim
   but at a higher threshold (e.g. 250 MB) so normal browsing isn't wiped. Update `LeanPolicy` tests.
2. Make sure `AssetCache` never stores media (already excluded by `MEDIA_EXT` + content type) — add a test with a
   `video/mp2t` response and an `.m4s` URL.

### 2.4 Real preloading ("first run takes ~5 s, then everything is instant")
1. **Route the hidden preload WebView through the same interceptor**: in `SourcePreloader.Client.shouldInterceptRequest`,
   after the ad filter, call `AssetCacheInterceptor.intercept(request)`; with 2.1 a miss returns null and enqueues a
   fill, so warming now fills our durable cache. Pass the interceptor into `SourcePreloader`'s constructor.
2. Make the preloader **resumable**: `stop()` → `pause()` (keeps the queue); resume when fullscreen ends and the user
   has been idle on a browse page ≥ 10 s. Start delay 1.5 s after first `onPageFinished` (was 3.5 s).
3. Persist a per-source "warmed at" timestamp in `Prefs`; skip a source warmed < 12 h ago whose cache entry count for
   its hosts is non-trivial. This keeps the TV from re-downloading three home pages on every launch.
4. **First-run warm-up on the splash** (user request): if `Prefs` says no source was ever warmed, keep the splash
   (with the existing red spinner + a subtle "Preparing your sources…" line) for **at most 5 s** while the active
   source loads; the hidden warm-up starts in parallel immediately. After 5 s, reveal regardless. Later launches never
   wait. Implement in `LaunchController` (it owns the splash) behind a `firstRunWarmup` flag.
5. Scroll warm-up: in the hidden page, after `onPageFinished`, run one `evaluateJavascript` that scrolls the document
   to the bottom in 3 steps (300 ms apart) so lazy rows request their posters, then settle 3 s. Never click anything.
6. Optional (only if time allows, measure first): DNS/TLS pre-connect for each source's main + image hosts with a HEAD
   request from the fill executor at launch.
7. Log timings: `MovySourcePreload warmed <id> in <ms>, fills=<n>`; `MovyAssetCache hits=<n> misses=<n> bytes=<n>`
   once per page finish (debug builds only).

### 2.5 Analytics / ad weight
1. With a debug build, open each source home and dump `BlockStats` + a list of *allowed* third-party hosts per page
   (redacted in release). Anything matching analytics/session-replay (Clarity, Heap, PostHog, Yandex Metrika, GA,
   Umami, Cloudflare insights) or rotating ad hosts must be blocked. If the bundled lists miss them, add rules to the
   app's custom filter list (find where `AdblockEngine` loads lists; keep them in `config`/assets, not code).

### Gate A (must pass)
- Unit tests green. Emulator: cold-launch Movy twice; second launch `MovyAssetCache hits` ≫ misses and home visibly
  paints faster. Measure with `adb logcat` timestamps from process start → first `onPageFinished` → first poster
  painted (CDP: `performance.getEntriesByType('resource')` count of images with `transferSize===0` on 2nd run).
- Switch source via Island after warm-up: record time to first paint before/after (target: ≥ 40 % faster). Write the
  numbers into `docs/tv-adapter/REPORT.md` "Round 4".
- Watch 2 minutes of a stream, background, return: JS/CSS/images still served from `movy-assets` (not re-fetched).

---

## 2b. Phase A2 — Versioned local site shell (user request, after Gate A)

Goal: the app opens each source **from a local copy instantly**, checks the live site in the background, and always
ends up on the **latest** version. Do this after Phase A passes (it builds on AssetCache + async fill).

### What a "version" is
A **snapshot** = one source route's HTML document + the list of asset URLs it references (scripts, styles, preloads,
fonts, hero images) + metadata:
`{ tag (monotonic int), hash (sha256 of normalised HTML), buildId, fetchedAt, url, etag/lastModified, status }`.
- Normalise before hashing: strip nonces, CSRF tokens, timestamps, ad-script tags, inline JSON blobs that change every
  request (keep a per-profile `shell.volatile` regex list); otherwise every fetch looks "changed".
- `buildId`: Next.js `__NEXT_DATA__.buildId` / build manifest path, SvelteKit `/_app/version.json` or `_app/immutable/entry/start.<hash>.js`,
  Vite `index-<hash>.js`. A changed buildId = a real deploy.
- The assets themselves live in `AssetCache` (no duplication). Assets referenced by any **retained** snapshot are
  **pinned** (exempt from LRU eviction) so an old snapshot can always boot.

### Ring buffer (why not "5 old + 5 upcoming")
Future versions cannot be fetched before the site publishes them, so "upcoming" slots would always be empty. Keep
**3 slots per route**: `current`, `previous`, `older` (configurable `shell.keepVersions`, default 3, max 5). A new
version pushes in at the front and the oldest drops out (its pins are released). Older versions exist only as
rollback when the newest fails to boot.

### Which routes
Only shell-like entry routes per source, listed in the profile (`tvProfiles.<site>.shell.routes`): the start/home
route and the top nav sections (movies, tv). Never detail/watch pages, search, account pages, or anything with a
query string. Skip snapshotting a response that is not `200 text/html`, is a bot/CDN challenge page, is under
5 KB, or lacks the site's root element (`#__next`, `#root`, Svelte root div).

### Launch flow ("local first, latest always")
1. Main-frame GET for a shell route → `shouldInterceptRequest` serves `current` from disk instantly (status 200,
   `text/html`, kept headers only; no Set-Cookie is replayed). At the same time start a **revalidation fetch** of the
   live HTML (conditional request with ETag/If-Modified-Since, via the async executor, never on the intercept thread).
2. **Fast-network race**: if the live response comes back within `shell.raceMs` (default 700 ms) *before* we answered,
   answer with the live document instead (and store it if changed). So on a good connection you are always on live.
3. Live unchanged (304 or same hash) → touch `fetchedAt`, done.
4. Live changed → store as a new snapshot (`tag+1`), prefetch its new assets into AssetCache in the background, and:
   - if only volatile data changed (same buildId): use it from the next navigation; no reload.
   - if the **buildId changed** (a deploy): once its assets are in cache, apply it at the next safe moment — the next
     route change, the next app launch, or immediately if the user is still on the home page and idle (not in a
     player, not in a menu): reload silently behind the splash/loading spinner. Never reload during playback.
5. **Max staleness**: never serve a snapshot older than `shell.maxAgeHours` (default 12; SSR sites Movy/7Movies 6,
   because their HTML embeds row data) — past that, wait for live (normal loading UI).
6. **Boot health check**: after a snapshot is shown, the injected script reports `shellBoot` OK when the site's root
   has rendered content within 4 s and no chunk-load error fired (`ChunkLoadError`, failed module import, 404 on a
   pinned asset). On failure: mark the snapshot `bad`, reload from live; if live also fails (site down / no network),
   boot `previous`. That is the only reason old versions are kept.
7. Offline / site down: serve the newest good snapshot (better than an error page), with the existing error/failover
   UI if the API calls inside fail.

### Implementation notes
- New pure class `ShellPolicy` (route match, normalise+hash, staleness, ring push/evict, race decision) with unit
  tests; `ShellStore` (disk: `cacheDir/movy-shell/<source>/<routeKey>/<tag>.html|.json`, atomic writes like
  AssetCache); wire into `AdblockWebViewClient.shouldInterceptRequest` for main frame **after** the ad/nav checks.
- Preloader (2.4) also refreshes snapshots for the other sources, so switching is local-first too.
- Settings → Clear cache must also clear `movy-shell`. Release RAM drops only in-memory indexes.
- Logs (debug): `MovyShell served <source> v<tag> age=<min> race=<live|local> changed=<y/n> build=<same|new>`.

### Gate A2
- Second cold launch: home document served locally (`MovyShell served … race=local`), first paint measurably earlier
  than Gate A numbers (record in REPORT.md).
- Simulate a deploy: edit a stored snapshot's buildId/hash on the emulator (adb run-as) → app detects a new version,
  prefetches, applies it without breaking playback.
- Corrupt a pinned asset → boot check marks the snapshot bad and the live site loads.
- Airplane mode → newest good snapshot appears instead of an error.

---

## 3. Phase B — Left rail breathing space

1. Add profile key `tvProfiles.<site>.railInset` = list of `{ selector, prop }` (prop `padding-left` or `left`)
   naming the site's header and main content wrappers; tvnav applies `calc(<original> + var(--movy-rail-gutter))` via
   a CSS rule only while the mini rail is visible (`html.movy-rail-mini`). Gutter target: **content starts at
   ≥ rail width + 28 px** (≈ 88 px at the current 60 px rail).
   - Measure per site with CDP: `Math.min(...visible header/hero/card lefts)`; pick the outermost wrappers that move
     header + hero + rows together (hero title must move too). Movy needs little or none (starts at 96).
   - Do **not** use `transform` on `body`/`html` (breaks `position:fixed` and fullscreen).
2. Rail polish: icons centred in the 60 px column with 14 px top/bottom item padding; the mini rail background becomes
   a very faint gradient only behind the icons (not across the hero); the collapsed rail should never cover text.
3. When the drawer is open (310 px) the page does not reflow (overlay + scrim, as today).
4. Remove the inset class while players/fullscreen/modals are up (rail hidden there already).

### Gate B
- Screenshots of all four home pages + one detail page each: nothing under the rail, ≥ 24 px clear space between rail
  edge and first text/card. Focus ring on the first card is fully visible (not clipped by the rail).

---

## 4. Phase C — One player model on every site

### 4.1 Target behaviour (same on all four)
| Key | No control focused (free playback) | A control focused | A menu/panel open |
|---|---|---|---|
| OK | play/pause | activate control | select item (see 4.3) |
| LEFT/RIGHT | seek −/+10 s (existing) | move along the bar | move between columns; at the outer edge: stay |
| UP | focus top bar (back / quality / subtitles) | move up; from top bar: stay | move in list; at top of list: stay |
| DOWN | focus bottom bar | move down; from bottom bar: release to free playback | move in list; at bottom: go to the footer buttons |
| BACK | exit player (shell) | release focus to free playback | close the menu, focus returns to its toggle |
| any key | wakes controls, restarts the 5 s fade | same | menu keeps controls held (no fade while open) |

After 5 s idle: every control fades; **only the video and its subtitles stay visible**; subtitle overlays that sit
above the control bar are moved back to the bottom when the bar is hidden.

### 4.2 Profile keys (add to `tvProfiles` for all four; Movy already has some)
```
player.toggles     : buttons that open a menu (e.g. Movy ".tabbable" in the top bar; Cinejoy
                     button[aria-label=Subtitles|Settings|Servers]; 7Movies #lbQuality,#lbSubs;
                     Moonflix #audio-subtitle-toggle,#speed-button,#servers-button)
player.menu        : the open-menu container (Movy ".player-surface" [existing]; Cinejoy ".settings-popover";
                     7Movies ".lb-menu.show"; Moonflix "#audio-subtitle-menu:not(.hidden)",
                     "#speed-quality-menu:not(.hidden)", "#server-picker-menu:not(.hidden)")
player.menuItems   : selectable rows inside a menu (Moonflix "li.track-selector"; others: buttons)
player.menuColumns : column containers for LEFT/RIGHT (Movy the two ".custom-vertical-scroll" lists' parents;
                     7Movies ".lb-col"; Moonflix audio/subtitle track containers)
player.menuClose   : how to close: "escape" (Movy, 7Movies) | "toggle" (Cinejoy) | selector "button:Cancel"
                     (Moonflix) — express as {mode, selector}
player.menuConfirm : Moonflix only — "Apply" button selector; auto-pressed after an item is chosen
player.controls    : focusable bar controls (add for Cinejoy, 7Movies, Moonflix using the ids/labels in §1.3)
player.never       : never candidates (7Movies "#centerToggle", ".panel:not(.open)", "#subtitleSearch",
                     "#subtitleUploadBtn"; Cinejoy "input[type=file]", ".settings-popover input[type=text]";
                     Movy/Cinejoy "Search on OpenSubtitles" only if it opens a text search; Moonflix
                     "#center-play-pause-button", "#episode-panel" when off-screen)
player.subtitles   : the cue overlay to keep visible (Cinejoy ".lp-subtitle" parent; Moonflix native ::cue)
```
Selectors inside cross-origin iframes (7Movies, Moonflix) apply to the injected copy running *in that frame*
(`depth` ≥ 1; the scripts already run in frames via document-start injection with `allFrames`). Verify the injected
script actually runs inside each embed frame (CDP target list or a `postMessage` ping); if not, that is the first bug.

### 4.3 Menu engine (`packages/inject/tvmenu.js`, new)
1. On menu open (detected by `player.menu`): enter **menu mode**: candidates = only visible items inside the menu
   (respect `controlVisible`, inherited `opacity<0.1`, `pointer-events:none` on any ancestor up to the menu,
   `hidden` class, off-screen rects). Initial focus = the currently selected item (`aria-checked`, `aria-selected`,
   `.active`, a check-mark icon, or Moonflix's highlighted `li`), else the first item.
2. UP/DOWN = previous/next item in the same column (DOM order, not geometry, for lists); at the end of a column move
   to the footer row if any (Movy `Customize appearance`, 7Movies drill rows, Moonflix Cancel/Apply).
3. LEFT/RIGHT = jump to the column on that side, to the item at the closest vertical position (or its selected item).
4. Each move calls `item.scrollIntoView({block:'nearest'})` so the inner scroll list follows. No page scrolling.
5. OK on an item: click it. For subtitle/audio choices, **after selection close the menu automatically after
   250 ms** (Netflix behaviour) — except drill rows and accordions (Cinejoy language groups like `English 73` open a
   sub-list: stay in the menu and focus its first child). Moonflix: click the `li`, then press `player.menuConfirm`
   (Apply) automatically.
6. Text and file inputs inside menus are never focused (skip). If the site's only way to reach a track is via search,
   expose the track list instead (Cinejoy's accordion has every language).
7. Close (BACK, or auto-close): by `player.menuClose` mode — Escape on `document` + `activeElement`, else click the
   toggle that opened it (remember it on open), else click the close selector. Confirm closure after 300 ms; if still
   open, try the next mode. Then focus returns to the toggle and the 5 s fade restarts.
8. While a menu is open, keep controls held (dispatch the same wake as `wakeControls` every 2 s) so the bar doesn't
   fade out under the panel.
9. Unit-test the pure parts in `tvnav-pure.js` (or a new `tvmenu-pure.js`): column selection, next/prev index with
   footer, closest-item-by-y, close-mode fallback order.

### 4.4 Bars and fades
- Same 5 s idle fade on all four. Movy already fades (`movy-pctl-off`). Add per-site `player.hide` selectors for
  Cinejoy (bottom bar `absolute bottom-0…`, top bar), 7Movies embed (top `lb-*`, bottom bar), Moonflix
  (`#controls-bar`, top title/back). Keep the site's own auto-hide working; ours just enforces the 5 s and never
  hides while a menu is open.
- Keep Movy's `zoom: fsWidth/normalWidth` fullscreen fix; verify it also applies inside 7Movies/Moonflix frames when
  the outer iframe is fullscreened (it is relayed today — measure the bar height before/after fullscreen).

### 4.5 Subtitles stay visible
- When bars are hidden, Cinejoy's `.lp-subtitle` container and 7Movies' cue box must drop to `bottom: 6%`; when bars
  are visible, lift above the bar. Native cues (Moonflix, Movy if text tracks are used) need `video::cue` sizing only.
- Don't force a subtitle language; respect the site's saved choice.

### 4.6 First OK must work (popunders)
- On TV the first activation on each site triggers a popunder `window.open` and the site's own click is cancelled.
  Make `navguard.js`/`scamguard.js` (read them first) neutralise this generically: when `window.open` targets a host
  outside the source's allowed hosts, return a dummy window object (`{closed:false, close(){}, focus(){}, location:{}}`)
  instead of `null`, and never let the opener's `preventDefault` path cost the user's click. Verify per site that the
  first OK on a card / Play opens the page on the first press.

### Gate C — test matrix (emulator, then the Bravia)
For each site, record pass/fail in REPORT.md:
1. Open player → controls show → 5 s later only video + subtitles.
2. UP reaches top bar; DOWN reaches bottom bar; BACK releases; BACK again exits the player.
3. Open Audio & Subtitles: focus lands on the selected item; UP/DOWN scrolls the inner list; LEFT/RIGHT switches
   Audio ↔ Subtitles; pick English → menu closes itself and English cues appear; BACK in an open menu closes it and
   focus is on the toggle (never exits the player).
4. Quality/Server/Speed menus: same navigation; choosing a server keeps playback in fullscreen.
5. No text input or file picker ever gets focus.
6. Cinejoy: use a title that plays on desktop (e.g. TMDB id 550 at `/watch/movie/550`). If streams fail on the
   emulator, finish Cinejoy on the Bravia.

---

## 5. Phase D — Island and leftovers
1. Island LEFT/RIGHT (IslandView.java) — test it now: open with `KEYCODE_MENU`, move across tiles, OK switches.
2. Moonflix recents: record the modal's title/id when the detail modal opens (title from the modal heading, id from
   the Play target or poster path) so "Recently viewed" works there too.
3. Drawer close → restore focus to the card that was focused before LEFT (store it on open).

---

## 6. Phase E — Docs, real device, report
1. Update `docs/site-dossiers/*.md` with the §1.3 findings (no URLs).
2. `REPORT.md` → "Round 4": what changed, timings table (cold launch, second launch, source switch before/after),
   the Gate C matrix, known issues.
3. Install the TV debug APK on the Bravia (`192.168.29.104:5555`, ARMv7) only after Gate C passes on the emulator;
   take `screencap` screenshots of each site home and one player with the subtitle menu open.
4. Update the project memory note `movy-tv-testing` only if the harness changed.

---

## 7. Harness reminders
- Build+install: `powershell -NoProfile -File scripts/tv-test/b.ps1` (~2 min; copies `packages/inject/*.js` into
  assets — **add `tvmenu.js` to whatever list it copies and to `CosmeticBridge` injection**).
- Drive: `bash scripts/tv-test/t.sh open <movy|cinejoy|7movies|moonflix> [secs]`, `t.sh key UP DOWN OK BACK KEYCODE_MENU`,
  `t.sh shot <name>`. Page JS: `node scripts/tv-test/cdp.mjs '<js>'` (first page target — after preload changes make
  sure it is not the hidden WebView; prefer filtering targets by visibility/URL in `cdp.mjs`).
- adb: `/c/Users/ak818/AppData/Local/Android/Sdk/platform-tools/adb.exe`; emulator `emulator-5554`; package
  `com.ayushkumar.movy.tv`.
- Layout: page lays out at 1280×720 CSS px; real fullscreen window 960×540 at dpr 2. Scripts inject at document start
  (`addDocumentStartJavaScript`), but don't rely on `load`.
- Tests: `npm test` (inject package) and `./gradlew testTvDebugUnitTest` (or the project's unit-test task).
