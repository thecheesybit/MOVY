# TV adapter plan (stepwise, with gates)

Test bed: Google TV emulator (`GoogleTV_1080p`, keyboard enabled, `adb -s emulator-5554`). Real TV is the last check.
Debug helpers: `movy_source <id>` opens a source, `movy_eval <js>` runs JS (result in logcat tag `MovyDebug`).
Note: the shell hides the focus ring and swallows the first key after 5 s idle (wake key). Tests must press a wake key first.

## Findings so far
- Emulator remote was dead because the AVD had `hw.keyboard = no`. Fixed (`yes`). Arrow keys, OK, Back reach the app.
- Movy: D-pad row/column navigation works; OK on hero Play opens the in-page player, fullscreen, own controls, focus ring OK.
- Viewport: the shell forces `width=1280` through the viewport meta, but sites re-assert their own meta on route changes
  (innerWidth fell back to 960 after navigation) and the fixer gave up after 25 fixes. Fixed with a rate limited watcher
  (needs rebuild + verify). Cinejoy shows its broken phone layout at 960, so this is the likely cause of its bad nav bar.

## Three additions from the user
1. **Hide site chrome with element-hiding filters** (Brave cosmetic rules, `##selector`, per source): remove the site's own
   logo, floating header, "scroll to top" button, notification regions, cookie banners. Lives in `config/apps/movy.json`
   (remote-updatable) and goes through the existing cosmetic engine; no code per site.
2. **Native per-site side drawer (Android TV style)** instead of walking the site's own nav: right-side drawer, one per
   source, entries: Home, Movies, Shows, (Anime), Search, My List / Resume, Sign in. Selecting an entry loads that route
   (relative path from config) or runs a small adapter action (open search, focus site search field). Opened with MENU or
   long OK (the existing Island mechanism), D-pad driven natively, so it cannot be broken by site layout.
3. **Preload before reveal**: the splash stays up until the adapter is applied (viewport 1280, hide CSS, cosmetic rules,
   first focus set), signalled by the page via `MovyTvNative.ready()`, with a hard cap (e.g. 6 s) so a slow site never hangs.

## Stages and gates
| # | Stage | Pass gate (on emulator) |
|---|---|---|
| 0 | Emulator remote works | done: user confirmed keys move focus |
| 1 | Robust 1280 viewport | `innerWidth` stays 1280 after 3 route changes on all four sites (not in fullscreen) |
| 2 | Preload gate (splash waits for adapter) | no visible un-styled frame; cap works when adapter never reports |
| 3 | Cosmetic hide rules per source | logo/header/overlay elements absent on each home; no layout hole |
| 4 | Drawer core (native, config driven, relative routes) | open/close with MENU and long OK, Back closes, focus trapped, entries navigate |
| 5 | Movy + Cinejoy: drawer entries, page nav, player, Back | search works, play, seek, fullscreen, Back leaves player then page |
| 6 | 7Movies + Moonflix: popup/overlay blocking, cross-origin player | no ad tab, no fake overlay, OK/Left/Right control the player |
| 7 | Cinejoy speed: TMDB API + image cache, preconnect | second home load clearly faster; request count and time measured before/after |
| 8 | Full regression of all four + real TV check | checklist below passes on emulator, then on the user's TV |

## Per-source checklist (run for each)
Open source, wake key, move through hero/rows, OK opens detail, Play starts video, seek +-10 s, pause, Back exits player,
Back again goes to previous page, drawer opens and each entry works, search finds a title, no ad tab or overlay appears.

## Rules
- Source URLs never go in code comments, logs, docs or commits (config is git-ignored).
- Anything that does not open or needs a login the agent cannot do: ask the user. Never sign in to a Google account.
- No commit until the user asks.
