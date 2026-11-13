# Movy — merge and fix plan (2026-11-13)

Goal: one branch that has (a) everything on `main` up to `53d442f`, (b) our phone player fixes and HUD, and
(c) every open Movy item from `docs/KNOWN-ISSUES.md` closed or honestly marked "needs hardware / needs the owner".
YouTube Freemium is out of scope.

Work folder: `G:\no-ad\movy-latest`. Branch: `feature/player-hud-on-main`. Do not work in `G:\no-ad\movy` (old copy).

## 0. Rules for whoever executes this

1. **Secrets.** Never `git add` `config/apps/*.json`, `config/assets/**/google-services.json`, `restore-private.mjs`
   or `movy-friend-setup.txt`. Never write a source URL, host, Firebase id or key into a file, log, commit or chat
   reply. Use the source names only: Movy, Cinejoy, 7Movies, Moonflix. Never delete `C:\Users\niles\.movy\keystore`.
2. **Read first, in this order:** `docs/KNOWN-ISSUES.md`, `docs/tv-adapter/ROUND5-PROGRESS.md`,
   `docs/tv-adapter/ROUND5-REVAMP.md` §2 (ground rules) and the level you are on, `docs/tv-adapter/R5-AGENT-BRIEFS.md`,
   `docs/tv-adapter/R5-INVENTORY.md`, `docs/PLAYER-HUD-PLAN.md`. The decisions table in ROUND5-PROGRESS §3 is final;
   do not re-ask those questions.
3. **Profile-driven.** Site specifics go in `config/apps/movy.json` → `tvProfiles.<site>` and are mirrored in the
   schema (`packages/config/src`), `TvProfiles.java` and `TvProfilesTest`. No `if (host === ...)` in code.
   Because `movy.json` is git-ignored, every new profile key also needs a safe default in code so the app works
   for someone whose config lacks it, and a line in the stage report telling the owner which key to add.
4. **Scope gates.** TV work stays behind `cfg.tv` / `isTv`. Phone work stays behind `!tv`. Electron is untouched
   unless a stage says so.
5. **Size.** New JS goes into new modules (register in `CosmeticBridge.TV_SCRIPTS` and `TV_SCRIPTS` in
   `scripts/android/generate.mjs`). `tvnav.js` and `tvfs.js` must not grow.
6. **Every pure rule gets a unit test.** After each stage all four must pass:
   `npm test` · `gradlew testPhoneDebugUnitTest` · `gradlew testTvDebugUnitTest` · `npm run build -- --app movy --target all`.
7. **One commit per item** (`fix:` / `feat:` + the item id, e.g. `fix(T10): ...`). Do not push; the owner pushes.
8. **Honesty.** If something was not run on a device, write UNVERIFIED next to it in `docs/KNOWN-ISSUES.md`. Never
   mark an item fixed from unit tests alone when the item is about behaviour on a screen.
9. **Java strings with `\n`:** edit them with the Edit tool, not with a shell or Python rewrite (this broke the
   build once already).

Build environment on this machine: Node 22 is not the default, so prefix PATH with `%LOCALAPPDATA%\nvm\v22.23.5`;
`ANDROID_HOME=%LOCALAPPDATA%\Android\Sdk`; the installed JDK 21 works (the docs say 17). If R8 fails on missing
Firebase classes: `gradlew clean` in `apps/mobile/android`, then rebuild.

**Devices: this machine has no Google TV emulator and no device attached** (`emulator -list-avds` shows only
`ytf_test`). Stage 2 creates the TV emulator. The Sony Bravia belongs to the repo owner, so every "on the Bravia"
check stays UNVERIFIED on our side and goes into the hand-back list (Stage 10).

## 1. Where we are now (done, verified on 2026-11-13)

`feature/player-hud-on-main` = `main` (`53d442f`) + our three commits, cherry-picked:

| Commit | What |
|---|---|
| `0ec13d6` | Phone VLC-style HUD (`packages/inject/playerhud.js`), navguard / scamguard / loading fixes, volume swipe |
| `ed74dbe` | Hide the site's own controls while our HUD is up; relay to ancestor frames |
| `4095999` | Play/pause tap fix; site menu kept awake in landscape |

Only one real conflict: `BrightnessMath.gainScript`. Resolution keeps both ideas: the owner's tripled
`:fullscreen:fullscreen:fullscreen` selector (beats sites that cancel filters) **and** our "filter only the
`<video>` when it is in the same document" (so menus and subtitles are not re-rasterised). `BrightnessMathTest`
asserts both.

Checked after the merge: `npm test` 66 + 26 + 46 pass, 0 fail · Gradle phone 273 pass · Gradle TV 273 pass ·
phone APK builds (`dist\movy\movy-phone.apk`). **Nothing was run on a phone or a TV.**

Two things this merge changes for TV that the owner has not seen, so Stage 2 must re-check them:
- `gainScript` now filters only the video on same-document players. The owner measured luma 114 / 61 / 24.5 at
  200 / 100 / 40 % on the Bravia with the old rule. If TV brightness looks different, make the video-only branch
  phone-only (pass `tv` into `gainScript`) rather than removing it.
- `navguard.js`, `scamguard.js`, `loading.js` run on TV too. They now spare open menus and dialogs and debounce the
  seek spinner by 500 ms.

## 2. Stages

Do them in order. Each stage ends with the four checks from rule 6, a commit per item, and an update to
`docs/KNOWN-ISSUES.md` and (for TV) a "Round 5 — <stage>" section in `docs/tv-adapter/REPORT.md`.

### Stage 1 — Phone: prove the merged build on a real phone (owner's phone over USB)
Covers KNOWN-ISSUES §3.2 and our own unverified HUD work. Needs `adb devices` to show the phone; if it does not,
stop and ask the owner to plug it in with USB debugging on.

1. Install `dist\movy\movy-phone.apk`. Note: it is signed with our own key, so it will not update over the
   friend's build; logins survive only when updating over an install made with this same key.
2. Run the 11-point checklist in `docs/PLAYER-HUD-PLAN.md` on all four sources, portrait and landscape. Use
   `chrome://inspect` (or the harness `scripts/tv-test/cdp.mjs`, it works for the phone package too) to watch the
   console. Record a pass/fail table per source.
3. The two original user bugs must be reproduced **before** judging the fix (PLAYER-HUD-PLAN "Phase 0"): install
   the plain `main` build once, pick a subtitle, capture what glitches (which element is removed or repainted),
   then confirm the merged build does not do it. Write the finding into the plan's empty "Findings" section.
4. KNOWN-ISSUES §3.2 list: brightness in web fullscreen on each source (7Movies has a cross-origin player
   iframe), status bar visible except in fullscreen, brightness restored on exit, Island switcher, Settings sheet
   (including the new "Player controls" switch), red loading spinner, Cinejoy with the brightness filter.
5. Known weak spots of the HUD to look at on purpose, and fix if seen:
   - subtitles hidden by the generic hider (a site that names its subtitle box unusually) → add the selector to
     `SUBSEL` in `playerhud.js`;
   - a leftover bar background of the site → mark its wrapper in `scanHide()`;
   - the "Subtitles · Quality · Servers" label crowding the bottom row on a narrow phone → shorten to an icon plus
     "Options" under 400 CSS px;
   - HUD appearing in portrait (it should stay landscape/fullscreen only, the site's own controls serve portrait).

Gate 1: checklist table in `docs/PLAYER-HUD-PLAN.md`; both user bugs confirmed gone on at least Cinejoy and Movy.

### Stage 2 — TV test bench and regression pass
1. Create a Google TV AVD (1920x1080, 320 dpi, x86_64, Google TV image) with the SDK's `avdmanager`; start it with
   `scripts/tv-test/start-emulator.bat` (adjust the AVD name there or pass it in; do not hard-code a user path).
   The harness scripts contain the owner's package name `com.ayushkumar.movy.tv`; confirm the package our build
   produces and, if it differs, make the scripts read it from one variable in `scripts/tv-test/lib.sh`.
2. `scripts/tv-test/b.ps1` (build + install), then run the gates that exist: `gate-movy.sh`, `gate-cinejoy.sh`,
   `gate-7movies.sh`, `gate-back.sh`, `r5-search.sh`, `r5-mic.sh` (emulator screensaver off).
3. Regression focus for the merge: brightness with CH+/CH- on each source; Cinejoy settings tiles and Subtitle /
   Playback pages still drivable (the owner's 11-13 fixes); menus and dialogs are not removed by navguard/scamguard;
   the seek spinner still appears on a real stall.

Gate 2: all existing gates green on the emulator, or each failure listed with its cause before any new work starts.

### Stage 3 — Small TV fixes (T10, the small parts of T4, T11, T14, T9 keyboard)
| Id | Change | Where | Test |
|---|---|---|---|
| T10 | New profile key `search.skip` (selector list). Elements matching it are not D-pad stops. Add the clear "x" selectors for Movy and Moonflix. Movy: DOWN after typing goes to the first result, not the tabs (profile `search.firstResult`). | `tvnav-pure.js` (candidate filter), `tvsearchmic.js`, schema, `TvProfiles.java` | pure test: skipped element is never chosen; `r5-search.sh` |
| T4a | Movy "Customize appearance" leaks focus into the hidden Quality menu: the candidate filter must drop elements inside a menu that is not the top-most open one (visibility + `inert`/`aria-hidden` + zero-size ancestors). | `tvmenu.js`, `tvnav-pure.js` | pure test with a fake hidden menu |
| T4b | Sleep Timer opens with the wrong default focus: focus the selected (`aria-checked` / `.active`) item, else the first enabled one. | `tvmenu.js` | pure test |
| T11 | `TvBridge.painted` / `buffering` trust: have `tvpaint.js` call it only from the top frame and pass `location.origin`; natively accept the call only when the origin equals the current source's origin (`NavRules`) and the WebView's main-frame URL matches. Player-frame buffering calls must carry the frame origin and be on the source's allowed list. | `TvBridge.java`, `tvpaint.js`, `tvplayerfx.js`, `PaintLedger` | Java unit test of the accept rule |
| T14a | Finish the `loading.hide` player-spinner selectors per source (read each player on the emulator, add to the profile). | profile, `loading.js` | emulator: only one spinner during buffering |
| T14b | Movy hero trailer goes fullscreen after a key press: `tvfs.js` must request fullscreen only for a video the user started from a detail/player page (profile `player.page` match), never for an autoplaying hero. | `tvplayerfx.js` / `tvfs.js` | pure test of the rule; emulator |
| T9a | Search box is read-only, so a USB keyboard cannot type: in `SearchKeyboard`, route hardware key events (`KeyEvent` with a printable char, DEL, ENTER) to the proxy `EditText` while a site search box holds TV focus. | `SearchKeyboard.java`, `TvKeys.java` | Java unit test of the routing rule; `adb shell input text` on the emulator |

T14 leftovers that only need sampling (Moonflix player resize, 7Movies embed stays 960x540, skip-intro and
next-up cards): sample on the emulator, fix what is ours, and write the rest into KNOWN-ISSUES as site-side.

### Stage 4 — T1 start-screen ring speed pass
The half-done branch `wip/r5-speed-ring` is **not on GitHub**. Ask the owner to push it; if it does not arrive,
redo it from the brief ("Speed pass" in `R5-AGENT-BRIEFS.md`). Steps: measure BEFORE (3 cold runs per source,
medians, split ring dwell / load start / DNS+connect / site paint, with `scripts/tv-test/paint-wait.mjs` and
`timing.sh`); then end the ring at first paint with a 400-600 ms minimum instead of the fixed 3 s; start loading
the focused source the moment it is focused; pre-connect the other three (`DohProxy` warm connections only);
remove any delay between OK and `loadUrl`; measure AFTER. Files: `StartRing`, `StartView`, `LaunchController`,
`LoadingUi`, `PaintLedger`, `tvpaint.js`. Pure timing rule in its own class with a unit test.

T7 (Cinejoy cold start) is closed by this stage as far as we can: report the AFTER number and state plainly that
the site's own ~6 s hydration is outside our control.

Gate 4: BEFORE/AFTER table in REPORT.md; ring-to-content median under 2.5 s on Movy, 7Movies and Moonflix.

### Stage 5 — T4 player settings (ROUND5-REVAMP "Level 4")
1. Re-test the 11-13 settings-page engine (tiles, sliders, toggles, pills, `player.menuDrill`, `rangeNext`) on
   Movy, 7Movies and Moonflix; fix per-site gaps through profile keys.
2. 7Movies legacy `#settingsPanel` (speed / fit / boost): make it a menu the engine can enter (profile
   `player.settings.legacy` with open selector and item selectors), or drive speed and fit natively through the
   video element when the panel cannot be reached.
3. **Movy-owned subtitle style** (needed because Moonflix has none): size S/M/L/XL, colour (white / yellow),
   background on/off, raised position. Injected CSS for `::cue` plus each site's cue element (profile
   `player.subtitles`), stored in `Prefs`, applied in every frame through a new module `tvsubstyle.js`. Reuse the
   same module on phone later; for now gate it to TV.
4. The native right-side panel described in the plan (Quality · Audio · Subtitles · Subtitle size · Speed ·
   Server) is the primary path; the site menus stay usable.

Gate 5 = the plan's Gate 4: every option in the R5-INVENTORY matrix marked "exists" works from the remote on
every source; subtitle size changes live on all four; no resize when the panel opens or closes.

### Stage 6 — T3 Watch History and T2 Continue Watching (shared store), then T2 global search
Build the store once and use it for both items.
1. **Watch records** (ROUND5-REVAMP "Continue watching"): source, path, title, poster, position, duration; written
   every 15 s and on pause/exit from the player frame; native `SharedPreferences` JSON, max 20. For Moonflix also
   import the site's own `recentlyWatched` localStorage entries on page load (it has no detail URL: opening an
   entry must open the site's modal, see R5-INVENTORY §4).
2. **T3:** a native tiles screen "Watch History" (reuse `TvDrawerView` styling and `AvatarPainter` tokens) fed by
   the store; one "Watch History" drawer item on every source. Keep the real site pages where they exist
   (Cinejoy, Movy) as the first choice only if the owner's decision table says so; otherwise the native screen is
   the single path.
3. **T2 continue watching:** Android home Watch Next row (`androidx.tvprovider`, `WatchNextProgram`, type
   CONTINUE) with deep link `movy://open?src=<key>&p=<path>`; handle the link in `MainActivity`; remove an entry
   above 95 % watched; in-app row on the Watch History screen. The link must carry the source **key**, never a host.
4. **T2 global search:** native overlay with the four stages (Listening → Searching → Results → Action) exactly as
   in ROUND5-REVAMP "Level 3"; opened by keycodes 84, 231, 219 and `onSearchRequested()`; per-source
   `search.api` profile fetched natively in parallel (6 s timeout, site cookies) through the existing HTTP/DoH
   stack; a source without an endpoint gets one "Search in <Source>" tile; BACK from a detail returns to the same
   results card; 8 recent queries. The endpoint templates are in R5-INVENTORY §4 and must live only in the
   git-ignored profile.

Gate 6 = the plan's Gate 3 (minus the Bravia parts).

### Stage 7 — T5 system (ROUND5-REVAMP "Level 5")
"Exit Movy?" dialog on BACK at root, focus on Stay, second BACK or OK on Exit exits; cache lifecycle table and
"Clear cache" / "Sign out of all sources" in Settings; warm-up of the other sources during steady playback
(native fetch into `AssetCache`, one request at a time, paused on `waiting`/seek, capped per session).
Brightness on CH± already exists; do not redo it.

Gate 7 = the plan's Gate 5.

### Stage 8 — Polish and debt (T8, T12)
- **T8 logos:** add an optional `logo` (file under `config/assets/movy/`) per source in the config schema;
  `AvatarPainter` draws it inside the circle, falling back to the initial. We must not ship third-party logo
  artwork in the public repo, so the files stay git-ignored like the rest of the private config; ask the owner
  for the images. Code and fallback are the deliverable.
- **T12 splits:** do this last, when the gates are green, as pure moves with no behaviour change:
  `tvnav.js` → `tvnav.js` + `tvnav-keys.js` + `tvnav-scroll.js`; `tvfs.js` → `tvfs.js` + `tvfs-controls.js`;
  `MainActivity` → move TV wiring into `TvController` and launch wiring into `LaunchController`. Re-run every gate.

### Stage 9 — Desktop (KNOWN-ISSUES §3.3)
Run on this Windows machine: `npm run build -- --app movy --target desktop`, run the NSIS installer end to end,
30-minute soak with minimize/restore, display scaling 125 % and 150 %, auto-PiP on minimize, brightness above
100 % on each source. Fix what breaks. Not doable by a model: code signing (needs a paid certificate) and the
Google sign-in password step (needs the owner's test account) — list both in the hand-back.

### Stage 10 — Close out
Update `docs/KNOWN-ISSUES.md` (new snapshot date, each item FIXED / UNVERIFIED / OPEN with a reason),
`ROUND5-PROGRESS.md` (stage log, checklist), `REPORT.md`. Write the hand-back list for the owner:
- T6 full QA on the Bravia (ARMv7): drawer speed, first key after idle, real mic keycodes 231/219, brightness luma
  after the `gainScript` merge;
- T9 real speech recogniser; T13 signed-in states and Cinejoy playback on real hardware;
- profile keys added in this work that the owner must copy into their own `config/apps/movy.json`;
- desktop code signing.
Then ask the user how to publish: fork + pull request, or collaborator access (a direct push was refused with 403).

## 3. Model: Sonnet 5.5 for every stage

All stages are written to be done by Sonnet 5.5. The three big stages (5, 6, 7) are therefore cut into
sub-stages of one session each. Rules that keep Sonnet on track:

1. **One sub-stage per session, one commit per item.** Start each session with: "Read `docs/MERGE-AND-FIX-PLAN.md`,
   do Stage N (sub-stage X) only, follow section 0, stop at the gate and report." Do not let a session continue
   into the next sub-stage; start a fresh one.
2. **Pure logic first, wiring second.** In every sub-stage write the pure function and its unit test before any
   Android or DOM wiring, and run the tests, then wire it. Pure code is where Sonnet is reliable; wiring is where
   it drifts.
3. **Read the neighbour before writing.** Before adding a module, read the closest existing one and copy its shape:
   `tvfocusmem.js` (+ test) for a small JS module, `PaintLedger.java` (+ test) for a pure Java rule,
   `TvDrawerView.java` for a native TV view, `SearchKeyboard.java` for native-to-page text, `tvsearchmic.js` for a
   page feature behind a profile key.
4. **No redesign.** If a sub-stage seems to need a different structure from the one written here, stop and report
   the conflict instead of choosing; the owner decides.
5. **Stop on red.** If a gate or any of the four checks fails twice for the same reason, stop and write what was
   tried; do not widen the change to make it pass.
6. **Use a second Sonnet session as reviewer** after Stages 5, 6 and 7: a read-only session given only the
   branch diff and ROUND5-REVAMP, told to list bugs, no edits. Fix its findings in a new session.
7. If the owner has Opus budget, the only sub-stages worth upgrading are 6c (global search model) and 7b
   (warm-up); everything else stays on Sonnet.

### Stage 5 split (player settings)
- **5a** Re-test the 11-13 engine on Movy, 7Movies, Moonflix; fix profile gaps only (no new JS). Gate: table of what
  works per source.
- **5b** 7Movies legacy `#settingsPanel`: reach it from the engine, or fall back to speed/fit through the `<video>`
  element. Pure test for the chosen rule.
- **5c** Subtitle style module `tvsubstyle.js` (pure CSS builder + test first, then injection + `Prefs`). Gate:
  size/colour change live on all four sources.
- **5d** Native right-side settings panel: first the pure model (sections present per site, in the fixed order,
  with test), then the view, modelled on `TvDrawerView`.

### Stage 6 split (history, continue watching, search)
- **6a** Watch-record store: pure class (add/update/trim to 20/remove above 95 %, JSON round-trip) + test, then the
  page recorder (15 s and on pause/exit) and the Moonflix `recentlyWatched` import.
- **6b** Watch History screen (native tiles from the store) + one drawer item on every source; Watch Next row and
  `movy://open` deep link in `MainActivity`.
- **6c** Global search, part 1: pure query/result model (per-source `search.api` mapping, merge, ordering, recents)
  + tests; key handling for 84/231/219/`onSearchRequested()`.
- **6d** Global search, part 2: the overlay view with the four stages, native fetching, Action stage and BACK
  returning to the same card. Reuse `ListeningPill` and `SearchVoice`.

### Stage 7 split (system)
- **7a** Exit dialog + "Clear cache" / "Sign out of all sources" in Settings + the cache lifecycle table in REPORT.
- **7b** Warm-up during playback (pure gate rule first: played >= 60 s, buffered >= 30 s, no `waiting` in 30 s,
  cap per session; then the fetcher).

## 4. What a model cannot close
T6 (Bravia QA), T9 real recogniser, T13 signed-in states, the Bravia half of T14, T7 beyond our own ring, desktop
code signing, and the logo images for T8. These stay OPEN / UNVERIFIED with the owner.
