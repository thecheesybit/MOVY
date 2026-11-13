# Movy — status and known issues (snapshot 2026-11-13)

Single place to see what works, what is open and where to look. Sources: `tv-adapter/REPORT.md` (per-round results),
`tv-adapter/ROUND5-PROGRESS.md` (resume log), `tv-adapter/R5-INVENTORY.md` (measurements), `DESKTOP.md`.
Site names only (Movy, Cinejoy, 7Movies, Moonflix); never write source URLs, hosts or Firebase ids into files, logs or commits.

Legend: **OPEN** = reproduced or measured, not fixed. **UNVERIFIED** = code exists, never confirmed on real hardware / with real input.

## 1. Build and test state (2026-11-13)
| Check | Result |
|---|---|
| `npm test` (inject 56, config 26, desktop 47) | 129 pass, 0 fail |
| Gradle `testTvDebugUnitTest` / `testPhoneDebugUnitTest` | not re-run on 2026-11-13 (last recorded: 262 pass at L3a, plus the 11-13 `TvProfilesTest`/`BrightnessMathTest` additions); run them before merging |
| `npm run build -- --app movy --target all` | phone APK, TV APK, Windows installer + portable build |
| Devices used | Google TV emulator (1920x1080 @ 320 dpi), Sony Bravia (ARMv7, 32-bit) for spot checks, OnePlus Pad 3 (phone/tablet) |

Gradle needs `JAVA_HOME=C:/Program Files/Java/jdk-17` and `ANDROID_HOME=<Android SDK>`. Rust uses the gnu toolchain.

## 2. What changed since the last written log (2026-11-11 .. 2026-11-13)
| Commit | Area | Change |
|---|---|---|
| `1e6dfc8`, `c0d2920`, `4c57564` | all | Fullscreen brightness control, 2%..200% (phones: real screen brightness up to 100% + picture gain above; TV: picture gain 40%..200%; desktop video up to 200%); TV CH+/CH- = brightness |
| `dd60a9d` | all | Brightness filter now outranks a site rule that cancels filters (Cinejoy flat-UI mode); tripled pseudo-class |
| `b449977` | TV | OK on a faded player (controls hidden) toggles play/pause at once instead of pressing a stale control |
| `c3eb0a5` | TV | Cinejoy settings popover tiles (quality, server, subtitles) reachable with the D-pad; new profile key `player.menuDrill` |
| `723e5bd` | TV | Settings pages (Subtitle, Playback): sliders (LEFT/RIGHT ~5 %), toggles, pills, step buttons; disabled controls skipped; menu stays open after a toggle |
| `c0a827a` | TV | Cinejoy Continue Watching resume works after the site renamed Play to Resume |

## 3. Open work, by platform

### 3.1 Android TV / Google TV (the main open area)
Round 5 plan: `tv-adapter/ROUND5-REVAMP.md`. Done: L0, L1, L2, L3a, review fixes, brightness, Cinejoy player settings.
| # | Item | Status |
|---|---|---|
| T1 | **Start-screen ring speed pass.** The ring takes ~5 s ("not fast fast"). Partial, UNVERIFIED work is on branch `wip/r5-speed-ring` (StartRing/StartView/LaunchController/LoadingUi edits, `SourceWarmup`, `StartTiming`, `tvpaint.js`, `scripts/tv-test/r5-speed.mjs`). No before/after numbers yet. | OPEN |
| T2 | **L3b**: global mic search across all four sources (overlay Listening -> Searching -> Results -> Action; BACK from a detail returns to the same results), Continue Watching (Android home Watch Next via TvProvider + in-app row, resume with `movy://open`). | OPEN, not started |
| T3 | **Moonflix has no Watch History** on the site. Build a native tiles screen from Movy's own watch records (the site keeps `recentlyWatched` in localStorage and never shows it). One "Watch History" tiles item on every source. | OPEN |
| T4 | **L4 unified player settings panel + subtitle size.** Moonflix has no subtitle styling at all (Movy-owned styling needed); 7Movies speed/fit/boost live in a legacy `#settingsPanel`; Movy "Customize appearance" leaks focus into the hidden Quality menu; Sleep Timer opens with the wrong default focus. The 11-13 menu-engine fixes were proven on **Cinejoy only**. | OPEN |
| T5 | **L5**: "Exit Movy?" dialog (BACK at root, focus on Stay), cache lifecycle, warm-up during playback. | OPEN, not started |
| T6 | **L6 full QA on the Bravia** (ARMv7). Everything so far was measured on the x86 emulator; animation smoothness, the 100 ms drawer budget, first key after long idle, and the remote's mic key (emulator swallows 231/219; `KEYCODE_SEARCH` 84 reaches the app) need checking there. | OPEN |
| T7 | Cinejoy cold start ~10-24 s, switch ~5-15 s. Goal ≤ 8 s not met; the site's own ~6 s main-thread hydration (33 XHR + 125 images) dominates. We own only the ring, cover and pre-warm. | OPEN (partly outside our control) |
| T8 | Start screen and drawer show the source initial, not a logo (no logo assets in config). | OPEN (cosmetic) |
| T9 | Real speech recogniser never exercised (debug stand-in `--es movy_voice` only). Search box is made read-only, so a USB keyboard no longer types into it. | UNVERIFIED / OPEN |
| T10 | Search focus: on Movy and Moonflix RIGHT from the box first stops on the site's clear "x" before the in-page mic; Movy DOWN after typing lands on the result tabs. A profile `skip` for the clear buttons would fix it. | OPEN (minor) |
| T11 | Frame trust: `TvBridge.painted/buffering` cannot tell which frame called; a hostile frame spoofing a source URL could lift the cover early (cannot pin the spinner any more). | OPEN (low) |
| T12 | `tvnav.js` 1086 lines, `tvfs.js` ~800: over the 800-line guideline; split was skipped as risky. `MainActivity` 711 lines. | OPEN (tech debt) |
| T13 | Signed-in states never tested on TV (Account actions match site texts "Sign In"/"Login"). Cinejoy streams often fail on the emulator ("Trying Solara..."), so playback was audited on real hardware only partly. | UNVERIFIED |
| T14 | Moonflix player never sampled for resize; Movy hero trailer goes fullscreen after a key press; 7Movies embed player stays 960x540; player-spinner selectors for `loading.hide` incomplete; skip-intro / next-up cards not verified live. | OPEN / UNVERIFIED |
| T15 | Round 5 changes (native drawer, start screen, search proxy) exist for **Movy only**. YouTube Freemium still has the old Round-4 TV layer (see its own repo). | by design |

### 3.2 Android phone / tablet
No defect is recorded in the repo. Round 5 is TV-gated (`cfg.tv` / `isTv`); the phone flavour compiles (`compilePhoneDebugJavaWithJavac`) but has **not been re-tested on a device since Round 5**. To check on the OnePlus Pad 3 / a phone:
- Brightness control in web fullscreen on every source (7Movies embeds a cross-origin player iframe), status bar visible except in web fullscreen, brightness restored on exit.
- Island drawer (source switcher) still opens and switches; Settings sheet; red-spinner loading; update-over-install keeps logins (same signing key, higher build number).
- Cinejoy flat-UI mode with the brightness filter (fixed on TV in `dd60a9d`; verify on phone).

### 3.3 Desktop (Electron, Windows)
Per `DESKTOP.md`: not code-signed, so SmartScreen shows "unknown publisher". UNVERIFIED: 30-minute soak with minimize/restore, display scaling above 100 %, running the NSIS installer end to end, auto-PiP on a real minimize, brightness above 100 % on each source, Google sign-in password step (needs a test account).

## 4. Where things are
Plan `tv-adapter/ROUND5-REVAMP.md`; resume log `tv-adapter/ROUND5-PROGRESS.md`; per-level results `tv-adapter/REPORT.md`; measurements `tv-adapter/R5-INVENTORY.md`; site notes `site-dossiers/*.md`; emulator harness `scripts/tv-test/`.
