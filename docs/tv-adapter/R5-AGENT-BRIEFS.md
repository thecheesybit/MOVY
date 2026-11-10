# Round 5 — agent briefs (for relaunching from any chat)

Launch with the Agent tool: `subagent_type: "general-purpose"`, `model: "sonnet"`, `run_in_background: true`. Only one
agent drives the emulator at a time; a second may run read-only (for example `code-reviewer` on the last level's commit).
Every brief ends with: do NOT commit or push; write a "## Round 5 — Level N" section in `REPORT.md`; reply with a
~20-line summary.

## Common preamble (put this first in every brief)
You are working on Movy TV in D:\Movy (Windows; Bash = Git Bash, PowerShell available). Movy is a Capacitor Android
TV app that wraps four streaming websites (Movy, Cinejoy, 7Movies, Moonflix) in a WebView, with injected JS in
`packages/inject/*.js` and native Java in `apps/mobile/android/app/src/main/java/app/movy/shell/`.

Read first: `docs/tv-adapter/ROUND5-PROGRESS.md`, `ROUND5-REVAMP.md` §0–§2 plus your level, `R5-INVENTORY.md`, and the
previous level's section of `REPORT.md`.

Harness: `scripts/tv-test/b.ps1` (build+install), `t.sh` (open/key/shot), `cdp.mjs` (eval), `paint-wait.mjs`,
`r5-sample.mjs`, `r5-probe.mjs`, `r5-net.mjs`. Emulator `emulator-5554`, package `com.ayushkumar.movy.tv`. Gradle needs
`JAVA_HOME=C:\Program Files\Java\jdk-17` and `ANDROID_HOME=C:\Users\ak818\AppData\Local\Android\Sdk`.

Rules:
- TV only; phone flavour and Electron unchanged.
- No source URLs or hosts anywhere.
- Profile-driven: `tvProfiles` in `config/apps/movy.json` (git-ignored), mirrored in the zod schema, `config/schema.json`, `TvProfiles.java` and `TvProfilesTest`.
- Files under 800 lines; `tvnav.js` must not grow.
- New JS modules are registered in `CosmeticBridge` and in the `b.ps1` copy list.
- Unit-test pure logic; `npm test` and Gradle `testTvDebugUnitTest` stay green.
- Do NOT commit or push.

## Level 2 — native drawer (RESUME: continue on branch `wip/r5-L2-drawer`, commit `e65e280`)
Already done on the branch:
- the JS rail is removed from `tvnav.js` (1657→1071 lines)
- `tvdrawer.js` is started
- small edits in `tvfs.js`, `tvmenu.js` and `tvnav-pure.js`

Check that work first, then finish:
- **Native drawer:** `TvDrawerView` (plus helpers, each under 400 lines) above the WebView replaces the JS rail AND the TV use of `IslandView` (keep IslandView for phone).
- **Collapsed:** a ~64 dp strip of outline icons, vertically centred, current item white and the rest grey. Hidden in playback and on the start screen. Keep the `tvinset.js` content inset.
- **Keys:**
  - LEFT at the page's left edge (JS reports it via `TvBridge`) opens the drawer with focus on the current section.
  - A second LEFT (or MENU) moves focus to the **Sources row at the top: 4 circular source icons**. LEFT/RIGHT picks one, OK switches (the drawer closes at once and the L1 spinner shows "Opening <Source>…"), DOWN returns to the list.
  - RIGHT or BACK closes the drawer and restores the previous web focus.
  - Remove double-RIGHT switching. Unmap CH± source switching (L5 uses CH± for brightness; leave a TODO).
  - MENU on a page opens the drawer on the Sources row; MENU in playback is unchanged.
- **Order** (missing items hidden): Sources · Search · Home · Movies · Shows · site extra (Anime / New & Popular / Discover / Shorts) · My List · History · Continue watching (≤3 recents) · Settings (native SettingsSheet) · Account last.
- **Config:** profile `tvProfiles.<site>.drawer = { accent, items:[{id,label,icon,path|action,open,openText,item,itemSel}] }`, moved out of `SOURCE_THEMES`. Update the schema mirrors and `scripts/android/generate.mjs` defaults. Native calls `TV.drawerAction(id)` in `tvdrawer.js` (SPA navigation, the pick/account actions, and the current site search until L3).
- **Look:** Hotstar-like (`docs/design/ref-hotstar-home.webp`, `ref-hotstar-drawer-open.png`), style only.
  - Open: labels slide in over a full-height left gradient scrim (black ~90 % → transparent at ~45 % width), not a boxed panel. Focused/current label white bold, others grey. Background tone `#0f1014`; the source accent marks focus and the current source.
  - Animation: translateX + fade over 160 ms, no blur.
- **Performance:** opens < 100 ms after the key and survives navigation (native).
- **Gate 2:** on all four sources, LEFT, LEFT, RIGHT×n and OK switches source; every item works signed-out; RIGHT/BACK restore focus; latency measured; no JS rail left; CH± does nothing. Screenshots go to `r5-L2-*`. Report the tvnav.js line count before→after.

## After L2: fix the L1 review
Work through `docs/tv-adapter/R5-L1-REVIEW.md`: #1 high, #2–#7 medium, and the low items where cheap. Run on master after L2 is merged.

## Level 3a — keyboard proxy + in-page mic (L3 is split in two so one agent never runs out of room)
Read plan §"Level 3" (the "two different mics" bullets and the keyboard bullet) and `R5-INVENTORY.md` §4 and §4b.
- **Native keyboard proxy:** OK on any site search box opens the TV keyboard every time. A hidden native `EditText` takes focus and `showSoftInput`; each keystroke is mirrored into the site input (native value setter + `input` event); Done/Search = Enter in the site; BACK / hide returns D-pad focus to the field. The WebView input is never focused for typing. Profile key `tvProfiles.<site>.search.input` (selector) in the schema mirrors, `TvProfiles.java`, tests. The drawer "Search" item opens the site's search page, focuses the box and opens the keyboard (replaces the interim behaviour from L2). Keep the pure parts (diffing text, selector choice) unit-tested.
- **In-page mic:** a round outline mic button injected right of each site's search field, same height as the field, a normal D-pad stop (RIGHT from the field), focus ring like the cards. OK → native `RecognizerIntent` (small listening pill, hidden when nothing resolves the intent); the text is typed into that site's search box (value setter + `input`/`change`; Enter if the site needs it); focus then moves to the first result. New module `tvsearchmic.js` (registered in `CosmeticBridge`, `generate.mjs`, `b.ps1`) — do not grow `tvnav.js`.
- **Gate 3a:** on all four sites OK on the search box opens the keyboard every time and typing appears live in the site and results update; DOWN after typing reaches results on Cinejoy and 7Movies; the in-page mic types spoken text (emulator: simulate a recognition result through a debug intent if no recogniser exists) and results appear; BACK restores focus. Screenshots `r5-L3a-*`. Report section "## Round 5 — Level 3a".

## Level 3b — global mic search + continue watching (after 3a is merged)
Plan §"Level 3": the global search overlay (4 stages), KEYCODE_SEARCH (84) + 231/219 handling, per-source `search.api` fetched natively, results loop with BACK returning to the same card, recents; Continue watching (SharedPreferences, Watch Next via androidx.tvprovider, `movy://open` deep link, in-app drawer items). Report section "## Round 5 — Level 3b". Gate 3 in the plan.

## Speed pass — start-screen ring (RESUME: continue on branch `wip/r5-speed-ring`)
Complaint: after choosing a source the ring takes ~5 s ("not fast fast"). The ring has a fixed `RING_MS = 3000` fill, then waits for the site's first paint. Steps: (1) MEASURE per source, 3 cold runs each (force-stop between), medians, split into ring dwell / load start delay / DNS+connect / site paint — BEFORE table; (2) CUT: end the ring at first paint with a tiny min dwell (400-600 ms), start loading the focused source the instant it is focused (and the default source behind the start screen at app start), pre-warm DNS/TLS for the other three (connections only), remove delays between OK and `loadUrl`, lift the cover on first paint; (3) unit-test the pure timing rules; `npm test` (121) and Gradle `testTvDebugUnitTest` (262) stay green; (4) MEASURE again (AFTER) and be honest about what the sites' own hydration (Cinejoy ~6 s) makes unfixable. Write "## Round 5 — Speed pass" in `REPORT.md`. Do NOT commit or push.
