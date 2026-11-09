# Movy TV — Round 5: the revamp (navigation, loading, drawer, search, player, system)

Written 2026-11-10. Builds on Round 4 (`ROUND4-PLAN.md`, results in `REPORT.md`). **No source URLs anywhere** (files,
commits, logs, UI): sites are Movy, Cinejoy, 7Movies, Moonflix. Read §0–§2 before any level; then only your level.

---

## 0. The product we are building

Movy TV takes four streaming *websites we do not control* and makes them feel like one native Google TV app driven
by the Sony remote (D-pad, OK, BACK, MENU, mic, VOL±, CH±, play/pause). We can only add things around and on top of
the sites (native Android views, injected JS/CSS, request interception). We never edit the sites.

The principle for this round: **the native shell owns the chrome, the web owns the content.**
Everything that must look and behave the same on all four sites — loading, drawer, source switching, search entry,
keyboard, voice, dialogs, toasts, brightness, exit — is drawn by Android views in `MainActivity`'s layout, above the
WebView. Injected JS only does what needs the page: spatial navigation, reading/clicking site controls, reporting
state (`MovyTv` bridge).

### Research summary (what "complete" means for a TV streaming app)
- Google's TV quality checklist: every focusable element reachable by D-pad, predictable focus movement, BACK always
  goes to the previous view and never loops, OK = play/pause, LEFT/RIGHT = seek during playback, no on-screen back
  buttons needed. (Google says apps should exit without confirmation; the user explicitly wants a confirm, so we do a
  one-step confirm where a second BACK exits — no trap.) <https://developer.android.com/docs/quality-guidelines/tv-app-quality>,
  <https://developer.android.com/design/ui/tv/guides/foundations/navigation-on-tv>
- Home-screen "Continue watching": Google is moving to the Engage SDK (needs Play distribution / allow-listing); the
  older `androidx.tvprovider` Watch Next API still works until its 2027 end of support. Movy is sideloaded, so we use
  Watch Next and verify on the device; the in-app Continue watching row is the guaranteed path.
  <https://developer.android.com/guide/playcore/engage/tv/continue-watching>, <https://github.com/android/tv-samples/pull/150>
- Voice: the remote's mic/Assistant key is usually taken by the system; on TV `KEYCODE_SEARCH` may be delivered for
  text search. In-app voice = our own mic button that launches `RecognizerIntent.ACTION_RECOGNIZE_SPEECH` (check it
  resolves first; hide the mic if not). Keep a text path always.
  <https://android.googlesource.com/platform/frameworks/base/+/15cac2637e10>
- Text entry on TV: a focused native `EditText` + `showSoftInput` brings up the TV (Gboard/Leanback) keyboard
  reliably; a focused WebView `<input>` traps LEFT/RIGHT and is inconsistent. So all typing happens in native views.

---

## 1. Current state (audited 2026-11-10, HEAD `8105c36`)

| Area | Where | State |
|---|---|---|
| Key routing | `TvController`, `TvKeys`, `TvCursor`, `TvFocusTrap` | BACK/OK/MENU/media keys mapped; **CH± switches source**; VOL± passed through (first VOL+ unmutes muted site video). |
| Spatial nav | `packages/inject/tvnav.js` (1668 lines!), `tvnav-pure.js` | Works on all four; too big; re-scans DOM; rail drawer lives here. |
| Drawer | JS "left rail" in `tvnav.js` (`SOURCE_THEMES`, `renderRail/openRail`) + native `IslandView` (MENU) | Two separate source switchers; per-site themes hard-coded in JS; drawer rebuilt on every page; sources at the bottom of the drawer. |
| Search | `openSiteSearch()` in `tvnav.js` | Clicks the site's search button and focuses the site `<input>` → WebView IME, no mic, no history, results flow differs per site. |
| Recents | `movy_tv_recent` localStorage, per origin | 3 shown in the drawer; nothing on the Android home screen. |
| Loading | native `LoadingUi`/`SpinnerView` + `loading.js` + each site's own spinner | Several spinners, not aligned with the player's spinner. |
| Player | `tvfs.js` (797), `tvmenu.js` (337), profiles `player.*` | One key model on all four; site menus reachable; no unified settings; subtitle size only via site UIs; resize jump on fullscreen (1280×720 layout → 960×540 fullscreen). |
| Speed | `AssetCache`, `SourcePreloader`, `LeanPolicy`, shell snapshots | Round 4 numbers: cold Movy 5.1 s first poster; switch to Cinejoy 10–21 s; Movy switch sometimes 12 s. Still laggy. |
| Lifecycle | `TvLifecycle.exitApp`, `trimColdStart` | BACK at root exits straight away; caches trimmed by size. |

---

## 2. Ground rules for every agent

- Sonnet-level agents, one level at a time. **Only one agent may drive the emulator at once.** A second, parallel
  agent may only do read-only work or pure code + unit tests that do not deploy.
- Harness: build+install `powershell -NoProfile -File scripts/tv-test/b.ps1`; drive `scripts/tv-test/t.sh`
  (`open <site>`, `key …`, `shot <name>`); eval `node scripts/tv-test/cdp.mjs '<js>'`; timings
  `scripts/tv-test/paint-wait.mjs`. Emulator `emulator-5554`, package `com.ayushkumar.movy.tv`. adb lives in the
  Android SDK `platform-tools` (not on the bash PATH; the scripts find it).
- Profile-driven: site specifics go in `config/apps/movy.json` → `tvProfiles.<site>` (git-ignored real config) and are
  mirrored in `config/schema.json`, `TvProfiles.java`, `TvProfilesTest`. No `if (host === …)` branches.
- Files under ~800 lines. `tvnav.js` must shrink, not grow: new JS goes in new modules registered in
  `CosmeticBridge` and the `b.ps1` copy step.
- Every pure rule gets a unit test (`npm test`, Gradle `testTvDebugUnitTest`); both stay green.
- TV only: phone flavour and Electron behaviour unchanged.
- Lean: no movie media on disk, no second WebView during playback, RAM released on background.
- Native UI style: dark, minimal, one accent per source, 150–200 ms transform/opacity animations only (no blur,
  no backdrop-filter, no layout animation — the Bravia is ARMv7). Focus = scale 1.06 + accent ring.
- **Visual reference: JioHotstar TV** (user-supplied, `docs/design/ref-hotstar-*.{png,webp}`). Take the language, not
  the brand: near-black background (`#0f1014`), white bold for the focused/current item and soft grey for the rest,
  thin outline icons, a left icon rail that expands to labels over a **left-to-right dark gradient scrim** (no
  boxed panel), circular "profile" avatars centred on an empty dark screen for choosing, one gradient primary
  action. Never copy Hotstar's logo, names or artwork.
- **Default source is Cinejoy** (`recommendedSource: "cinejoy"` in `config/apps/movy.json`, set 2026-11-10).
- Do not commit or push; the orchestrator reviews and commits each level. Write results into `REPORT.md` under
  "Round 5 — Level N" with screenshots `docs/tv-adapter/screenshots/r5-L<N>-*`.

---

## 3. Levels

Each level ends with a gate. The next level starts only after the gate passes and the orchestrator commits.

### Level 0 — Baseline and site capability inventory (read-only)
Produce `docs/tv-adapter/R5-INVENTORY.md`:
1. **Timings** (5 runs each, median): cold launch → first poster; second launch; switch to each source; open a
   title → video `playing`; BACK from player → catalog interactive. Also RAM (`dumpsys meminfo`) after 10 min browse.
2. **Scripts per page type**: which inject files run on catalog, detail, player page, player iframe; their parse/exec
   time (`performance` marks or CDP `Profiler`); which site scripts are 3rd-party and not blocked.
3. **Player settings matrix** — for each site, every option the site player offers, how to reach it (selectors,
   open/close, apply semantics): quality, server, speed, audio track, subtitle track, subtitle size, colour,
   background, position/offset, sync, auto-next/skip intro, aspect/fit. Mark: exists / missing / broken on TV.
4. **Search per site**: search route and query param (e.g. a `/search?q=` style URL — record the pattern only as a
   path template, never the host), or the input selector + submit behaviour; how results render; whether results
   are reachable by D-pad.
5. **Drawer items per site**: Home, Movies, Shows, extra sections, My List, History/Continue watching, Settings,
   Account — route or action, signed-out behaviour.
6. **Resize bug repro**: record viewport/video rect over time from player open to 5 s after `playing` on each site.

Gate 0: inventory complete for all four sites; numbers recorded.

### Level 1 — Navigation and loading (the foundation)
**1A Loading speed**
1. Inject only what the page needs: `tvfs.js`/`tvmenu.js` only in documents that contain (or are) a player; split
   `tvnav.js` into `tvnav-core.js` (focus engine), `tvnav-rows.js` (rows/cards), drawer code removed in Level 2.
   Inject at document start where possible; no repeated full-DOM scans (use one MutationObserver + rAF batching).
2. Block remaining ad/analytics hosts found in L0 (engine rebuild via `scripts/tv-test/rebuild-engine.mjs`).
3. Pre-connect (DNS+TLS) to the other three sources' origins once idle after first paint (native, `DohProxy` keep-alive),
   not a hidden WebView.
4. Cinejoy switch (10–21 s): find the real cause from L0 (DoH, JS bundle, API calls) and fix it; target ≤ 5 s.
5. Images: keep posters via `AssetCache` hits; on miss let Chromium fetch (already async). Confirm no intercept-thread blocking.

**1B0 Start screen = sources as profiles** (replaces the TV chooser; ref `docs/design/ref-hotstar-profiles.png`)
- After the splash: dark screen, small Movy mark top-left, title "What are we watching?" centred, the **four sources
  as large circular avatars** in one centred row (source logo/initial on its accent colour, name below).
- Focus starts on the default (Cinejoy, or the last used source). A **progress ring draws around the focused
  avatar, one full circle** (~3 s). At the same moment the focused source starts loading in the (hidden) main
  WebView. When the ring completes **and** the page has painted, the screen fades out (200 ms) into the site. If the
  ring completes first, the ring turns into the same-style indeterminate spinner until the page paints (no second
  loading UI). If the page is ready first, the ring still finishes (fast-forwards over the last 300 ms).
- LEFT/RIGHT moves focus to another avatar: the ring and the load restart for that source. OK = choose now (ring
  fast-forwards). BACK = exit dialog (Level 5). A Settings gear sits top-right (UP to reach it).
- The same screen (no countdown) is what MENU/long-press shows from the drawer's "Switch source" when the user wants
  the big picker; the drawer's sources row (Level 2) is the quick path.

**1B One loading language**
- One native `LoadingUi` component (centred red arc spinner, optional one-line caption "Opening Cinejoy…") used for:
  app start (after splash), source switch, page navigation > 400 ms, and **player buffering** (video `waiting` →
  show after 300 ms, `playing` → hide), centred on the screen (the video is fullscreen).
- Hide every site spinner/skeleton that overlaps ours via profile `loading.hide` selectors (CSS `visibility:hidden`).
- Never two spinners at once; never a spinner over an interactive page for more than 300 ms after it is usable.

**1C Navigation correctness**
- BACK model (one table, all sites): player menu open → close menu; player → leave player to the detail/catalog
  page it came from; detail/modal → close it; any page → history back; root (home of current source) → exit dialog
  (Level 5). Never a loop, never a dead key.
- Focus restore: returning to a catalog restores the card that was focused (by URL + index), scrolled into view.
- Key repeat: holding a D-pad key scrolls smoothly (throttle to ~8 moves/s, accelerate after 1 s).

**1D No resize after load**
- Fix the 1280×720 → 960×540 re-layout on fullscreen: keep a fixed layout viewport (WebView initial scale + viewport
  meta override) so fullscreen does not re-layout; force `object-fit: contain` and full-size video; keep the player
  hidden behind the loading spinner until its rect is stable for 2 frames after `playing`, then fade in (150 ms).

Gate 1: start screen shows four avatars with Cinejoy focused, the ring completes once and opens the site, LEFT/RIGHT
restarts it on another source, OK skips the wait; L0 timings re-run, every number better (target: first poster cold ≤ 3.5 s, any switch ≤ 5 s, title→playing
≤ 6 s on emulator); no visible resize on any site (video rect constant from first visible frame); BACK table passes
on all four sites; one spinner only.

### Level 2 — Native drawer v2 and source switching
Replace the JS left rail **and** the TV Island with one native drawer (`TvDrawerView`, Android views over the WebView).
- Look (ref `docs/design/ref-hotstar-home.webp`, `ref-hotstar-drawer-open.png`): collapsed = outline icons only,
  vertically centred, current item white; open = labels slide in beside the icons over a full-height left gradient
  scrim (black 90 % → transparent at ~45 % width) that dims the page; current/focused label white bold, others grey.
  The sources row sits where Hotstar shows its logo (top-left); the account avatar sits last.
- **Collapsed**: slim 64 dp icon strip on the left (keeps the existing `tvinset.js` content inset), hidden in playback.
- **Open**: LEFT at the page's left edge → drawer opens with focus on the current section. **A second LEFT** (or MENU)
  moves focus to the **Sources row at the top: four source icons in one row**; LEFT/RIGHT picks a column, OK switches,
  DOWN returns to the list. RIGHT or BACK closes the drawer and restores web focus. No double-RIGHT anywhere; CH± no
  longer switches sources.
- **Order (same on every source, missing items hidden)**: Sources row · Search · Home · Movies · Shows · (site extra:
  Anime / New & Popular / Discover) · My List · History · Continue watching (up to 3, with title) · Settings (Movy
  settings sheet) · Account.
- Items come from profile `tvProfiles.<site>.drawer.items` (move `SOURCE_THEMES` out of `tvnav.js` into config);
  native calls `TV.drawerAction(id)` in the page for route/pick actions.
- Animation: open = translateX + fade 160 ms; source switch = drawer closes immediately, spinner shows, accent colour
  cross-fades. Nothing animates during page load.
- Focus handoff: JS reports "at left edge" through the bridge; native takes focus; on close native gives focus back and
  JS restores the previous element.

Gate 2: from any non-player page on all four sources: LEFT, LEFT, RIGHT×n, OK switches source; every drawer item works
signed-out; drawer opens < 100 ms after the key; no JS rail left in `tvnav.js`.

### Level 3 — Search (keyboard + voice) and Continue watching
**Search overlay (native)**
- Opened by drawer Search, `KEYCODE_SEARCH`, or OK on any site text search input (intercepted — the site input is never
  focused for typing).
- Layout: top field (native `EditText`, TV keyboard opens at once), **mic button** beside it (`RecognizerIntent`; hidden
  if nothing resolves it; `RECORD_AUDIO` only if we fall back to `SpeechRecognizer`), recent searches (last 8, per
  source, chips), suggestions = recent titles.
- Submit → profile `search`: `{ "route": "/search?q={q}" }` or `{ "input": sel, "submit": "enter" }` (from L0). The
  site results page loads; focus lands on the first result.
- Results loop: an injected **query pill** at the top of the results page shows "Search: <query>"; UP from the first
  row focuses it, OK reopens the overlay pre-filled; BACK from results returns to where search started.
- "Search all sources": a second row in the overlay to run the same query on another source (switch + search).

**Remote mic button = global search across all four sources** (ref `docs/design/ref-remote-mic-keys.png`: the remote
has a mic key or a Google Assistant key)
- Key capture: handle `KEYCODE_SEARCH` (84), `KEYCODE_VOICE_ASSIST` (231) and `KEYCODE_ASSIST` (219) in
  `dispatchKeyEvent`, and override `onSearchRequested()` (the TV framework routes the search key there when the app
  has focus). First task: log on the emulator and the Bravia which code(s) the mic key actually delivers to the app
  while it is in front; on firmware where the system Assistant swallows it, the drawer/search-screen mic is the path
  (document it, don't fight the system).
- Pressing it anywhere (catalog, drawer, not during playback — there it is ignored or pauses first) opens the
  **global search overlay directly in Listening state**.
- Results come from all four sources **without loading four WebViews**: per-source profile `search.api`
  (`{ "path": "...{q}...", "items": "<json path>", "title": "...", "poster": "...", "year": "...", "type": "...",
  "open": "/movie/{id}" }`) discovered in L0/L3 from each site's own search requests (most use a JSON search
  endpoint); fetched natively in parallel through the existing DoH/HTTP stack with the site's cookies, 6 s timeout
  each. A source without a usable endpoint shows a single "Search in <Source>" tile that opens its results page.
- **Overlay stages** (one native view, states change in place, 150 ms cross-fades):
  1. *Listening* — page dims (scrim 85 %), large accent mic circle centred with a soft pulse driven by the
     recogniser's RMS level, live partial text under it, hint "Say a movie or show". BACK cancels.
  2. *Searching* — the mic circle shrinks and moves into the top search bar (text = final query, mic button beside
     it); below it four rows, one per source (source avatar + name), each filled with 6 skeleton poster cards and a
     small ring while its request runs. Rows fill independently as answers arrive; failed row = "Couldn't reach
     <Source> · Retry".
  3. *Results* — focus lands on the first poster of the first non-empty row (row order: current source first, then
     by result count). Card = poster, title, year, type badge. LEFT/RIGHT within a row, UP/DOWN between rows, UP
     from the first row reaches the search bar (OK = type with keyboard, mic button = speak again). Mic key again =
     new voice query in place.
  4. *Action* — OK on a card: overlay keeps its state in memory and hides; if the card's source isn't current, the
     source switches (loading ring on the avatar style), then its detail path opens. BACK from that detail page
     (before navigating deeper) **returns to the results overlay as it was** (same row/card focused) — so the user
     can try another source's result again and again. BACK on the overlay closes it.
- Recent queries (8) are shown as chips in the empty state; typing uses the native field (TV keyboard).

**Continue watching**
- Record per watch: source, path, title, poster (og:image / player poster), position and duration (from the video,
  every 15 s and on pause/exit). Store natively (`SharedPreferences` JSON, max 20).
- Android home screen: publish to Watch Next (`androidx.tvprovider`, `WatchNextProgram`, type CONTINUE, deep link
  `movy://open?src=<key>&p=<path>`, last position) — handle the deep link in `MainActivity` (open source + path, resume
  position if the site allows `t=`/seek). Remove the entry when > 95 % watched. Verify on the emulator's Google TV
  launcher and the Bravia; if Google TV hides it, record that (in-app row still works).
- In-app: drawer "Continue watching" items and the first row of the drawer list.

Gate 3: the mic key's delivered keycode is documented for emulator and Bravia; global search shows the four stages
and results rows for every source with a search endpoint; OK → detail → BACK returns to the same results card 3
times in a row across different sources; type and voice search work on all four (voice: on a device with Google app); results reachable; re-search from
results works 3 times in a row; a watched title appears on the home screen (or the limitation is documented) and its
deep link resumes.

### Level 4 — Player: one simple settings panel
- **Movy player panel** (native overlay on the right, opened by UP→settings icon or MENU during playback): sections
  that exist on the current site, in a fixed order: Quality · Audio · Subtitles · Subtitle size · Speed · Server.
  Each choice is executed by driving the site's own menu via profile `player.settings.<name>` (open selector, item
  selector, apply/close semantics from L0) in the page or frame (`tvmenu.js` relay). Selected value read back and shown.
- **Movy-owned subtitle style** (works even where the site lacks it): size S/M/L/XL, background on/off, raised
  position — injected CSS for `::cue` and each site's cue element (profile `player.subtitles`), persisted globally.
- Cinejoy quality and every option in the L0 matrix marked "exists" must be reachable from the panel or the site menu.
- Site menus stay usable (existing Round 4 model) but the panel is the primary path.
- Controls: OK play/pause, LEFT/RIGHT seek ±10 s (hold = accelerate), UP top bar, DOWN bottom bar, play/pause key,
  FF/RW keys, BACK closes panel → releases controls → leaves player.

Gate 4: every option in the matrix works from the remote on every site where it exists; subtitle size changes live on
all four; no resize or jump when opening/closing the panel.

### Level 5 — System: exit, cache lifecycle, background warm-up, brightness, volume
- **Exit**: BACK at the root → native dialog "Exit Movy?" [Exit] [Stay], focus on Stay; **BACK again or OK on Exit
  exits**. MENU/HOME behave normally.
- **Cache policy (documented table in REPORT)**: kept across launches — AssetCache (posters, fonts, hashed JS/CSS,
  30 days, size cap), versioned site shells, cookies/localStorage (logins, site settings), Continue watching. Cleared
  on exit — RAM, WebView back/forward history, Chromium HTTP cache entries for media (never stored by us), HTTP cache
  trimmed to its cap (not wiped). Settings has "Clear cache" (assets + shells) and "Sign out of all sources".
- **Startup**: splash → current source only; other sources only pre-connected; nothing heavy in the first 10 s.
- **Warm-up during playback**: when a video has played ≥ 60 s, buffer ahead ≥ 30 s, and no `waiting` in the last
  30 s: low-priority native fetch (no WebView) of the other sources' shell HTML + hashed JS/CSS into AssetCache, one
  request at a time, paused instantly on `waiting`/seek, capped (e.g. 15 MB per session).
- **Brightness on CH±**: CH+ / CH− step app brightness in 10 % steps with a small native level toast. Implementation:
  `WindowManager.LayoutParams.screenBrightness` where honoured, plus a black dim overlay for below-normal levels;
  above-normal = light CSS `filter: brightness()` on the video only (measure CPU; disable if it drops frames).
  Persisted per app.
- **Volume**: VOL± go to the system (CEC to the TV); the first press unmutes a muted site video (keep).

Gate 5: exit flow; cache table verified by file listing before/after exit; warm-up does not cause a single `waiting`
event in a 10 min playback; second source switch after a movie ≤ 2 s; brightness steps visible and persisted.

### Level 6 — Full QA, real device, docs
- Full remote walk on all four sources (launch → browse → search → detail → play → every panel option → back → switch
  source → exit) on the emulator, then the Bravia (`192.168.29.104:5555`).
- 30-minute soak: RAM stable, no slowdown.
- Update dossiers, `TV-PLAN.md`, `REPORT.md`; known issues list.

---

## 4. Orchestration

| Step | Agent A (drives emulator / writes code) | Agent B (parallel, no emulator) |
|---|---|---|
| 1 | L0 inventory | — |
| 2 | L1 implementation | — |
| 3 | L2 native drawer | code review of L1 diff |
| 4 | L3 search + continue watching | code review of L2 diff |
| 5 | L4 player panel | code review of L3 diff |
| 6 | L5 system | code review of L4 diff |
| 7 | L6 QA | code review of L5 diff |

The orchestrator reviews each report, checks the diff, runs `npm test` + Gradle unit tests, commits, then starts the
next level. Max two agents at once; all agents Sonnet 5.5 medium.
