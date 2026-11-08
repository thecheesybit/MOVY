# Movy — multi-target web shell with Brave adblock

One config → three apps that load a website full-screen, with ads blocked by
Brave's [`adblock-rust`](https://github.com/brave/adblock-rust) engine.

| Target  | Tech                        | Output                       |
|---------|-----------------------------|------------------------------|
| phone   | Capacitor 7 Android (flavor `phone`) | `dist/<app>/<app>-phone.apk` |
| tv      | Capacitor 7 Android (flavor `tv`, Leanback) | `dist/<app>/<app>-tv.apk` |
| desktop | Electron                    | `dist/<app>/desktop/…` (NSIS installer + unpacked) |

Reference device: OnePlus Pad 3 (OPD2415), Android 16 / API 36, arm64-v8a,
Chrome WebView 156. Connected over wireless ADB at `192.168.29.236:36091`.

## Repository layout

```
config/
  apps/example.json        # template; user adds their own apps/<name>.json (git-ignored except example)
  assets/<name>/           # optional icon.png (1024²), tv-banner.png (320×180)
  schema.json              # JSON Schema for editor autocomplete
packages/config/           # Node: load + validate (zod) + merge target overrides
native/adblock/            # Rust crate: adblock-rust wrapper
  src/lib.rs               # core API (pure Rust, unit tested)
  src/jni.rs               # JNI exports for Android  (feature "jni")
  src/bin/build-engine.rs  # host CLI: filter lists -> serialized engine .dat
apps/mobile/               # Capacitor project (android/ with phone + tv flavors)
apps/desktop/              # Electron app
scripts/                   # build orchestration (node)
dist/                      # build outputs (git-ignored)
```

## App config (`config/apps/<name>.json`)

```jsonc
{
  "name": "example",                 // folder/file key, [a-z0-9-]
  "appName": "Movy",                 // launcher label / window title
  "appId": "com.example.movy",       // Android applicationId, Electron appId
  "url": "https://source-1.example",          // start URL
  "version": "1.0.0",
  "versionCode": 1,
  "allowedHosts": ["source-1.example", "*.source-1.example"], // top-level navigation allowed in-app
  "externalLinks": "block",          // "block" | "in-app" (never launches other apps; "open-external" = "block")
  "userAgent": null,                 // null = platform default with WebView markers removed
  "backgroundColor": "#000000",
  "orientation": "any",              // "any" | "portrait" | "landscape"
  "adblock": {
    "enabled": true,
    "lists": "default",              // "default" or array of filter list URLs
    "extraLists": [],                // appended to lists
    "customRules": [],               // inline ABP/uBO rules
    "cosmetic": true,                // element hiding + scriptlets
    "blockPopups": true              // window.open / target=_blank to non-allowed hosts
  },
  "targets": {                       // shallow per-target overrides of any key above
    "phone": {}, "tv": { "orientation": "landscape" }, "desktop": {}
  }
}
```

Default lists: EasyList, EasyPrivacy, uBlock filters, uBlock badware, uBlock
privacy, uBlock unbreak, uBlock quick-fixes, Peter Lowe's. Scriptlet/redirect
resources: `https://raw.githubusercontent.com/brave/adblock-resources/master/dist/resources.json`.

## Build flow

`npm run build -- --app <name> --target phone|tv|desktop|all`

1. `packages/config` loads + validates, merges `targets.<t>` overrides.
2. `scripts/filters.mjs` downloads lists (cache in `.cache/filters`, 24h TTL,
   falls back to cache when offline) and runs `build-engine` →
   `engine.dat` (serialized `adblock::Engine`) + `resources.json`.
3. Target build:
   - Android: generates `capacitor.config.json` + `android/app/movy.gradle.json`
     (appId, label, versionCode…), copies `engine.dat` into assets, icons,
     runs `gradlew assemble<Phone|Tv>Release` (debug-signed unless
     `config/signing.json` exists), copies APK into `dist/`.
   - Desktop: writes `app-config.json` + `engine.dat` into the Electron
     resources, runs electron-builder.

## Adblock engine contract

Rust core (`native/adblock/src/lib.rs`), engine with `Engine::deserialize`
for fast startup; built with the **same crate version** on host and device.

```rust
pub struct Shell { engine: adblock::Engine }          // wrap in Mutex/RwLock if !Sync
impl Shell {
  fn from_serialized(bytes: &[u8]) -> Result<Self>;
  fn from_rules(rules: &str, resources_json: Option<&str>) -> Self;
  fn check(&self, url: &str, source_url: &str, request_type: &str) -> CheckResult; // matched, important, redirect, exception
  fn url_cosmetic_resources(&self, url: &str) -> String;           // JSON
  fn hidden_class_id_selectors(&self, classes: &[String], ids: &[String], exceptions: &[String]) -> String; // JSON array
}
```

Android Kotlin facade `app.movy.adblock.AdblockEngine` (package fixed, independent of appId):

```kotlin
object AdblockEngine {
  fun load(context: Context)                     // reads assets/adblock/engine.dat (+resources) off main thread
  val isReady: Boolean
  fun check(url: String, sourceUrl: String, type: String): BlockResult
  fun cosmeticResources(url: String): String     // JSON
  fun hiddenSelectors(classes: List<String>, ids: List<String>, exceptions: List<String>): String
}
```

JNI symbols: `Java_app_movy_adblock_NativeBridge_*`, handles passed as `long`.
Native lib name: `libmovy_adblock.so`, ABIs arm64-v8a, armeabi-v7a, x86_64.

## Android integration

- `MainActivity` (Capacitor `BridgeActivity`) installs `AdblockWebViewClient
  extends BridgeWebViewClient`:
  - `shouldInterceptRequest`: infer request type (main frame → `document`,
    Accept header / extension → `script|stylesheet|image|media|font|xmlhttprequest|other`),
    `sourceUrl` = current top-level URL; blocked → empty 200/204 response
    (redirect resource → serve its body).
  - `shouldOverrideUrlLoading`: non-allowed host → per `externalLinks`; known ad
    host (check as `document`) → swallow.
- Cosmetic filtering: `WebViewCompat.addDocumentStartJavaScript` injects
  `assets/adblock/cosmetic.js`, which calls a `@JavascriptInterface`
  (`MovyAdblock`) for per-URL resources, injects hide CSS + scriptlets at
  document start, and runs a throttled `MutationObserver` for generic
  class/id hiding.
- Popups: `WebChromeClient.onCreateWindow` → allow only allowed hosts.
- "Like Chrome": JS, DOM storage, third-party cookies, autoplay without
  gesture, file downloads
  (`DownloadManager`), file chooser, back button → `goBack()`, WebView UA with
  `; wv` and `Version/x.y` removed, hardware acceleration, edge-to-edge.
- Loading UX: `getDefaultVideoPoster()` returns a transparent bitmap (no grey
  play-icon flash); injected `loading.js` shows a red spinner over buffering
  `<video>` elements (also inside fullscreen); native 3dp progress bar during
  page loads; WebView background = `backgroundColor`; branded splash until the
  first page loads. Desktop reuses `loading.js` via its preload.
- System bars (phone): status bar + nav bar visible by default, content laid
  out below them via window insets (edge-to-edge is forced on API 35+), bar
  icons light/dark from `backgroundColor`. When the site enters fullscreen
  (Fullscreen API → `onShowCustomView`) the app goes immersive (bars hidden,
  cutout used, screen kept on, landscape allowed); leaving fullscreen
  (`onHideCustomView` or Back) restores everything. Back while fullscreen
  only exits fullscreen.
- `tv` flavor: always immersive, `LEANBACK_LAUNCHER` intent, `android.software.leanback`
  (required=false) and touchscreen not required, banner, landscape, D-pad
  focus visible, back = history back, long-press back = exit.

## Desktop integration

- Electron `session.webRequest.onBeforeRequest` → engine check with Electron
  `resourceType` mapped to adblock types; cancel or redirect (data: URL).
- Preload script performs the same cosmetic filtering via IPC.
- Engine access: Brave's official Node bindings (`adblock-rs`) if they install
  cleanly with prebuilt/locally built binaries on Windows, otherwise a napi-rs
  binding over `native/adblock`. The engine must deserialize the same `engine.dat`
  format or be rebuilt from the cached lists at build time.
- Single window, no menu bar (Alt shows it), remembers size/position,
  external links per `externalLinks`, F11 fullscreen, context menu.

## Work packages (each done by one worker agent, sequentially)

1. **W1 – Rust adblock core**: crate, unit tests (block/allow/cosmetic/
   serialize round-trip), JNI layer, `build-engine` CLI, cargo-ndk build
   script producing `.so` for 3 ABIs.
2. **W2 – Config + build scripts + Capacitor Android (phone + tv)**: config
   package with tests, filters script, Capacitor project, Kotlin integration,
   APK build, install on the tablet.
3. **W3 – Electron desktop**: app, adblock integration, electron-builder.
4. **Orchestrator**: review, device verification, docs.

## Definition of done

- `npm run build -- --app example --target all` produces phone APK, tv APK and
  desktop build without manual steps.
- On the tablet the phone APK opens the configured site; ad requests from the
  default lists are blocked (verified via logcat counter and a test page such
  as `https://d3ward.github.io/toolz/adblock`).
- Rust and config unit tests pass.

## Rotation and fullscreen video (W8)

- Orientation rule (`OrientationPolicy`, decided at runtime from `smallestScreenWidthDp`, not by flavor):
  - Phones (< 600dp): portrait in the app (device rotation lock and auto-rotate are ignored); fullscreen video switches to
    `SENSOR_LANDSCAPE` (both landscape sides, even with rotation locked); exiting fullscreen returns to portrait. The site's
    own `screen.orientation.lock` is ignored.
  - Tablets (>= 600dp): follow the device's rotation setting (`UNSPECIFIED`; `FULL_USER` in fullscreen, which respects the lock). Nothing forced.
  - TV: manifest orientation (`sensorLandscape`); the policy is not applied.
- Root cause of the black video on rotation: after the activity rotated (we handle `orientation|screenSize` in
  `configChanges`, so nothing recreates it) the WebView's fullscreen video view kept its portrait layout. SurfaceFlinger showed
  the video overlay layer still placed for a 1440x3168 portrait fullscreen (letterboxed at y=1179, scale 0.75) while the
  display was 3168x1440, so it sat off-screen behind our black container and only the audio was visible.
- Fix: `MainActivity.onConfigurationChanged` -> `ShellWebChromeClient.onConfigurationChanged()` re-lays out the custom
  container and video view (immediately, +150 ms, +500 ms) and re-attaches the video view if its size still differs from
  the container (`FullscreenPolicy.needsReattach`). While fullscreen the page view is INVISIBLE and the loading bar is
  suppressed so nothing else draws over or competes for the video surface.

## Android TV remote and 10-foot UI (W9)

- Keys (`TvController` + pure `TvKeys`), routed in `MainActivity.dispatchKeyEvent` before the WebView sees them:
  - Island toggle: MENU(82), INFO(165), GUIDE(172), BOOKMARK(174), SETTINGS(176), MEDIA_TOP_MENU(226), TV_DATA_SERVICE(230),
    TV_TELETEXT(233), TV_CONTENTS_MENU(256), TV_MEDIA_CONTEXT_MENU(257); fallback: long press of D-pad CENTER (CENTER is held back
    until release so a short press is replayed to the page, a long press opens the Island without a click).
  - Colour keys RED/GREEN/YELLOW/BLUE (183-186) switch to sources 1-4; the Island tiles carry matching coloured dots (TV only).
  - Transport keys (85/126/127/89/90) call `window.__movyTv.media(...)` in the page (play/pause, seek +-10 s, relayed into iframes).
  - Long press of BACK toggles pointer mode (`TvCursor`, `CursorMath`): native arrow, accelerating, edge scroll, CENTER clicks via a
    mouse MotionEvent. BACK order: pointer mode, Settings sub-page/alert, Island, fullscreen, history, "Press back again to exit".
  - Debug builds log every key as `MovyKeys` (`adb logcat -s MovyKeys`: keycode, scancode, source, device).
- Web navigation: `packages/inject/tvnav.js` (tv flavor only): spatial D-pad navigation (direction cone scoring, unit tested),
  cursor:pointer "cards" are focusable, focus ring that follows real focus, CENTER clicks, text fields keep caret keys, fullscreen
  video: CENTER play/pause, LEFT/RIGHT seek 10 s with an in-page bubble (a native overlay would compete with the video surface),
  UP/DOWN wake the controls; a big player iframe is entered with CENTER and left with UP.
- Native screens: `TvChooserLayout` (row of cards, options row), `TvFocus` (1.07 spring scale + 3dp ring), `TvFocusTrap` (arrows stay
  in the open panel, exactly one focused element, focus survives rebuilds), Settings side panel (all rows focusable, LEFT on a
  sub-page goes back, destructive alert defaults to Cancel), Island scaled by `TvLayout.islandScale` so 4 tiles + gear fit,
  `TvHint` ("MENU . Sources" chip 3 s after a home page loads, one-time toast). `Ui.textScale` is 1.25 (1.1 below 800dp wide).
  Sizing lives in `TvLayout` (tested at 640x360, 960x540, 1280x720 dp).
- Scam overlays: `packages/inject/scamguard.js` (all flavors, top frame) removes full-screen fixed blank/srcdoc-sandbox iframes or
  containers at an extreme z-index (the "Confirm you're not a robot" QR modal sets `display:block !important` inline, so CSS
  cannot hide it); real players (http src, `<video>`, normal z-index) are never touched. Host rules for the chain were added to
  `adblock.customRules`; the domains rotate, the structural guard is the real defence.

### W10 addendum: Sony remote reachability

- Measured on the Sony BRAVIA remote: only D-pad, CENTER, BACK, play/pause and CHANNEL +/- reach apps; MENU, colour keys,
  settings and guide are consumed by the system. The visible hint therefore says "Hold OK or press ▶ at the edge".
- `LongPressGate` (pure state machine): after a CENTER hold opens the Island, all further CENTER repeats and the UP are swallowed
  until the next fresh DOWN, so the Island no longer selects or closes itself.
- D-pad RIGHT with no focus target to the right (`tvnav.js` -> `MovyTvNative.edgeRight()`) opens the Island; LEFT or BACK closes it,
  RIGHT inside it is a no-op. CHANNEL_UP/DOWN step to the next/previous source and toast its name.

## W11 QA pass (phone and tablet)

- Bug: the Island disappeared on a source that redirects its start page (e.g. Moonflix) after you switched away and back.
  Cause: the landing page was only learned when the source's last URL was its start URL, and was reset on every switch.
  Fix: `IslandController` keeps the learned landing page per source for the process and restores it on switch.
- Cinejoy: some titles return the site's own "Server error" in the player; others play fine with ad blocking on (site-side).

## Island attention cue (phone/tablet)

On touch devices the collapsed Island capsule is red (#E50914) and blends to black as it expands. Every 10 s it gives a short, decaying sideways wiggle (3.5 dp, 0.5 s), skipped while expanded, hidden or touched. There is no haptic buzz. TV is unchanged. The constants are in UiTokens (ISLAND_IDLE, NUDGE_*); the tests are in IslandNudgeTest.


## W13: TV engine (TV-BUILD sections 1 to 6, plus "Lean by design")

All of it is TV flavor only. The phone/tablet flavor ships no TV scripts, an empty `tvProfiles` and the cache off.

- **Profiles in config (§1).**
  - `tvProfiles` (zod, `.strict()`, `packages/config/src/tvprofile.mjs`) is allowed in the app config and in `targets.<t>`; `cache {enabled,maxMb,maxAgeDays}` is new too (default on for tv only).
  - `buildDefaults` ships the profiles in `movy-defaults.json` (tv only); `LiveConfig`/`TvProfiles` merge a Remote Config `tvProfiles` per source, the remote winning per key; unknown keys are dropped, known keys are validated.
  - The page asks `MovyTvNative.profile(topOrigin)`; a config update calls `__movyTv.reloadProfile()` and the profile is relayed into child frames.
  - Real selectors live in git-ignored `config/apps/movy.json`; `example.json` holds a placeholder.
- **Layout width (§2).** TV uses `setUseWideViewPort(true)` and a forced `<meta viewport width=profile.viewportWidth>` (default 1280, all viewport metas rewritten, bounded and debounced: Next.js re-asserts its own and made a mutation ping-pong that froze the page). Decision: keep **1280**. At 960 CSS px Cinejoy renders its mobile layout (audit); at 1280 it gets the desktop layout (top nav, no bottom pill, row headings), and text stays readable (captions about 18 px physical at 1080p, body 24 px).
- **Navigation engine (§3).** `tvnav-pure.js` (pure, unit tested) and `tvnav.js`.
  - The page is a list of lines: rows (profile `rows` plus auto-detected horizontal scrollers) and free lines; fixed headers sort above the page and fixed bottom bars below it.
  - LEFT/RIGHT walk a row and scroll it so the card is fully visible with a peek; UP/DOWN pick the nearest card of the next line keeping the column.
  - Candidates are cached and rebuilt on a throttled MutationObserver and on scroll; a key press only measures rects.
  - `skip` matches the element itself (list descendants explicitly) and a profile card always wins. Not focusable: unrendered, closed `<details>`, aria-hidden, scroll holders, page-sized wrappers.
  - First focus is `firstFocus`, else the first card (never the header). It upgrades to the hero Play once it appears, survives hero re-renders (`repairFocus`), and a modal layer gets its own first focus.
  - Frames use `__movyTvExit` and `__movyTvEnter`. Focus ring: `scale:1.08` (the CSS `scale` property, so site transforms stay) with a 150 ms ease-out. Range inputs no longer trap LEFT/RIGHT.
  - `__movyTv.timings` records sync, input latency and key-to-paint.
- **Idle, immersive, first-key (§4).**
  - `IdleGate` (pure) and `TvIdle`: a 5 s timer; on idle the Island, Settings, hint and cursor hide and `html.movy-idle` hides header, bottom bar, player controls, the ring and the cursor in all frames. The first key only wakes and is swallowed together with its repeats and key-up (play/pause acts at once).
  - `TvImmersive` re-applies on resume, focus, insets (bars, IME), and fullscreen exit (the old `show(systemBars)` is skipped on TV).
- **Fullscreen policy (§5).** `tvfs.js`.
  - OK on a `playButtons` element, or the first `playing` after a key, enters fullscreen on `player.fullscreenTarget` (child frames ask the parent).
  - No gesture after a route change: a real F1 key injected by native grants one (Shift and other modifiers do not).
  - A fullscreen element lost to the site (server switch, autonext) is re-entered. BACK in fullscreen (the WebView itself handles it) tells the page through a synchronous `MovyTvNative.userLeftFullscreen()`, so it stays out.
  - The site fullscreen buttons and `overlayKill` are hidden with CSS; `killOnTv` neutralises trailers.
- **Smart cache (§6).**
  - `CachePolicy`, `CacheIndex` and `AssetCache` (all Android-free, JVM tested) plus `AssetCacheInterceptor` in `shouldInterceptRequest` after adblock and DoH.
  - Images, fonts and versioned JS/CSS only (GET, 200, no Range/Authorization/signed query, no no-store/private). Never media, JSON or HTML. Set-Cookie is only tolerated on immutable static files and never re-emitted.
  - Served from disk at once; 24 h background revalidation (304 refreshes, 200 replaces, 404/410 evicts); 30 days after first store it is deleted; sweep at start and daily; 300 MB LRU; atomic writes; CORS headers kept (an Origin is echoed when the stored copy had none).
  - Settings shows "Image cache" and Clear Cache clears it.
- **Lean by design.**
  - `TvLifecycle` and `LeanPolicy`. Cold start trims the WebView HTTP cache (`cache/WebView/Default/HTTP Cache`) over 100 MB before the WebView exists; the same trim runs on background. Cookies, localStorage and IndexedDB are never touched. Downloads are disabled on TV.
  - `onStop`: media paused in all frames, `onPause`+`pauseTimers`, RAM cache cleared, idle timer/cursor/hint stopped, cookies flushed, AssetCache index dropped.
  - After 60 s in the background the WebView is destroyed, the last URL is saved (reopened on the next launch) and the process ends. BACK-exit ends the process. `onTrimMemory` is handled.
- **Hooks for W14.**
  - JS: `__movyTv.media(action,secs)`, `__movyTv.mediaStatus()` / `lastStatus` (also pushed to `MovyTvNative.onMedia(json)`), `__movyTv.enterFullscreen()`, `userExitFs()`, `wantsFullscreen()`, `idle(bool)`, `isIdle()`, `PF` (compiled profile), `debug()`, `timings`.
  - Profile fields `servers`, `episodes`, `player.controls/video/root` are parsed and shipped but not yet used by any native UI.
  - Native: `TvBridge` (add methods there), `TvIdle.touch()` for native UI that should not idle, `injectGestureKey()`.
- **Tests.**
  - `packages/config` 24; `packages/inject` 21; desktop 41.
  - JVM 137 per flavor (new: IdleGate, CachePolicy, CacheIndex, AssetCache, TvProfiles, LeanPolicy).
- **Build notes.** Debug iterations used versionCode 70; the final release TV build is 81.
