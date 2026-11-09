# Movy TV — Round 5, Level 0: baseline and site capability inventory

Measured 2026-11-10 on the Android TV emulator (`emulator-5554`, x86, ~1280x720 CSS layout at dpr 2, 960x540 real window),
build = HEAD `8105c36` rebuilt with `b.ps1`, debug TV flavour. Sites are named Movy, Cinejoy, 7Movies, Moonflix; no
hostnames anywhere (search routes are path templates). Raw per-run data lives in the session scratchpad; the helpers are
committed under `scripts/tv-test/`: `r5-sample.mjs` (frame sampler, key presses, BACK timing), `r5-probe.mjs` (per-frame
script/resource probe, script exec cost), `r5-net.mjs` (CDP network capture with host and key redaction).

Caveats that apply to every number: the emulator CPU is much faster than the Bravia (ARMv7), but its network and stream
start are slower and noisy (video `waiting` stalls every 10-20 s on Cinejoy/7Movies); "first poster" is the first `<img>` of
at least 60 px that is `complete` in the visible document (`paint-wait.mjs`). Default source is now Cinejoy, so Cinejoy is the
first thing a user sees.

## 1. Timings (5 runs, median and range, ms)

| Metric | Movy | Cinejoy | 7Movies | Moonflix |
|---|---|---|---|---|
| Cold launch (caches cleared, process killed) to first poster | 5974 (5328-7023) | 24144 (22944-26011) | 7081 (5934-7881) | 11928 (11400-14748) |
| Second launch (process killed, caches warm) to first poster | 5537 (4964-6238) | 22565 (20526-23963) | 6467 (5725-8676) | 10819 (10124-12233) |
| Switch from another source, to first poster | 2552 (2284-2689) | 15685 (13657-20210) | 3139 (2857-3405) | 6640 (4656-8576) |
| Open title, OK press to video `playing` | 5695 (5188-11962) | 12835 via hero Play (n=4: 11318-21033); 17792 via route (16466-28733) | 21379 (10181-30584) | 16837 (n=3 of 5 started: 14580-19939) |
| BACK from player to catalog interactive (3 presses needed, see below) | 6750 (n=3: 6213-13111) | 8644 (n=3: 8537-13361) | 11189 (n=3: 6004-11835) | not measurable (see below) |

How each was taken:
- Launch/switch: `paint-wait.mjs launch|switch`. Switch order cinejoy, 7movies, moonflix, movy, 5 rounds, 8 s apart, so "switch" benefits
  from `SourcePreloader`/shell snapshots; Movy and 7Movies (2.5-3.1 s) are the preloaded fast cases.
- Open title: Movy OK on detail `Play`; Cinejoy OK on hero `Play` (which routes to the watch page) and, separately, direct navigation to the
  watch route (autoplay); 7Movies OK on detail `Play` (then the embed loads); Moonflix OK on hero `More Info` + OK on modal `Play`
  (2 of 5 runs never started playing in 70 s: the modal press raced the modal mount). `playing` is a `video` event in any frame.
  The times are dominated by the stream start on the emulator, not by the app.
- BACK: the first key after 5 s idle is swallowed by `tvIdle.onKey` (wake only), so the harness sends BACK at 0, 0.7 s and 3 s. In a fullscreen
  player the chain is: wake, release control focus, leave fullscreen, then history back; so a real remote needs 2-3 presses. Earlier
  harness BACKs spaced 5 s apart never left the player (each was swallowed by the idle gate): that is a harness artefact, not an app
  bug, but it is a real UX cost (one dead key after every pause). Moonflix: only 2 successful exits (3620 ms, 1 press; 9985 ms, 2 presses).
- Not measured: "catalog interactive" is "no fullscreen, no playing video, focus ring present", it does not wait for posters.

RAM (`dumpsys meminfo com.ayushkumar.movy.tv`, TOTAL PSS), 11 min of browsing (all four sources cycled 3x, 11 D-pad keys + BACK each):

| Time | PSS (MB) | Note |
|---|---|---|
| cold start on Movy | 96.9 | RSS 234.8 |
| after first Cinejoy visit | 124.7 | |
| 2-5 min | 119-130 | |
| peak (Cinejoy, 7 min) | 161.9 | RSS 344 |
| end (667 s) | 125.1 | RSS 272.9, Java heap 32 MB at end, Native 20 MB at start |

No monotonic growth over 11 min (125 MB at the end vs 124.7 after the first visit), but spikes of +35 MB while Cinejoy loads.

## 2. Scripts per page type

All ten scripts are registered with `addDocumentStartJavaScript(..., "*")` in `CosmeticBridge.install` and therefore run in EVERY frame
of every document, including 0x0 trace/ad iframes and the cross-origin player embeds. There is no per-page-type gating beyond early
returns inside each file.

| Script | Size | Catalog (top) | Detail (top) | Player page (top) | Player iframe(s) | Top-level exec on emulator (fresh doc, median of 5) |
|---|---|---|---|---|---|---|
| `cosmetic.js` (ad cosmetic) | 5.3 KB | yes | yes | yes | yes | 0.2 ms |
| `loading.js` (video red arc) | 6.5 KB | yes | yes | yes | yes (draws the arc) | 0.3 ms |
| `navguard.js` | 9.8 KB | yes | yes | yes | yes | 1.5 ms |
| `scamguard.js` | 5.4 KB | yes | yes | yes | yes | 0.0 ms |
| `tvnav-pure.js` | 15.3 KB | yes | yes | yes | yes | 0.0 ms |
| `tvnav.js` (focus engine, rail drawer, search) | 79.5 KB | yes | yes | yes | yes (one instance per frame) | 1.4 ms (1.1-7.0) |
| `tvfs.js` (fullscreen, media, keys, viewport) | 35.9 KB | yes | yes | yes | yes | 2.7 ms (0.6-7.2) |
| `tvinset.js` (rail inset) | 7.0 KB | yes | yes | yes | yes | 0.0 ms |
| `tvmenu.js` (player menu engine) | 14.4 KB | yes | yes | yes | yes | 0.3 ms |
| `tvshell.js` | 3.3 KB | yes | yes | yes | yes | 0.0 ms |

Total about 6-9 ms of parse and top-level run per document on the emulator (about 187 KB of JS). The real cost is steady state: `tvnav`
candidate rebuilds (`TV.stats`) on a freshly loaded home: Movy 11-26 rebuilds, last 27-35 ms; Cinejoy 6 rebuilds, 8 ms; 7Movies 9 rebuilds,
118 ms (3131 DOM nodes); Moonflix 10 rebuilds, 77 ms (2400 nodes). Multiply by 5-10 for the Bravia. On a 7Movies watch page the probe
saw 5 frames (top, two 0x0 frames, a 1280x720 wrapper, the player embed) each running the full bundle; Moonflix has top, wrapper and
up to three nested frames (several 0x0).

Third-party scripts seen on catalog pages that are NOT blocked in this build (all returned 200; roles only):
| Site | Unblocked third-party scripts and calls |
|---|---|
| Movy | an analytics proxy script (1, about 0.6 s), an obfuscated-path script, first-party-like `POST /api/track` events |
| Cinejoy | product-analytics event POSTs (`POST /api/event`, about every page view and engagement), 1 unidentified third-party script and 1 more script host |
| 7Movies | Google-tag analytics (gtag), Cloudflare web-analytics beacon, 2 obfuscated-path script hosts (ad/redirect style) |
| Moonflix | Google-tag analytics, Cloudflare web-analytics beacon, 2 further analytics/ads tag scripts, a Yandex-style metrics script, product-analytics `POST` events (2 endpoints, about 1.1-1.9 KB each), a third-party "analysis" XHR host (4 calls); 8 analytics scripts in total by resource timing. The R4 dossier said the ad engine blocks the Moonflix trackers; they are loading now. |

## 3. Player settings matrix

Legend: works = reachable and applies with the D-pad (verified or covered by Round 4 gate); unreachable = exists on the page but the
D-pad cannot focus it (profile `never` rule on `input`, sliders, colour pickers, or focus escapes); broken = reachable but wrong.
"Immediate" = the choice applies at once and `tvmenu` closes the menu after 250 ms.

### Movy (in-page `#vp-shell`, same-origin video, depth 0)
Open: top bar toggles `#vp-shell button.tabbable` (`Quality 1080p`, `Audio & Subtitles`) after UP shows the controls, RIGHT to the toggle, OK.
Menu root `#vp-shell .player-surface` (all menus stay mounted, visible via opacity). Close: BACK = Escape; selection closes it.

| Option | Items | Item selector | Apply | Status on TV |
|---|---|---|---|---|
| Quality | 2160p, 1080p Full HD, 720p HD, 480p | first `.custom-vertical-scroll` column buttons | immediate | works |
| Server | 18 cities (Miami, Boise, Nashville, Chicago, Vegas, Reno, Oakland, Seattle, Phoenix, Atlanta, Portland, Austin, Dallas, Munich, Berlin, Paris, Delhi, Cancun) + favourite star | second column buttons (`Favorite server` skipped) | immediate | works |
| Speed | 0.5x 0.75x 1x 1.25x 1.5x 2x | row of buttons under the columns | immediate | works (DOWN from the columns reaches `0.5x`; LEFT/RIGHT walk) |
| Audio track | list in the left column of the Audio & Subtitles menu | `.custom-vertical-scroll` col 1 | immediate | works (empty for the test title: "No audio tracks available") |
| Subtitle track | `Off` + tracks (empty for the test title) | col 2 | immediate | works; `Upload subtitles` label (file input) skipped; `Search on OpenSubtitles` footer exists |
| Subtitle size | slider `input[type=range]` 16-50 (default 23) | inside `Customize appearance` drill | live | unreachable (`player.never: input`; only `Reset` is focusable) |
| Subtitle colour | colour input + 3 number inputs R/G/B | same drill | live | unreachable |
| Subtitle background | "Background blur" slider 0-1 | same drill | live | unreachable |
| Subtitle position | none | | | missing |
| Sync | "Latency" text, only active with a selected caption | same drill | | unreachable |
| Fit/aspect | none in UI (video letterboxed by container) | | | missing |
| Auto-next / skip intro | not inspected (movie only; no episode tested) | | | unknown |
| Other | `Reset` (subtitle appearance) | | | works |
Bug seen: after OK on `Customize appearance` focus jumped to `Reno` (an item of the hidden Quality menu): the drill panel is not the focus scope.

### Cinejoy (in-page `.player-root`, same-origin video)
Open: bar buttons by `aria-label` (`Servers`, `Subtitles`, `Settings`): UP wakes the bar, DOWN enters it, RIGHT to `Settings`, OK; one popover `.settings-popover[role=dialog]` mounted when open. Close: only the toggle closes it (profile `menuClose: toggle`); BACK works through the toggle.
**Cinejoy DOES offer quality: Settings (gear) popover, first tile `QUALITY Auto`, sub-list `Auto, 1080p, 720p, 360p`** (confirmed by DOM; stream also self-switches 640x360 to 1280x720 by itself).

| Option | Where | Items | Apply | Status on TV |
|---|---|---|---|---|
| Quality | Settings, tile `QUALITY` | Auto, 1080p, 720p, 360p | sub-page, choice applies | unreachable: tiles are a 2x2 grid above `Sleep Timer`; on open focus lands on `Sleep Timer` and UP does not enter the grid (4 UPs, focus unchanged) |
| Server | Settings tile `SERVER` (also `Servers` cloud button) | Secret, Nebula, Lisbon, Solara, Athens | choice applies | unreachable via the tile (same reason); the `Servers` bar button was reachable but its popover did not open from a synthetic press |
| Audio track | Settings tile `AUDIO` | ENGLISH 5.1, Commentary By Director | choice applies | unreachable (tile) |
| Subtitle track | `Subtitles` bar button, or tile | `Customize`, `Off`, `Upload subtitle file` (file, never), `Sync subtitle to audio` (drill), language rows with counts (English 73, Arabic 22 ...) that drill into track lists, `Ae EXTERNAL` rows with a translate trigger (never) | choice applies | works for the bar button route (R4) |
| Subtitle settings | Settings, `Subtitle Settings` | `Use native subtitles` toggle, `Pastel mode`, subtitle delay (range -20..20 + number), Font (Cinejoy/Netflix/Montserrat), Text size (range 50-200%), Position (range 0-50), Background (range), Color (colour input) | live | sliders/colour unreachable (`input` never); font buttons reachable in principle |
| Speed | Settings, `Playback Settings` | 0.25x, 0.5x, 1x, 1.5x, 2x | immediate | reachable via drill when focus is inside the popover |
| Fit/aspect | `Playback Settings`, "Zoom" | Original, Fill, Stretch | immediate | reachable via drill |
| Volume boost / brightness | `Playback Settings` | sliders 100-600% / 50-600%, `Reset brightness`, `Modify colors` | live | unreachable (sliders) |
| Sync | subtitles page `Sync subtitle to audio`, subtitle settings "Subtitle delay" | | | drill row reachable, slider not |
| Sleep timer, Watch Party, Enable Subtitles | Settings list | `-`/`+` stepper (30), `End of episode` switch | live | Sleep Timer opens by OK but is the wrong default focus |
| Auto-next / skip intro | none seen on the movie page | | | not inspected for series |
Stream state: the emulator played Cinejoy during this inventory (no "Trying Solara"); video stalled ('waiting') every 10-20 s. Both the site spinner (grey ring) and our `loading.js` red arc draw over the video at once (see section 7).

### 7Movies (cross-origin embed iframe; controls live in the embed, frame with `#lbQuality`)
Open: top bar `#lbQuality`, `#lbServer`, `#lbSubs` (labelled `Audio & Subtitles`), `#lbNext`; menus `.lb-menu.show` (`#lbQualityMenu`, `#lbSubsMenu`), rows `.lb-opt` (`.active` = selected), drill rows `.lb-opt.lb-drill`. Close: Escape; selection closes.

| Option | Items | Apply | Status on TV |
|---|---|---|---|
| Quality | Auto, 720p, 2160p | immediate | works (R4 gate) |
| Server | BigBang, Vexon, Vela, Orion, Titan, Atlas, Lyra (same menu, right column) | immediate | works |
| Audio track | English, Hindi, Spanish, Portuguese, French, Russian, Spanish Latin, Ukrainian | immediate | works |
| Subtitle track | Off + per-provider and generic languages (Arabic... Danish...) | immediate | works |
| Subtitle size/colour/bg/position/delay | `Customize appearance` drill: ranges -300..300 (sync), 50-200 (size), 0-40 (position), checkbox, colour input | live | unreachable (sliders, checkbox, colour) |
| Sync | drill `Sync to audio` | live | unreachable (slider) |
| Speed | legacy `#settingsPanel` ("Playback settings") | | `.panel` is in the profile `never` list: unreachable |
| Fit/aspect | `#settingsPanel` "Video size": Fit, Fill, Stretch | | unreachable (legacy panel) |
| Volume boost | `#settingsPanel`: Off, 2x, 3x | | unreachable |
| Skip intro / auto-next | `#introSkip` (`Skip intro`), `#nextUpPlay`/`#nextUpCancel` (next-up card), `#lbNext` `Next Episode`, `#nextEpBtn` | | candidates in the control list for `#lbNext`; skip-intro and next-up not verified live |
| Other | lock `#lockBtn`, cast `#castBtn`, PiP `#pipBtn`, episodes `#episodesBtn`, `#exitBtn` | | exists; most are in `controls` |

### Moonflix (nested iframes; interactive frame has `#audio-subtitle-toggle`, frame numbering differs per run)
Open: `#audio-subtitle-toggle`, `#speed-button`, `#servers-button`, `#fit-button` (bar at the bottom right). Menus use the `hidden` class: `#audio-subtitle-menu`, `#speed-quality-menu`, `#server-picker-menu`. Close: the toggle (profile `menuClose: toggle`; Escape does nothing).

| Option | Items | Item selector | Apply | Status on TV |
|---|---|---|---|---|
| Audio track | English, Hindi (selected = `font-bold`) | `li.track-selector` col 1 | `Apply` / `Cancel` buttons exist but are zero-size: choice applies at once | works (R4 gate) |
| Subtitle track | Off, English [CC] | col 2 | same | works |
| Speed | 0.25x ... 2x (Normal selected) | `#speed-quality-menu li` | `Done` / `Cancel` zero-size: applies at once | works |
| Quality | Auto, 1080p, 720p, 480p | second `ul` of the same menu | same | works |
| Server | Moonflix Player, Nitno, Ranger, Filem, Hexon ("EMBED PLAYER") | `#server-picker-menu button` | immediate | works |
| Fit/aspect | `#fit-button` cycles "Video Fit" | | immediate | works as a control |
| Subtitle size/colour/bg/position, sync | none (native text tracks, no UI) | | | missing |
| Skip intro / auto-next / episodes | `#skip-prompt-btn`, `#next-episode-button`, `#episode-panel-button` | | | present in `controls`; not verified live |
Also: the site's overlay close button `button.absolute.top-4.right-4.z-50` (`Close player`) is a focus target right after play (profile `hide` list, yet it showed with a focus ring in the screenshot).

Notable gaps: no D-pad access to any slider, colour or checkbox input on any site (subtitle size/colour/background/position/sync, Cinejoy volume/brightness); Cinejoy's most important tiles (quality, server, audio) are unreachable; no site exposes subtitle position except Cinejoy/7Movies sliders; Moonflix has no subtitle styling UI at all.

## 4. Search per site

Common: the site search is typed into the site's own `<input>` in the WebView. Focusing it opens the Gboard TV keyboard over the lower third of the screen (screenshots `r5-L0-cinejoy-search`, `r5-L0-7movies-search`); with the IME up, DOWN does not leave the input (focus stayed on the input for 2 DOWNs on Cinejoy and 7Movies), so results are not D-pad reachable until the IME is dismissed. On Movy and Moonflix (results in an overlay under the header) DOWN fell through to the page behind (`See trailer`, then a card), i.e. the results overlay is not the focus scope. (Movy/Moonflix typing was done through DOM events, so the IME was not up there.)
Debounce: results fire as you type; no submit needed on any site (Enter not required).

| Site | UI route | Input selector | Results structure |
|---|---|---|---|
| Movy | overlay in the header, no route change; header button `button[aria-label="Search"]` | `input[aria-label="Search"]` (type text) | dropdown list of `a[href^="/movie/"|"/tv/"]` links (143 anchors in page incl. home) |
| Cinejoy | route `/search`; the rail/header button `button[aria-label="Search"]` navigates there | `input[type=text][placeholder^="Search movies"]` (focused on load) | grid of poster cards under "Discover something new"; links `/movie/{id}-{slug}-{year}` and `/series/{id}-{slug}-{year}` |
| 7Movies | route `/search` (full page "What are you watching?") via header `button` aria-label Search | `input[type=search][aria-label^="Search movies"]` | grid of cards plus a "People" section |
| Moonflix | overlay opened by `button[aria-label="Open search"]`, no route | `input[type=search][aria-label="Search"]` | dropdown list in the overlay; picking a card opens the in-page modal |

### Site search requests (captured with CDP while typing "fight club", path templates only)
| Site | Request | Auth | Result list path | id / title / poster / year / type | Detail path from the fields |
|---|---|---|---|---|---|
| Movy | `GET /3/search/multi?language=en&page=1&query={q}` (TMDB-compatible, external host) | none: no cookie, no custom header | `results[]` (20 per page; `total_pages`) | `id` / `title` (movie) or `name` (tv) / `poster_path` / `release_date` or `first_air_date` / `media_type` (`movie`, `tv`, `person`) | `/movie/{id}`, `/tv/{id}` (`?play=true` autoplays) |
| Cinejoy | `GET /3/search/multi?language=en-US&query={q}&page=1&include_adult=false&api_key=<public client key>` (TMDB, external host), plus a first-party `GET /api/lists/public/search?q={q}&tmdbIds={csv}` for user lists, plus a per-result `GET /3/{movie|tv}/{id}?append_to_response=...` fetch for every hit | key in the query string only; no cookie; no custom header (list search sends `Content-Type`) | `results[]` (20) | same TMDB fields as Movy | `/movie/{id}-{slug}-{year}` -> redirects to `/watch/movie/{id}`; `/series/{id}-{slug}-{year}`; the slug is the kebab-case title |
| 7Movies | `GET /api/search?v=5&q={q}` (same-origin, JSON) | cookie sent (`cookie=true`); no custom header; not tested without the cookie | `results[]` (19) and `people[]` | `id` / `title` / `poster` (full image URL) / `year` (string) + `releaseDate` / `mediaType` (`movie` or `tv`); also `rating`, `backdrop`, `genre`, `description` | `/movie/{id}`, `/tv/{id}` (watch = `/movie/{id}/watch`) |
| Moonflix | `GET /3/search/multi?api_key=<public client key>&query={q}&page=1` (TMDB, external host) | key in query string only; no cookie, no custom header | `results[]` | same TMDB fields | no URL: the detail view is an in-page modal (`div.fixed.z-[300]`); the route `/movies`, `/tv-shows` exist but a title has no path |
No site renders search results server-side; all are client-rendered from JSON. Cinejoy additionally POSTs analytics events per search page view.

## 5. Drawer items per site (compare with `SOURCE_THEMES` in `packages/inject/tvnav.js` lines 805-862)

Rail items that exist today: Search (action), Home, Movies, Shows, per-site extras, My List/History/Watchlist/Settings/Account.
Checked routes by navigating to them and reading the page title.

| Item | Movy | Cinejoy | 7Movies | Moonflix |
|---|---|---|---|---|
| Home | `/` | `/` | `/` | `/browse` |
| Movies | `/browse/movie` ("Movies") | `/movies` | `/movies` | `/movies` |
| Shows | `/browse/tv` ("Shows") | `/series` ("TV Series") | `/tv` | `/tv-shows` |
| Extra | Anime `/browse/anime` | Shorts `/shorts` (loads "Discovering movies...") | Discover `/discover` | New & Popular `/new-popular` |
| My List / Watchlist | action: account menu item `Watchlist` (no route; `/watchlist` redirects to `/`) | `/lists` ("My Lists") | action `pick` header button "My List"; a route `/watchlist` also exists ("Watchlist") | `/my-list` ("My List") |
| History / Continue watching | action: account menu item `History` (no route; `/history` redirects to `/`) | `/continue-watching` ("Watch History") | none in theme; route `/history` does not exist (error page) | none (recently viewed list is our own `movy_tv_recent`) |
| Settings | none in theme | `/settings` ("Settings") | `/settings` ("Settings") | none |
| Account | action `pick` on `header button[aria-label=Login]` menu (History, Watchlist, Watch Party, Login) | action `account`: gear menu, Login is a 16-digit account number box | action `pick` on `button[aria-label=Settings]` (Sign in/Settings) | none |
| Watch Party | account menu `Watch Party` | inside player Settings | none | none |
| Signed-out behaviour | History/Watchlist ask to sign in | `/lists`, `/continue-watching` load (empty states; localStorage based) | `/watchlist` loads | `/my-list` loads |
Gaps vs the Round 5 order (Sources, Search, Home, Movies, Shows, extra, My List, History, Continue watching, Settings, Account): Moonflix has no Settings/Account/History; Movy and 7Movies have no direct History route; 7Movies `My List` could be the route `/watchlist` instead of a DOM pick; Cinejoy and 7Movies have Settings routes; Movy has none.

## 6. Resize bug (viewport and video rect from OK to 5 s after `playing`)

Method: `r5-sample.mjs` injects a sampler in every frame (200 ms ticks + `resize`, `fullscreenchange`, video events). Layout is the forced 1280-CSS-px viewport (`<meta viewport width=1280>`; WebView overview mode), real window 960x540 CSS px at dpr 2.

| Site | Before fullscreen | Change (time after `playing`) | After |
|---|---|---|---|
| Movy (`#vp-shell` in top doc) | `innerWidth 1280x720`, `visualViewport 1280x720 scale 0.8`, `<video>` rect 0,0,1280x720 for 1.57 s | at +1.57 s: `resize`, `fullscreenchange`; `visualViewport` 960x540 scale 1, video rect 960x540 for one sample (about 150-300 ms), then back to 1280x720 (the `tvfs` zoom compensation 0.75 on the fullscreen element) | `innerWidth` stays 1280x720 reported, video rect 1280x720, `fs=true` |
| Cinejoy (`.player-root`) | same as Movy, video natural 640x360 | +1.54 s: identical pattern (960x540 for about 150 ms then 1280x720) | same; a second `resize` at +4.4 s is the stream switching 640x360 to 1280x720 |
| 7Movies (cross-origin embed) | embed frame `innerWidth 1280x720`, vv scale 1; video rect 1280x720 (video 1280x534) | +2.2 s: parent goes fullscreen (vv 960x540), the EMBED window resizes to `960x540` and the video rect becomes 960x540 and STAYS there (no compensation in the embed): the player UI re-lays out about 33% larger | embed 960x540 |
| Moonflix (nested embed) | embed 1280x720, vv scale 1 | no resize within 5 s after `playing` (fullscreen not yet entered; video 888x480) | unchanged |
Cause: `tvfs` requests fullscreen only about 1.5-2.2 s after `playing`, so the video plays inline at the 1280-px layout first. Native fullscreen replaces the layout viewport with the real window (960 CSS px), the page reflows 1280 to 960; the compensation (`zoom` set in the `fullscreenchange` handler) arrives one frame later in the top document and never in cross-origin embeds. Result: visible one-frame shrink-and-restore on Movy/Cinejoy and a permanent re-layout on 7Movies. Screenshots of the jump itself were not captured (adb screencap is about 500 ms, the transient 150-300 ms); the rect log above is the evidence. Layout bug also seen: Moonflix hero title is clipped at the top edge (`r5-L0-moonflix-home.png`).

## 7. Loading indicators

| # | Indicator | Where drawn | When | Selector / class | Overlaps |
|---|---|---|---|---|---|
| 1 | Launch splash with `SpinnerView` | native `SplashView`, above WebView | app start until `dismissing TV splash` | native | none |
| 2 | 3 dp red top progress bar | native `LoadingUi` (`content` FrameLayout) | `begin()` on source switch, then `onProgressChanged`; suppressed in fullscreen | native | with site skeletons |
| 3 | 48 px red arc over each `<video>` | `loading.js`, every frame | `loadstart`, `waiting`, `stalled`, `seeking` after 120 ms debounce; hides on `playing`/`canplay` | injected div, z-index 2147483647 | with the site player spinner |
| 4 | Movy catalog | none seen (server-rendered, poster pop-in) | | | |
| 5 | Movy player centre spinner | site | player open / buffering | in `#vp-shell` (red arc; one arc visible in screenshots) | possibly stacked with #3 |
| 6 | Cinejoy catalog skeleton | site | page load | `.skeleton` (svelte scoped), `div.animate-pulse` (hero) | |
| 7 | Cinejoy player spinner | site, grey ring | buffering | inside `.player-root` | **two rings at once**: site grey ring plus our red arc (`r5-L0-cinejoy-detail.png`) |
| 8 | 7Movies catalog skeleton | site | page load | `div.rail-skeleton`, `div.rail-loader` | |
| 9 | Moonflix catalog skeleton | site | page load | `div.animate-pulse` | |
| 10 | Moonflix player | site red arc; blank black player before | buffering | in the wrapper frame | with #3 |

Observed on a source switch to Cinejoy (`r5-L0-switch-cinejoy-1s/4s/10s.png`): at 1 s the old page is still shown with only the thin red top bar; at 4 s the screen is completely black except the injected rail; at 10 s still blank/skeleton: for 10+ seconds there is no spinner or caption at all, only the 3 dp bar at the very top. There is no native centred spinner after the splash.

## 8. Top 5 causes of lag (observed)

1. **Cinejoy catalog fan-out**: 33 metadata XHRs on the home (resource-timing sum 57 s, parallel), about 125 image requests, 6 scripts; cold first poster 22.9-26.0 s, second launch 20.5-24.0 s, switch 13.7-20.2 s, with a blank black page and no spinner for about 10 s. Cinejoy is now the default source, so this is the first thing users see.
2. **Moonflix weight and unblocked trackers**: 73+32+7 images plus 11 metadata XHRs, 8 analytics/replay scripts and 2 event POST endpoints all returning 200; first poster 10.1-14.7 s, switch 4.7-8.6 s.
3. **A full bundle in every frame, and heavy rescans**: 10 scripts (187 KB) in each of 4-5 frames on watch pages; `tvnav` rebuild 77-118 ms per rebuild on 7Movies/Moonflix homes with 9-26 rebuilds in the first 10 s, on an x86 emulator (5-10x worse on ARMv7).
4. **Fullscreen reflow 1.5-2.2 s after `playing`** (section 6): reflow of the whole player UI, permanent on 7Movies, plus a visible shrink/restore on Movy and Cinejoy.
5. **Cold-start and keystroke latency**: cold Movy first poster 5.3-7.0 s even with caches warm (4.9-6.2 s), the first key after 5 s idle is swallowed, and leaving the player takes 3 BACKs and 6-13 s; RAM climbs to 125-162 MB PSS while Cinejoy loads.
Streams themselves (5-30 s to `playing`, repeated `waiting`) are slow on the emulator regardless of the app and are not counted here.

## 9. Remote keys (emulator, `adb shell input keyevent`, logcat `MovyKeys` = `MainActivity.dispatchKeyEvent`)

| Key | Code | Reaches the app? | What happens |
|---|---|---|---|
| `KEYCODE_SEARCH` | 84 | yes: `MovyKeys: key KEYCODE_SEARCH (84) DOWN/UP` logged | `TvKeys.classify` has no case for it, so it falls to `super.dispatchKeyEvent` and the page; nothing happens today |
| `KEYCODE_VOICE_ASSIST` | 231 | no `MovyKeys` line | the system starts the Assistant (`android.speech.action.WEB_SEARCH` to the Katniss/TV search package, Assistant setup screen on the emulator) in front of Movy |
| `KEYCODE_ASSIST` | 219 | no `MovyKeys` line | the system starts the Assistant search result activity in front of Movy |
So only `KEYCODE_SEARCH` can be handled in `TvController`/`TvKeys`; 231 and 219 are consumed by the system before `dispatchKeyEvent` on this image (a real Sony remote mic key normally arrives as 231 or 219 and will also go to the Assistant, so our own on-screen mic button is the reliable path). Other keys seen to reach the app earlier: MENU (82), BACK (4), D-pad, media keys, `KEYCODE_SHIFT_LEFT` (wake). `KEYCODE_SEARCH` should be mapped in Level 3.
Source default: `recommendedSource` in `config/apps/movy.json` is `cinejoy`.
