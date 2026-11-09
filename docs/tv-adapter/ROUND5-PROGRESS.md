# Movy TV — Round 5 progress log (resume from here)

**Any new session: read this file first, then `ROUND5-REVAMP.md` (the plan), then only the section for the current
level.** This log is updated by the orchestrator after every stage. Newest entries at the bottom of §4.

---

## 1. How to resume in a new chat
Paste this into a new Claude Code session in `D:\Movy`:

> Resume Movy TV Round 5. Read docs/tv-adapter/ROUND5-PROGRESS.md and docs/tv-adapter/ROUND5-REVAMP.md, check
> `git log` and `git status`, then continue from "Next step" in §2. You are the orchestrator: run one emulator agent at a
> time (Sonnet 5.5 medium, `model: "sonnet"`), max two agents at once, review and commit each level, and update this log
> after every stage.

## 2. Current status
| | |
|---|---|
| Current level | **PAUSED 2026-11-10 by the user, during L2 (native drawer).** No agents running. |
| Next step | Resume L2: `git switch wip/r5-L2-drawer` (commit `e65e280`: the JS rail is removed from tvnav.js (1657→1071 lines) and `tvdrawer.js` started; tvfs/tvmenu/tvnav-pure touched; **native `TvDrawerView` not built, schema/profile `drawer` key not done, CH± not unmapped, IslandView still used on TV**). Relaunch the L2 agent on that branch with the same Level 2 brief, telling it to continue from that commit. Then merge into master, fix the `R5-L1-REVIEW.md` findings (1 high, 6 medium), commit, and start L3. Gradle needs `JAVA_HOME=C:/Program Files/Java/jdk-17` and `ANDROID_HOME=C:/Users/ak818/AppData/Local/Android/Sdk`. |
| Last commit | master `a967bd8` (L1 review docs); WIP branch `wip/r5-L2-drawer` `e65e280` |
| Uncommitted, on purpose | `config/apps/movy.json` is git-ignored (`recommendedSource` changed to `cinejoy`) |

## 3. Decisions (user-confirmed) — do not re-ask
| Date | Decision |
|---|---|
| 2026-11-10 | Native shell owns the chrome (drawer, search, keyboard, dialogs, loading, brightness, exit); the web owns the content. We never edit the sites. |
| 2026-11-10 | Orchestration: one agent drives the emulator at a time; max two agents at once; all agents Sonnet 5.5 medium. |
| 2026-11-10 | Level order: L0 inventory → L1 navigation and loading → L2 drawer → L3 search and continue watching → L4 player panel → L5 system → L6 QA. |
| 2026-11-10 | Default source = **Cinejoy**. |
| 2026-11-10 | Visual reference = **JioHotstar TV** (`docs/design/ref-hotstar-*`): dark `#0f1014`, icon rail expanding over a gradient scrim, circular profile avatars. Copy the style, never the brand. |
| 2026-11-10 | Start screen = the four sources as circular "profiles". A ring fills once around the focused source while it loads, then the site opens. LEFT/RIGHT changes source, OK opens now. |
| 2026-11-10 | Drawer: LEFT at the edge opens it; a second LEFT jumps to the **top row of 4 source icons**; no double-RIGHT; CH± no longer switches source. |
| 2026-11-10 | **Two mics.** (1) The remote mic/Assistant key = global search across all 4 sources (overlay stages: Listening → Searching → Results → Action; BACK from a detail page returns to the same results card). (2) An in-page mic beside each site's search box types speech into that box. |
| 2026-11-10 | OK on any site search box **always opens the TV keyboard** (native EditText proxy mirrors keystrokes into the site input). |
| 2026-11-10 | CH+/CH− = app brightness; VOL± = system volume (the first press unmutes a muted site video). |
| 2026-11-10 | Exit: BACK at the root opens "Exit Movy?" (focus on Stay); a second BACK or OK on Exit exits. |
| 2026-11-10 | Continue watching: Android home-screen Watch Next (TvProvider; Engage SDK needs Play) + an in-app row; resume via `movy://open` deep link. |
| standing | Never write source URLs or hosts into files, logs, commits or UI. No movie media on disk. No second WebView during playback. TV-only changes; phone and Electron unchanged. |

## 4. Stage log
| Date | Stage | What happened | Commit |
|---|---|---|---|
| 2026-11-10 | Audit | Read the codebase: `tvnav.js` is 1668 lines and holds the JS rail drawer; native `IslandView` is a second source switcher; CH± switch source; search focuses the site input (IME trap); Round 4 timings: Cinejoy switch 10–21 s, cold Movy 5.1 s. | — |
| 2026-11-10 | WIP commit | Committed pending rail work (History/Watchlist/Settings/Account items). Verify: 94/94 tests pass. | `8105c36` |
| 2026-11-10 | Research | Google TV quality checklist, Watch Next vs Engage SDK, TV voice/search key behaviour. Summary in plan §0. | — |
| 2026-11-10 | Plan | `ROUND5-REVAMP.md` levels 0–6 written. | `ae750ab` |
| 2026-11-10 | L0 started | Inventory agent launched (timings, scripts per page, player settings matrix, search per site, drawer items, resize repro, spinners, lag causes). | — |
| 2026-11-10 | Plan update | Start screen with source avatars and a progress ring; global mic search with 4 overlay stages; Hotstar-style drawer; Cinejoy default. Reference images saved in `docs/design/`. L0 agent also asked for each site's search JSON endpoint and mic keycodes (§9). | `b48b3b1` |
| 2026-11-10 | Plan update | In-page search mic beside the site search box + the keyboard always opens on the search box. | `d87c092` |
| 2026-11-10 | Resume log | This file + memory notes. | `2e8d78d` |
| 2026-11-10 | L0 done | `R5-INVENTORY.md`. Cold first poster: Movy 6.0 s, **Cinejoy 24.1 s**, 7Movies 7.1 s, Moonflix 11.9 s. Switch: Cinejoy 15.7 s (black screen ~10 s, no spinner). RAM 97→125 MB, stable. Causes: Cinejoy 33 XHR + 125 images; Moonflix/7Movies analytics NOT blocked; all 10 inject scripts (187 KB) run in every frame; tvnav rebuilds 77–118 ms ×9–26. Resize: tvfs requests fullscreen 1.5–2.2 s after `playing` → 1280→960 jump (7Movies embed stays re-laid out). BACK from player needs 3 presses; first key after idle swallowed. Cinejoy quality exists (gear → QUALITY) but D-pad can't reach it. No slider/colour inputs reachable on any site. KEYCODE_SEARCH(84) reaches the app (unmapped); 231/219 taken by the system Assistant. Search XHR templates recorded in §4. | `9f417a6` |
| 2026-11-10 | L1 started | Agent launched with L0 findings as priorities. | — |
| 2026-11-10 | L1 done | Start screen (StartView/AvatarView/StartRing, Cinejoy default, 3 s ring), one spinner + caption (LoadingMode), tvgate.js (player scripts only in player frames), tvplayerfx.js (fullscreen at Play, hidden until stable), one BACK leaves player, idle key acts, held D-pad throttle, tvfocusmem.js, DohProxy warm connections, Moonflix analytics rules. Fresh-emulator medians (HEAD → L1): switch Cinejoy 5356→5121 ms, launch Cinejoy 10352→9803 ms; the others within noise (L0's numbers came from a degraded emulator). **Not met:** Cinejoy cold ≤ 8 s (site's own 6 s main-thread hydration). Open: player-spinner selectors for `loading.hide`, Moonflix player sampling, 7Movies embed is 960x540 from the start (stable but small), start screen shows initials not logos, idle-key/focus-restore only unit-tested. Tests: npm 106, Gradle 217, all pass. | `7a8b13c` |
| 2026-11-10 | L2 started | Native drawer agent + L1 code-review agent (read-only) launched. | — |
| 2026-11-10 | L1 review | 0 critical, 1 high (start ring can wait 15 s if the page painted during the splash), 6 medium, 5 low. Saved in `R5-L1-REVIEW.md`; **fix after L2 lands**. | `a967bd8` |
| 2026-11-10 | **Paused** | User asked to pause. L2 agent stopped mid-work; its partial JS changes are parked on branch `wip/r5-L2-drawer` (`e65e280`) so master still builds and works at L1. | `e65e280` (branch) |


## 4b. L0 findings that change later levels
- **L3 mic key:** the remote's mic/Assistant keys (`KEYCODE_VOICE_ASSIST` 231, `KEYCODE_ASSIST` 219) never reach the app on the emulator; the system Assistant takes them. `KEYCODE_SEARCH` (84) does reach `dispatchKeyEvent`. So global search opens on key 84, from the drawer, and from the search overlay's own mic. Re-check 231/219 on the Bravia in L6.
- **L3 global search data:** Movy and Moonflix use a TMDB-style `/3/search/multi?query={q}`. Cinejoy uses the same TMDB search plus its own lists search. 7Movies uses a same-origin `/api/search?v=5&q={q}` with its cookie. Moonflix has no detail URL, only a modal, so its results must open the modal. Full templates are in `R5-INVENTORY.md` §4.
- **L3 keyboard:** on Cinejoy and 7Movies, DOWN stays inside the search input with the keyboard up; on Movy and Moonflix it falls through to the page behind. The native keyboard proxy fixes both.
- **L4 player:** Cinejoy's quality option is real (gear → QUALITY: Auto/1080p/720p/360p) but its tiles are unreachable by D-pad. No site's sliders or colour inputs are reachable, and Moonflix has no subtitle styling at all, so Movy-owned subtitle styling is required. 7Movies speed/fit/boost live in a legacy `#settingsPanel`. Movy's "Customize appearance" leaks focus into the hidden Quality menu.

## 5. Level checklist
- [x] L0 baseline + inventory (`R5-INVENTORY.md`)
- [x] L1 navigation, loading, start screen, no-resize
- [ ] L2 native drawer (partial, on `wip/r5-L2-drawer`) + sources row (Hotstar style)
- [ ] L3 search: keyboard proxy, in-page mic, global mic search, continue watching
- [ ] L4 unified player settings panel + subtitle size
- [ ] L5 exit dialog, cache lifecycle, warm-up during playback, brightness
- [ ] L6 full QA (emulator + Bravia), docs

## 6. Where things are
- Plan: `docs/tv-adapter/ROUND5-REVAMP.md`. Inventory: `docs/tv-adapter/R5-INVENTORY.md`. Results per level:
  `docs/tv-adapter/REPORT.md` ("Round 5 — Level N"). Screenshots: `docs/tv-adapter/screenshots/r5-L<N>-*`.
- Harness: `scripts/tv-test/` (`b.ps1` build+install, `t.sh` drive, `cdp.mjs` eval, `paint-wait.mjs` timings).
- Site notes: `docs/site-dossiers/*.md`. Real config (git-ignored): `config/apps/movy.json`.
