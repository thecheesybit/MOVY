# Anti-Gravity orchestration prompt: Movy TV adapter (map, drawer, smooth load, player UX)

Copy everything below the line into Anti-Gravity. The workspace must be opened at `D:\Movy`.
Claude (the reviewer) will inspect the result afterwards, see "REVIEW PROTOCOL" at the end.

---

## ROLE

You are the orchestrator for a multi-step engineering job on an existing Android TV app. Plan first, then split work across sub-agents where tasks are independent. Work in **small verified steps** (stage, test on the emulator, pass/fail gate, next stage). Never claim a stage is done without the evidence listed for its gate. If something fails or stays unverified, say so plainly in the report. If a site does not open, needs a login you cannot do, or a decision is the owner's to make, **stop and ask the owner** instead of guessing.

## GOAL

Movy wraps four streaming websites into ad-free apps: **7Movies (`7movies`), Movy (`movy`), Cinejoy (`cinejoy`), Moonflix (`moonflix`)**. YouTube-based sources are out of scope. We have **no access to the sites' source code**, only to what the browser shows. The Android **TV** flavor (Google TV / Android TV, remote control only) must feel like a proper TV app, and **the website experience must be delivered on the TV as it is on the web: same look, same speed, no glitches.**

The owner's complaints and requirements from this session (all must be met):

1. **Remote control reality**: only Up, Down, Left, Right, OK, Back are reliable. Volume and Channel up/down are optional extras. Everything must be fully usable with those six keys.
2. **Study before coding**: map every page's DOM (buttons, links, overlays, player) first, then design, then implement. DOM/inspection first; screenshots only when the DOM cannot answer.
3. **Always fullscreen** for playback, no leftover chrome.
4. **Smooth, glitch-free loading**: the owner sees the page zoom or resize 2 to 3 times while loading and between pages (layout width flips between 960 and 1280 CSS px). Either CSS or injection is late. Required: load first, pick the right size once, never visibly re-layout, never refresh or flash between pages.
5. **Size must not change when the video player starts** (the page reports 960x540 in fullscreen but 1280x720 outside; the player UI scale jumps). Decide and implement an invariant, then prove it.
6. **Per-site "Android drawer"** (like the Netflix / JioHotstar TV apps): a native right-side drawer per site with the site's navigation entries (Home, Movies, Shows, Anime, Search, Resume / Continue watching, My List, Sign in, whatever each site has). Selecting an entry opens that page or runs that action. This replaces walking the site's own header with the D-pad.
7. **Hide site clutter with element-hiding filters** (the same mechanism as Brave's "block element": cosmetic rules `site##selector`, which the app already supports through its Brave adblock engine): the site's logo, floating header pill, scroll-to-top button, notification regions, banners, anything useless on a TV.
8. **Video player UX must be natural and seamless** on every site: play/pause, seek, quality, servers, speed, audio, subtitles (including sub-menus and search / customize layouts), next episode, episode picker. Every control and every menu must be reachable and operable with the six keys, and Back must unwind one level at a time.
9. **Player chrome auto-hides after 5 seconds without a key: everything**, i.e. the top bar (back arrow, title, Quality, Audio & Subtitles), the centre transport buttons (rewind 10, pause, forward 10) and the bottom bar (seek bar, time). Only the video remains. Any key brings it back.
10. **Activity grid / focus map**: for every screen and state, an explicit, accurate map of what is pressable and where each arrow key goes ("when I press Up these three buttons in the same hierarchy are pressable; Down, these three; further Down, these"). No accidental jumps, no unreachable controls, no focus on invisible things.
11. **Focus visuals**: no ugly blue (or heavy white) outlines. Make the focus indicator accurate and tasteful (see Deliverable B). The site's own blue focus outline must not show.
12. **Remove the "Hold OK or press right at the edge" pop-up** (chip and first-run toast). Already switched off in code; keep it off, and make sure no other discoverability pop-up appears.
13. **Preload while the loading screen is up**: the shell preloads and applies the custom CSS and scripts during the splash so the page never looks half-styled.
14. **Cinejoy loads slowly** (about 100 TMDB API calls and about 374 images per home load): add proper caching.
15. **Test on the Google TV emulator**, not the owner's real TV. The real TV check is the last step, done by the owner.

## HARD RULES

1. **Source URLs never appear** in code comments, logs, docs, reports, UI or commits. They live only in `config/apps/movy.json` (git-ignored; start URLs are XOR-obfuscated into the APK). Refer to sources by id. Reading the config is fine; copying URLs elsewhere is not.
2. **Never sign in to a Google account** and do not automate any login. "Sign in" in a drawer only navigates to the site's own sign-in page for the owner to use.
3. **Do not commit, push or amend.** The repo already has about 170 uncommitted changes from earlier work; do not revert files you did not change. Leave everything uncommitted for the owner.
4. **Source of truth for page scripts is `packages/inject/`** (`tvnav.js`, `tvnav-pure.js`, `tvfs.js`, `loading.js`, `navguard.js`, `scamguard.js`, `cosmetic.js`). `apps/mobile/android/app/src/main/assets/shell/` is **generated and git-ignored**; `scripts/android/generate.mjs` copies from `packages/inject`. Edit `packages/inject`, then rebuild. Likewise `assets/movy-defaults.json` is generated from `config/apps/movy.json` (it needs the build's obfuscation key, so for quick profile experiments patch `tvProfiles` in both files with `scripts/tv-test/patch-profile.mjs`, and always keep `config/apps/movy.json` as the persistent copy).
5. Follow the repo rules: immutable style, small files (200 to 400 lines, 800 max), handle errors, no hardcoded secrets, existing unit tests must stay green, new logic that can be pure goes into a pure, unit-tested module (see `tvnav-pure.js`, `TvKeys.java`, `IdleGate.java` for the pattern).
6. Do not weaken ad / popup / scam blocking. Adblock engine, `navguard.js`, `scamguard.js`, `onCreateWindow` deny stay on.
7. Keep the phone flavor and desktop app working (do not break `npm test`); TV-only code must be gated to the TV flavor.
8. Models and effort: sub-agents should use Sonnet 5.5 at medium effort unless the owner says otherwise. If a task truly needs more, ask the owner first.

## ENVIRONMENT (Windows 11, bash and PowerShell both available)

- Build: `cd apps/mobile/android` then `./gradlew.bat assembleTvDebug -q` with `JAVA_HOME="C:\Program Files\Java\jdk-17"` **and** `ANDROID_HOME="C:\Users\ak818\AppData\Local\Android\Sdk"` (without ANDROID_HOME Gradle fails with "SDK location not found"). Stop the daemon when finished: `./gradlew.bat --stop`.
- APK: `apps/mobile/android/app/build/outputs/apk/tv/debug/app-tv-debug.apk`, package `com.ayushkumar.movy.tv`.
- Emulator: AVD `GoogleTV_1080p` (1920x1080, density 320, Android 14 Google TV, 4 cores, 3 GB). **`hw.keyboard = yes` in `C:\Users\ak818\.android\avd\GoogleTV_1080p.avd\config.ini`** is required: with `no` the PC keyboard is never forwarded and the remote "does nothing" (this was the owner's first problem). Start: `"%LOCALAPPDATA%\Android\Sdk\emulator\emulator.exe" -avd GoogleTV_1080p -no-snapshot-load`. adb has other devices attached (owner's phones, OnePlus pad): **always** use `adb -s emulator-5554`.
- The emulator decodes video in software, so audio/video jitter there is expected and is **not** an app problem. Judge playback smoothness only on the real TV. The app never transcodes or compresses media.
- Debug hooks (debug builds only, in `MainActivity.handleDebugIntent`): `am start -n com.ayushkumar.movy.tv/app.movy.shell.MainActivity --es movy_source <id>` opens a source start page; `--es movy_eval '<js>'` evaluates JS in the page and logs the JSON result to logcat tag `MovyDebug`; `movy_force_chooser`. Log tags: `MovyKeys` (every key), `MovyShell`, `MovyDebug`.
- Test scripts (copied to `scripts/tv-test/`, they hard-code `/d/Movy` and `emulator-5554`, fix as needed): `t.sh open|key|shot|eval`, `walk.sh KEY...` (prints the focused element after every key), `b.sh` (build and install), `patch-profile.mjs`.
- **Harness gotchas** (these cost the previous session hours):
  - The shell has a **5 s idle gate**: after 5 s without a key the first key only wakes the UI and is swallowed. Send a harmless wake key (`SHIFT_LEFT`) before the real key, or keep all keys within one call.
  - `input keyevent` names must be real: `DPAD_UP DPAD_DOWN DPAD_LEFT DPAD_RIGHT DPAD_CENTER BACK` (not `CENTER`, not `DPAD_BACK`; a wrong name silently sends keycode 0).
  - `movy_eval` goes through `am start` and costs about 2 to 3 s per call, longer than the idle gate and longer than site auto-hide timers. Prefer a single long-lived inspector: enable WebView remote debugging, `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` and drive it with the Chrome DevTools Protocol (DOM snapshot, `Runtime.evaluate`, `Page` events, `Input.dispatchKeyEvent`), or add an in-page recorder that logs every focus change and key to a ring buffer that you read once.
  - `adb exec-out screencap` can lag a frame behind; trust DOM state, not one screenshot.
  - Quote JS for `movy_eval` carefully (single quotes outside, double quotes inside, no spaces in unquoted parts).

## WHAT IS ALREADY DONE (verified on the emulator unless marked)

Files changed by the previous session (all uncommitted): `packages/inject/tvfs.js`, `packages/inject/tvnav.js`, `MainActivity.java`, `TvHint.java`, `TvProfiles.java`, `config/apps/movy.json` (git-ignored, `tvProfiles.movy`), docs under `docs/site-dossiers/` and `docs/TV-ADAPTER-PLAN.md`, `scripts/tv-test/`.

- Emulator remote fixed (`hw.keyboard = yes`). Arrow keys, OK, Back reach the app.
- **Viewport flip diagnosed and fixed (re-verify)**: `TV.vpLog` records every width change. Before: Movy flipped 960, 1280, 960, 1280 about eight times in the first 3 s because the site re-inserts its own `<meta viewport>` and the fixer waited 120 ms per fix. Now: `tvfs.js` guards viewport writes synchronously (patches `Element.prototype.setAttribute` and `HTMLMetaElement.prototype.content`, plus a synchronous `MutationObserver`) and a **boot veil** (`<style id="__movy_veil">` hides `body` and blacks `html`) holds the first paint until the width is correct, hard cap 2.5 s. Log after the fix on Movy and Cinejoy: `start:980`, then one `resize:1280`. **Not yet solved: width becomes 960 while the player is fullscreen** (see Deliverable C).
- Hint chip and first-run toast switched off (`TvHint.ENABLED = false`).
- Movy: D-pad row/column navigation works; OK on hero Play opens the in-page player, enters fullscreen, plays; Back leaves fullscreen then the page.
- Movy player, implemented and mostly verified: **free mode** (playback starts with no focus: OK = play/pause, Left/Right = seek 10 s, Up = reveal controls and focus the top bar, Down = centre buttons); a profile key `player.menu` (a visible popover is a modal layer, focus is confined to it); `player.hide` (layers hidden by the 5 s timer, class `movy-pctl-off`); the 5 s timer (`CONTROLS_HIDE_MS`) plus a keep-alive that dispatches mouse events every 1.5 s so the site's own auto-hide does not close a menu; page-first Back (`__movyTv.back()`: close the popover with Escape, fall back to clicking the opening button) wired in `MainActivity.dispatchKeyEvent`/`onBack` (the shell must consume BACK in fullscreen itself, because Chromium would exit fullscreen before the page sees it); vertical scroll-into-view for inner scroll containers (server list); SVG-as-button activation (SVG has no `click()`); `TV.refresh()` rebuilds the candidate cache on keys in fullscreen. Screenshot proof: after 5 s all three regions disappear and only the video remains.
- **Known broken or unverified right now**: (a) the player's **Back arrow** (an `svg.cursor-pointer`, first child of the left group in the top bar) is in the profile's `focusables` but is **not reachable**: pressing Left from "Quality" lands on the centre buttons or elsewhere. Root cause found: `tvnav.js buildCache` caps candidates at `MAX_CANDS = 700` in DOM order, and the player is last in the DOM, behind hundreds of home-page and menu nodes, so it is starved. A naive "only nodes inside `document.fullscreenElement`" filter was tried and **broke player navigation entirely** (focus left the player, page scrolled); it was reverted. Needs a correct fix (for example: scope the scan to the active modal / player root by selector from the profile, keep the fullscreen element out of the equation, raise or replace the cap with per-layer budgets, or build candidates for the player layer first). (b) Back unwinds menu, then controls, then fullscreen only when the controls are still visible; verify the whole chain once the Back arrow works. (c) Hearts ("Favorite server") skip rule was fixed to `#vp-shell [aria-label="Favorite server"]` (they are `span[role=button]`), re-verify. (d) Cinejoy and the other three sites have **not** been tested on the emulator beyond "page opens at 1280".

## KEY TECHNICAL FACTS (so you do not rediscover them)

- Architecture: native Java shell (Capacitor 8 plus custom classes in `app.movy.shell`), WebView, per-site JS profile (`tvProfiles` in config: selectors only, allow-listed keys in `TvProfiles.java`), page scripts injected at document start in every frame. `tvnav.js` = candidate cache, rows/lines model, spatial pick (`tvnav-pure.js`: `score`, `pickVertical`, `pickHorizontal`), modal detection, focus ring class `movy-tvf`; `tvfs.js` = idle fade, viewport, media keys, fullscreen policy, player chrome, Back; `TvController.java` routes keys (MENU and friends open the Island, long OK opens the Island, colour keys pick sources, channel up/down step sources, transport keys control the video, long Back toggles pointer mode); `TvIdle`/`IdleGate` = 5 s idle; `IslandController` = the existing native right-side drawer (sources); `LaunchController`/`SplashView` = splash.
- The WebView on the emulator is Chromium 113. On the TV it will differ; do not use brand-new CSS/JS features without a fallback.
- TV runs the page at CSS width 1280 (`setUseWideViewPort(true)`, `setLoadWithOverviewMode(true)`, meta `width=1280`) on a 1920x1080 screen at dpr 2 (native 960x540 dp). Sites switch to a **phone layout at 960** (Cinejoy shows a floating pill nav at the bottom: this is the "ruined navigation bar"), so the layout width must stay 1280 or more at all times.
- Chromium consumes BACK in fullscreen, `fullscreenchange` fires immediately, and the page's "stay in fullscreen" logic (`wantFs`) will re-enter unless the shell marks the exit as user-initiated first (`TV.userExitFs()` and `lastUserFsExitMs`).
- CSS `pointer-events` is inherited: a closed popover sits in the DOM under a transparent wrapper (`opacity:0`), its own computed `pointer-events` is `none`, descendants inherit it. Detect "open" with visibility, ancestor opacity and the element's own pointer-events.
- Frames: Movy and Cinejoy play in the top document (same-origin `<video>`, full control). **7Movies and Moonflix play in cross-origin iframes** (control is limited to fullscreen on the parent, key relay by `postMessage`, taps at coordinates). Their embeds may sit two documents deep.
- The idle gate, native hint, Island and Settings are native views over the WebView; they hide on idle.
- Movy player DOM (use as the model for the others): `#vp-shell` > `.vp-root` > `.vp-container` > `.absolute.inset-0.z-10.pointer-events-none` containing three layers: top bar `.absolute.top-0.left-0.right-0` (left group: back arrow `svg.cursor-pointer`, title; right group: Quality and Audio & Subtitles `.tabbable` buttons), centre `[class*="mobileCenterControls-module"]` (three `.tabbable` buttons at about y 250), bottom bar `.absolute.bottom-0.left-0.right-0` (seek track, time `0:27 / 01:44`). Popovers are `.player-surface` inside a `fixed ... z-50` wrapper: the **Quality popover** has three zones (Quality list 2160p/1080p/720p/480p, Server list of about 20 cities each with a heart, Speed row 0.5x to 2x); the **Audio & Subtitles popover** has an Audio column ("No audio tracks available"), a Subtitles column (Off plus about 40 languages, scrolling, plus "Upload subtitles"), a search input, "Search on OpenSubtitles" (opens another layout) and "Customize appearance" (another layout). Escape closes a popover.
- Cinejoy (see `docs/site-dossiers/cinejoy.md`): SvelteKit, desktop nav at 1280, labelled player controls (Back, Cast, Play/Pause, Rewind 10s, Forward 10s, Mute, Picture in Picture, Servers, Subtitles, Settings, Fullscreen), TMDB-driven, volume range input must not trap Left/Right, comments section is a focus sink. Also read `7movies.md`, `movy.md`, `moonflix.md` and `README.md` in `docs/site-dossiers/`, and the current `tvProfiles` in `config/apps/movy.json`.
- Moonflix: first Play click tries to open an ad tab; about 20 tracker hosts; autoplaying hero trailer; cards are divs with `aria-label`ed buttons ("Play <title>", "More info about <title>"); detail is a modal. 7Movies: fake "security check" and fake "Firefox?" `iframe[srcdoc][sandbox*=allow-modals]` overlays; heavy ad hosts on the watch page; server picker in a closed `<details>`. Movy: full-viewport srcdoc ad iframe, rotating hero.

## METHOD: MAP FIRST, DOM FIRST

1. Build (or reuse) an **inspector** that attaches to the page and can dump, for the current state: every interactive element (tag, role, aria-label, text, rect, computed `cursor`, `pointer-events`, `opacity`, `visibility`, ancestors' opacity, tabindex, `inert`/`aria-hidden`, iframe depth), the scroll containers, the fixed/overlay layers (z-index, rect, what they contain), the player tree, and the viewport/meta/fullscreen state. Save dumps to `docs/tv-adapter/dom/<site>/<screen>.json`.
2. **Click everything** on each site once (in the emulator, with the real shell): every nav entry, every card type, hero, detail, season and episode picker, search (typing, results), player and **every player control and every sub-menu**, error and empty states. Record what opens, what layer it is, what is focusable inside it, and how it closes. If a page cannot be opened or needs a login, ask the owner.
3. Only then design the focus map and the adapter. Screenshots are for visual confirmation of layout and for what the DOM cannot say (canvas, video frames).

## DELIVERABLE A: Activity grid / focus map (the core of the job)

For every site and every screen state, produce a machine-readable **focus map** and enforce it at runtime.

- Screens per site (at minimum): Home, Movies list, Shows list, (Anime), Search (input and results), Detail (movie), Detail (show with season and episode picker), Continue watching / My List, Sign in page (reachable, not automated), Player (free mode), Player (controls visible), each Player popover and each nested layout, Error/empty states, Modal dialogs.
- Format (`docs/tv-adapter/focus/<site>.json`, validated by a JSON schema you also add): per screen, **zones** (ordered groups: `topbar`, `hero`, `row:<name>`, `grid`, `sidebar`, `episodes`, `controls-top`, `controls-centre`, `controls-bottom`, `popover:<name>`), each zone with `selector`, `items` selector, `orientation` (`horizontal` | `vertical` | `grid`), `entry` (which item gets focus when entering from up/down/left/right, e.g. "same column", "first", "last used"), and **explicit exits**: `up`, `down`, `left`, `right` to another zone or `none`/`edge`. Example: pressing Up from the first hero button goes to zone `topbar` (items: Back, Quality, Audio & Subtitles), pressing Down goes to `row:Top 10`, pressing Down again to `row:Trending`; Left/Right move inside the zone and never wrap or leave it unless the exit says so.
- **Pressable set per direction must be exact**: when focus is in a zone, the map states which items are reachable by each arrow. Invisible, clipped, hidden, `aria-hidden`, closed `<details>`, offscreen slider layers, and decorative elements are never focusable. Hearts, scroll arrows, add-to-list buttons and similar noise are skipped on TV unless the owner wants them.
- Runtime: extend `tvnav.js`/`tvnav-pure.js` so zones and exits drive navigation (spatial scoring remains the fallback for unmapped pages). Remember the column when moving between rows (Netflix behaviour). Entering a popover confines focus to it; closing returns focus to the opener.
- **Automated verification**: a walker that, for each screen, visits every item and checks that each arrow key lands exactly where the map says (or stays put at an edge), that no item is unreachable, and that nothing invisible ever gets focus. Run it for every site and attach the report. Add unit tests for the pure parts.
- Fix the known blocker: the player's **Back arrow** must be reachable (Left from Quality). See "Known broken" above.

## DELIVERABLE B: Focus visuals

- One consistent, subtle, TV-grade indicator on every site: for example a soft scale (1.04 to 1.06) with a thin 2 px light ring and a low-alpha glow or a lifted shadow, matching each site's corner radius, plus fade-in of 120 ms. Cards get scale and ring; text buttons and pills get a filled highlight; small icons get a circular halo. No saturated blue. Respect `prefers-reduced-motion`.
- **Suppress the site's own focus styling** (the blue outline seen on the player's Quality button is the site's `:focus-visible` style; do not double it): neutralise `outline`, `box-shadow` and `ring` utilities on the focused element and its wrapper while ours is shown, and clip nothing (rings must not be cut by `overflow:hidden` rows; add padding or use an overlay element positioned over the focused rect).
- The indicator hides on idle (existing behaviour) and never shows on invisible elements.

## DELIVERABLE C: Layout size is decided once and never changes

- Keep the synchronous viewport guard and the boot veil, but review them critically: confirm with `TV.vpLog` and an automated test that on all four sites, from cold start through three route changes, `innerWidth` changes exactly once (initial to 1280) and never again.
- **Fullscreen**: today `innerWidth` becomes 960 (the native dp width) when the player enters fullscreen, and returns to 1280 on exit. The player UI then renders at a different scale than the page ("the size changes when the video player plays"). Investigate why (the fullscreen window is laid out by Chromium at native size and the meta viewport scale does not apply). Choose and implement one invariant: for example apply the same scale to the fullscreen element (CSS `zoom`/`transform: scale(...)` on the player root with width 1280), or force the WebView's layout width natively (for example sizing the WebView to 1280x720 dp and scaling the view, only if it does not hurt video decoding and memory), or re-assert the meta after `fullscreenchange`. Measure `innerWidth`, player font size and control rect sizes before and after fullscreen and prove they are equal.
- Evaluate `setLoadWithOverviewMode(true)` (it can zoom out when a page overflows horizontally, which looks like "zoom resets"); keep it only if it causes no scale change.
- No page refresh or white flash between pages. The veil applies to the first paint of a top-level load only, never to SPA route changes or to the player.

## DELIVERABLE D: Player UX (all four sites)

- Behaviour spec (apply to Movy first, then Cinejoy, then 7Movies and Moonflix):
  - Playback starts fullscreen and "free": OK = play/pause, Left/Right = seek 10 s (hold = accelerate), Up = reveal controls and focus the top bar, Down = reveal and focus the centre controls or the seek bar, Back = hide controls if shown, else leave the player.
  - Controls visible: arrow keys walk the **controls grid** (top bar left to right including the Back arrow, centre transport, bottom seek bar). OK activates. Opening a menu confines focus to it; Back closes it and returns focus to the opener.
  - **Hide everything after 5 s without a key** (top bar, centre buttons, bottom bar, any toast). Any key shows them again and restarts the timer. Paused video keeps the controls visible. Open menus keep them visible.
  - **Menus and nested layouts**: map and make navigable every sub-menu: quality, server (long scrolling list), speed, audio, subtitles (long scrolling list), "Search on OpenSubtitles" (text input: needs an on-screen keyboard path), "Customize appearance" (font size, colours, etc.), "Upload subtitles" (skip on TV), next episode, episode list, season picker. Sliders (volume, seek, subtitle size) must work with Left/Right when focused. Long lists scroll with focus.
- Per player type: in-page `<video>` (Movy, Cinejoy): drive the site's own controls and the video API. Cross-origin iframe (7Movies, Moonflix): fullscreen the parent, relay keys to the iframe, use coordinate taps only as a last resort, and document what is impossible.
- Back chain must be exactly: popover/nested layout, then controls hidden, then exit fullscreen, then previous page, then on the first page "press Back again to exit".

## DELIVERABLE E: Per-site native drawer plus element hiding

- **Drawer**: a native, Android TV style right-side drawer per source, built on the existing `IslandController`/`IslandView` family or a new view in the same visual language (modern, minimal, iOS-like squircles as in the existing Island). Opened with MENU (and the existing long-OK fallback), closed with Back or Left. Entries come from per-source config (relative paths and action ids only, no absolute URLs in code): `{ id, label, icon, action }` with actions `open-path`, `search`, `resume`, `sign-in`, `home`, `custom-js`. Entries are the site's real navigation (study each site's header and menus first): for example Home, Movies, Shows, Anime, Search, New and Popular, My List, Continue Watching, Sign in. The drawer also keeps the source switcher.
- **Search**: a native text entry flow suited to a remote (on-screen keyboard or the system IME) that submits to the site's search route or fills and submits the site's own search field; results pages must be navigable by the focus map.
- **Resume**: if a site has a continue-watching row or per-title resume, expose it; otherwise persist last-played position per source in the shell and offer it.
- **Element hiding**: for each site list the elements to hide (logo, floating header pill, scroll-to-top, notification regions, promo banners, cookie bars) as cosmetic rules in the config's adblock section, applied by the existing cosmetic engine before first paint (so they never flash), and verify no layout hole is left. Once the drawer exists, the site's own header is hidden on TV on every page that has one (keep what the page needs, such as a search field if no drawer route exists).

## DELIVERABLE F: Preload gate, caching, speed

- **Preload gate**: the splash stays up until the page signals "adapter ready" through a new `MovyTvNative.ready()` bridge call (viewport applied, profile CSS in, cosmetic rules applied, first focus set, veil lifted), with a hard cap (6 s) so a slow site never hangs. The scripts and CSS are already local assets; make sure they are injected at document start in every navigation (including redirects and SPA hard reloads) and that nothing is applied late. Reuse the `onPageCommitVisible`/progress hooks in `LaunchController`.
- **Cinejoy**: intercept TMDB API GETs in the shell (`AssetCacheInterceptor`, `CachePolicy`) with a disk cache, stale-while-revalidate, TTL 6 to 24 h for list endpoints; cache TMDB images (content-addressed, effectively immutable, capped size); keep `_app/immutable/*` in the HTTP cache; `preconnect` to the TMDB hosts; check the site's service worker does not fight the cache. **Never cache video segments or manifests.** Measure before and after (request count, bytes, time to first meaningful paint on a cold and a warm start) and report the numbers.
- Moonflix and 7Movies: block the tracker and ad hosts at the network level (the free speed win), kill the autoplaying hero trailer on TV, keep popup blocking on.

## STAGES AND GATES (do them in this order; each gate needs evidence in the report)

| # | Stage | Pass gate on the emulator |
|---|---|---|
| 0 | Harness: inspector (CDP or in-page recorder), reliable key driver, DOM dump tool, walker | one command dumps a screen's interactive elements and walks a key sequence with a printed focus trail |
| 1 | DOM maps of all four sites (all screens in Deliverable A), player and menus included | `docs/tv-adapter/dom/*` complete; owner asked about anything that would not open |
| 2 | Layout size invariant incl. fullscreen (Deliverable C) | automated test: width constant across load, 3 route changes, enter and exit fullscreen, on all four sites |
| 3 | Boot veil and preload gate (Deliverable F first part) | no un-styled or re-laid-out frame (frame capture or `vpLog`/paint timing), splash lifts only on `ready()`, cap works |
| 4 | Focus map engine + visuals (A and B) on Movy | walker passes on every Movy screen; Back arrow reachable; no blue ring; rows remember the column |
| 5 | Movy and Cinejoy player UX, menus, 5 s hide, Back chain (D) | scripted run: play, seek, open every menu and sub-menu and operate it, hide after 5 s, Back chain exact |
| 6 | Element hiding + native drawer (E) for Movy and Cinejoy | logo and clutter gone, drawer opens and every entry works, search finds a title |
| 7 | 7Movies and Moonflix (popups, overlays, cross-origin player, focus map, drawer) | no ad tab or fake overlay, OK/Left/Right/Back work in the embedded player, walker passes |
| 8 | Cinejoy cache and speed (F second part) | measured improvement, numbers in the report |
| 9 | Full regression, unit tests, phone flavor and desktop untouched | `npm test`, Gradle unit tests (`testTvDebugUnitTest` or the project's equivalent), per-site checklist below all pass |

**Per-site checklist (run for each site):** cold start with no visible resize; wake key; move through hero, rows, header or drawer; OK opens detail; season/episode pick for a show; Play starts video fullscreen; seek, pause, quality, server, speed, audio, subtitles, sub-menus; 5 s hide; Back chain; drawer entries; search; no ad tab or overlay; second launch is faster.

## REVIEW PROTOCOL (Claude reviews your work)

- Write `docs/tv-adapter/REPORT.md`: per stage, what changed (file list), the gate evidence (logs, dumps, walker output, measured numbers, key screenshots), what is **not** verified, and open questions for the owner. Be honest: a stage with a failed or skipped gate is marked as such.
- Keep changes small and reviewable: one concern per change set, tests next to code, no drive-by refactors.
- Claude will independently re-run on the emulator: the viewport/vpLog test, the walkers, the player Back chain, the 5 s hide, the drawer entries and the Cinejoy cold/warm timing, and will diff `packages/inject`, `MainActivity.java`, `TvProfiles.java` and the config for leaked URLs, regressions and missing tests. Anything that only works through hand-edited generated files (`assets/shell`, `movy-defaults.json`) will be rejected.
- Final real-TV check is the owner's; prepare a short checklist for it.

## DECISIONS LEFT TO YOU (decide, document the reason in the report, ask the owner only if it is truly theirs)

Fullscreen size invariant mechanism; CDP inspector vs in-page recorder; focus-ring implementation (class on element vs overlay element); drawer implementation (extend the Island or a new view); on-screen keyboard approach for search; how to cap candidates (per-layer budgets vs scoped scans); what to do for features that are impossible in cross-origin players.
