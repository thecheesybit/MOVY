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
